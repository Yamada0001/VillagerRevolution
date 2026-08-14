package dev.bettervillagers.villager;

import dev.bettervillagers.BV;
import dev.bettervillagers.i18n.MessageService;
import dev.bettervillagers.profession.EquipmentApplier;
import dev.bettervillagers.profession.EquipmentDurability;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.profession.ProfessionData;
import dev.bettervillagers.scheduler.ScheduledHandle;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 村民运行期管理器（规范 3.x / 4.2 / 4.5）。
 * <p>
 * 维护在线村民注册表，负责生成注册、职业分配、装备应用、
 * 周期性 AI tick（战术/战略）与定时保存（WAL 风格）。
 * <p>
 * 修复（问题1）：新村民生成 AI 中世纪风格随机名字，头顶显示"名字-职业"（彩色）；
 * 修复（问题2）：新增快速战斗 tick（10 tick = 0.5s），确保战斗职业持续追击敌对生物。
 */
public final class VillagerManager {

    private final Map<String, BVillager> online = new ConcurrentHashMap<>();
    private final Map<String, Long> registrationTokens = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pendingRegistrationClaims = new ConcurrentHashMap<>();
    private final Map<String, Profession> pendingInheritedProfessions = new ConcurrentHashMap<>();
    private volatile int kingThreshold;
    private final KeyedSerialExecutor persistence;
    private final AtomicLong registrationSequence = new AtomicLong();
    private final AtomicInteger tacticalCursor = new AtomicInteger();
    private final AtomicInteger workCursor = new AtomicInteger();
    private ScheduledHandle tacticalHandle;
    private ScheduledHandle strategicHandle;
    private ScheduledHandle saveHandle;
    private ScheduledHandle cleanupHandle;
    private ScheduledHandle combatHandle;
    private ScheduledHandle workHandle;
    private ScheduledHandle socialHandle;
    private volatile boolean shuttingDown;

    public VillagerManager(int kingThreshold) {
        this(kingThreshold, 4);
    }

    public VillagerManager(int kingThreshold, int persistenceThreads) {
        this.kingThreshold = kingThreshold;
        this.persistence = new KeyedSerialExecutor(persistenceThreads);
    }

    /** 注册新出现/加载的村民（区块加载或生成时调用）。 */
    public void register(Villager entity) {
        String uuid = entity.getUniqueId().toString();
        if (shuttingDown || online.containsKey(uuid)) {
            return;
        }
        long token = registrationSequence.incrementAndGet();
        if (registrationTokens.putIfAbsent(uuid, token) != null) {
            return;
        }
        // 以下值须在主线程/区域线程（本方法调用线程）提取为纯值，
        // 避免在后续 runAsync 中访问实体/世界 API（规范：异步禁止访问游戏世界/实体）
        org.bukkit.Location spawnLoc = entity.getLocation();
        // 生电保护区内不激活 AI（规范 5.2）
        boolean inRedstone = BV.regions() != null && BV.regions().isProtected(spawnLoc);
        RegistrationSeed seed = captureRegistrationSeed(entity, spawnLoc, inRedstone);
        persistence.execute(uuid, () -> prepareRegistration(entity, uuid, token, seed));
    }

    private RegistrationSeed captureRegistrationSeed(Villager entity, org.bukkit.Location spawnLoc,
                                                       boolean inRedstone) {
        String biome = "plains";
        try {
            biome = spawnLoc.getBlock().getBiome().getKey().getKey().toLowerCase().replace('_', ' ');
        } catch (Throwable ignored) {
            // 某些平台/世界类型可能无法获取生物群系
        }
        return new RegistrationSeed(entity.getWorld().getName(),
                spawnLoc.getX(), spawnLoc.getY(), spawnLoc.getZ(),
                spawnLoc.getBlockX(), spawnLoc.getBlockY(), spawnLoc.getBlockZ(), biome, inRedstone);
    }

    /** Registers a bred child with the configured inheritance chance of one parent profession. */
    public void registerOffspring(Villager child, Villager mother, Villager father) {
        registerOffspring(child,
                mother == null ? null : mother.getUniqueId().toString(),
                father == null ? null : father.getUniqueId().toString());
    }

    public void registerOffspring(Villager child, String motherUuid, String fatherUuid) {
        Profession motherProfession = parentProfession(motherUuid);
        Profession fatherProfession = parentProfession(fatherUuid);
        double motherRate = inheritanceRate(motherProfession);
        double fatherRate = inheritanceRate(fatherProfession);
        Profession inherited = selectInherited(motherProfession, motherRate, fatherProfession, fatherRate,
                java.util.concurrent.ThreadLocalRandom.current().nextDouble(),
                java.util.concurrent.ThreadLocalRandom.current().nextDouble());
        String uuid = child.getUniqueId().toString();
        pendingInheritedProfessions.put(uuid, inherited);
        BVillager registered = online.get(uuid);
        if (registered != null) {
            pendingInheritedProfessions.remove(uuid);
            applyInheritedProfession(registered, child, inherited);
            return;
        }
        register(child);
    }

    private Profession parentProfession(String parentUuid) {
        if (parentUuid == null) {
            return null;
        }
        BVillager registered = online.get(parentUuid);
        return registered == null ? null : registered.profession();
    }

    private double inheritanceRate(Profession profession) {
        if (profession == null) {
            return 0.0;
        }
        ProfessionData data = BV.professions().data(profession);
        return data != null && data.enabled() ? data.inheritRate() : 0.0;
    }

    static Profession selectInherited(Profession first, double firstRate,
                                      Profession second, double secondRate,
                                      double parentRoll, double inheritanceRoll) {
        if (first == null && second == null) {
            return Profession.CIVILIAN;
        }
        Profession candidate;
        double rate;
        if (second == null || first != null && parentRoll < 0.5) {
            candidate = first;
            rate = firstRate;
        } else {
            candidate = second;
            rate = secondRate;
        }
        if (candidate == Profession.KING
                || inheritanceRoll >= Math.clamp(rate, 0.0, 1.0)) {
            return Profession.CIVILIAN;
        }
        return candidate;
    }

    private void prepareRegistration(Villager entity, String uuid, long token, RegistrationSeed seed) {
        try {
            Optional<VillagerData> stored = BV.storage().villagers().find(uuid);
            VillagerData data;
            Profession profession;
            boolean existing = stored.isPresent();
            if (existing) {
                data = stored.orElseThrow();
                profession = Profession.parse(data.profession());
            } else {
                int villageId = BV.villages().findOrCreate(seed.worldName(), seed.blockX(), seed.blockY(),
                        seed.blockZ(), seed.biome());
                boolean kingPresent = BV.villages().hasKing(villageId);
                int population = BV.villages().get(villageId)
                        .map(dev.bettervillagers.village.Village::population).orElse(0);
                profession = BV.professions().allocate(kingPresent, population, kingThreshold);
                ProfessionData professionData = BV.professions().data(profession);
                long now = System.currentTimeMillis();
                data = new VillagerData(uuid, VillagerNameGenerator.ruleBasedName(), profession.id(),
                        professionData.stats().health(), professionData.stats().attack(),
                        professionData.stats().defense().name(), seed.worldName(), seed.x(), seed.y(), seed.z(),
                        villageId, true, "[]", now, now);
            }
            if (!registrationCurrent(uuid, token)) {
                return;
            }
            RegistrationPlan plan = new RegistrationPlan(data, profession, existing, seed.inProtectedRegion());
            BV.scheduler().runForEntity(entity,
                    () -> completeRegistration(entity, uuid, token, plan),
                    () -> registrationTokens.remove(uuid, token));
        } catch (Throwable t) {
            registrationTokens.remove(uuid, token);
            if (!shuttingDown) {
                BV.plugin().getLogger().warning(BV.messages().raw("log.tactical-tick-error")
                        .replace("{uuid}", uuid).replace("{error}", "register: " + t));
            }
        }
    }

    private void completeRegistration(Villager entity, String uuid, long token, RegistrationPlan plan) {
        if (!registrationCurrent(uuid, token) || !entity.isValid() || entity.isDead()) {
            registrationTokens.remove(uuid, token);
            pendingInheritedProfessions.remove(uuid);
            return;
        }
        Profession profession = plan.profession();
        ProfessionData professionData = BV.professions().data(profession);
        VillagerData data = plan.data();
        if (!plan.existing()) {
            Profession inherited = pendingInheritedProfessions.remove(uuid);
            if (inherited != null) {
                profession = inherited;
                professionData = BV.professions().data(profession);
                data = withProfession(data, profession, professionData);
            }
        } else {
            pendingInheritedProfessions.remove(uuid);
        }
        boolean kingClaimed = false;
        if (!plan.existing()) {
            kingClaimed = BV.villages().applyPendingVillagerAddition(
                    data.villageId(), uuid, profession == Profession.KING);
            if (profession == Profession.KING && !kingClaimed) {
                int population = BV.villages().get(data.villageId())
                        .map(dev.bettervillagers.village.Village::population).orElse(0);
                profession = BV.professions().allocate(true, population, kingThreshold);
                professionData = BV.professions().data(profession);
                data = withProfession(data, profession, professionData);
            }
        }

        boolean correctedKingConflict = false;
        if (plan.existing() && profession == Profession.KING
                && !BV.villages().isKing(data.villageId(), uuid)) {
            if (!BV.villages().setKing(data.villageId(), uuid)) {
                int population = BV.villages().get(data.villageId())
                        .map(dev.bettervillagers.village.Village::population).orElse(0);
                profession = BV.professions().allocate(true, population, kingThreshold);
                professionData = BV.professions().data(profession);
                data = withProfession(data, profession, professionData);
                correctedKingConflict = true;
            }
        }

        BVillager bv = new BVillager(data, entity, profession, professionData);
        bv.protectedRegionSuspended(plan.inProtectedRegion());
        if (online.putIfAbsent(uuid, bv) != null) {
            if (!plan.existing()) {
                BV.villages().rollbackPendingVillagerAddition(data.villageId(), uuid, kingClaimed);
            }
            registrationTokens.remove(uuid, token);
            return;
        }
        if (plan.existing()) {
            BV.ai().memory().load(uuid, data.aiMemoryJson());
            if (correctedKingConflict) {
                VillagerData corrected = bv.toData(data.aiMemoryJson());
                persistence.execute(uuid, () -> persistSnapshot(corrected));
            }
        } else {
            VillagerData initialSnapshot = bv.toData("[]");
            boolean finalKingClaimed = kingClaimed;
            pendingRegistrationClaims.put(uuid, finalKingClaimed);
            persistence.execute(uuid, () -> persistNewRegistration(initialSnapshot, finalKingClaimed));
            requestGeneratedName(bv, profession);
        }
        if (!plan.inProtectedRegion()) {
            applyEntityRegistration(entity, bv, profession, professionData, !plan.existing());
        }
        if (BV.socialEngine() != null) {
            BV.socialEngine().settlePending(bv);
        }
        registrationTokens.remove(uuid, token);
    }

    private void persistNewRegistration(VillagerData data, boolean kingClaimed) {
        try {
            boolean persistedKing = BV.storage().villagers().insertNewAndAttach(data, kingClaimed);
            pendingRegistrationClaims.remove(data.uuid());
            if (kingClaimed && !persistedKing) {
                BV.villages().clearKingIfOwned(data.villageId(), data.uuid());
                resolveLostKingClaim(data);
            }
            dev.bettervillagers.storage.AtomicFileWriter.deleteFallback(BV.plugin(), data.uuid());
        } catch (RuntimeException e) {
            logSaveFailure(data, e, true, kingClaimed);
        }
    }

    private void resolveLostKingClaim(VillagerData attempted) {
        BVillager current = online.get(attempted.uuid());
        if (current == null || current.profession() != Profession.KING) {
            return;
        }
        int population = BV.villages().get(attempted.villageId())
                .map(dev.bettervillagers.village.Village::population).orElse(0);
        Profession replacement = BV.professions().allocate(true, population, kingThreshold);
        ProfessionData replacementData = BV.professions().data(replacement);
        current.profession(replacement, replacementData, false);
        VillagerData corrected = current.toData(attempted.aiMemoryJson());
        BV.storage().villagers().upsert(corrected);
        BV.storage().villages().clearKingIfOwned(attempted.villageId(), attempted.uuid());
        Villager entity = current.entity();
        if (entity != null) {
            BV.scheduler().runForEntity(entity,
                    () -> applyEntityRegistration(entity, current, replacement, replacementData, false), null);
        }
    }

    private void requestGeneratedName(BVillager bv, Profession profession) {
        VillagerNameGenerator.generateAsync(profession, aiName -> {
            BVillager current = online.get(bv.uuid());
            if (current != bv || shuttingDown) {
                return;
            }
            bv.name(aiName);
            Villager entity = bv.entity();
            if (entity != null) {
                BV.scheduler().runForEntity(entity, () -> updateDisplayName(bv), null);
            }
        });
    }

    private void applyEntityRegistration(Villager entity, BVillager bv, Profession profession,
                                         ProfessionData professionData, boolean newVillager) {
        bv.updateEntitySnapshot(entity);
        entity.setProfession(dev.bettervillagers.profession.VanillaProfessionMapper.toVanilla(profession));
        EquipmentDurability.applyCurrent(entity, professionData);
        updateDisplayName(bv);
        if (BV.trade() != null && !professionData.trades().isEmpty()) {
            try {
                if (newVillager || entity.getRecipes().isEmpty()) {
                    entity.setRecipes(BV.trade().generateOffers(bv));
                }
            } catch (Throwable t) {
                BV.plugin().getLogger().warning(BV.messages().raw("log.tactical-tick-error")
                        .replace("{uuid}", bv.uuid()).replace("{error}", "setRecipes: " + t));
            }
        }
    }

    private void applyInheritedProfession(BVillager bv, Villager entity, Profession profession) {
        Profession previous = bv.profession();
        if (previous == Profession.KING && profession != Profession.KING) {
            BV.villages().setKing(bv.villageId(), null);
        }
        ProfessionData data = BV.professions().data(profession);
        bv.profession(profession, data, false);
        EquipmentDurability.reset(entity, data);
        applyEntityRegistration(entity, bv, profession, data, false);
        VillagerData snapshot = snapshot(bv);
        persistence.execute(bv.uuid(), () -> persistSnapshot(snapshot));
    }

    private boolean registrationCurrent(String uuid, long token) {
        return !shuttingDown && Long.valueOf(token).equals(registrationTokens.get(uuid));
    }

    private static VillagerData withProfession(VillagerData data, Profession profession, ProfessionData professionData) {
        return new VillagerData(data.uuid(), data.name(), profession.id(), professionData.stats().health(),
                professionData.stats().attack(), professionData.stats().defense().name(), data.locationWorld(),
                data.locationX(), data.locationY(), data.locationZ(), data.villageId(), data.aiEnabled(),
                data.aiMemoryJson(), data.createdAt(), data.updatedAt());
    }

    /**
     * 更新村民头顶显示名（问题1：名字-职业，彩色）。
     * 格式：&e[名字]&r &7[职业中文名]
     */
    public void updateDisplayName(BVillager bv) {
        Villager entity = bv.entity();
        if (entity == null) {
            return;
        }
        String profDisplay = BV.messages().raw("professions." + bv.profession().id());
        // 显示名格式经 i18n 模板，避免硬编码颜色码（用户规则：禁止硬编码可见文本）
        String displayRaw = BV.messages().raw("display.villager-name-format")
                .replace("{name}", MessageService.escapeUntrusted(bv.name())).replace("{profession}", profDisplay);
        Component display = MessageService.deserialize(displayRaw);
        entity.customName(display);
        entity.setCustomNameVisible(true);
    }

    /** Compatibility alias for an unload that preserves the persistent record. */
    public void unregister(String uuid) {
        unload(uuid);
    }

    public void unload(String uuid) {
        registrationTokens.remove(uuid);
        pendingInheritedProfessions.remove(uuid);
        BVillager bv = online.remove(uuid);
        if (bv == null) {
            return;
        }
        VillagerData snapshot = snapshot(bv);
        persistence.execute(uuid, () -> persistSnapshot(snapshot));
        clearRuntimeState(uuid);
    }

    public void removePermanently(String uuid) {
        registrationTokens.remove(uuid);
        pendingInheritedProfessions.remove(uuid);
        BVillager bv = online.remove(uuid);
        int knownVillageId = bv == null ? -1 : bv.villageId();
        pendingRegistrationClaims.remove(uuid);
        clearRuntimeState(uuid);
        persistence.execute(uuid, () -> {
            int villageId = knownVillageId;
            if (villageId <= 0) {
                villageId = BV.storage().villagers().find(uuid).map(VillagerData::villageId).orElse(-1);
            }
            try {
                BV.storage().villagers().deletePermanently(uuid, villageId);
                dev.bettervillagers.storage.AtomicFileWriter.deleteFallback(BV.plugin(), uuid);
                if (villageId > 0) {
                    BV.villages().applyPersistedVillagerRemoval(villageId, uuid);
                }
            } catch (RuntimeException e) {
                BV.plugin().getLogger().warning(BV.messages().raw("errors.villager-delete")
                        .replace("{uuid}", uuid).replace("{error}", String.valueOf(e.getMessage())));
            }
        });
    }

    private void clearRuntimeState(String uuid) {
        BV.ai().memory().remove(uuid);
        BV.ai().evictLock(uuid);
        if (BV.behavior() != null) {
            BV.behavior().clearVillagerState(uuid);
        }
        if (BV.socialEngine() != null) {
            BV.socialEngine().release(uuid);
        }
    }

    public Optional<BVillager> get(String uuid) {
        return Optional.ofNullable(online.get(uuid));
    }

    public List<BVillager> all() {
        return new ArrayList<>(online.values());
    }

    public int count() {
        return online.size();
    }

    /** 启动周期性 AI tick 与定时保存。 */
    public synchronized void startTicking(int tacticalIntervalSec, int strategicIntervalSec, long autoSaveSec) {
        stopTicking();
        long tacticalTicks = Math.max(20L, tacticalIntervalSec * 20L);
        long strategicTicks = Math.max(20L, strategicIntervalSec * 20L);
        long saveTicks = Math.max(200L, autoSaveSec * 20L);

        tacticalHandle = BV.scheduler().runGlobalTimer(this::tickTactical, tacticalTicks, tacticalTicks);
        strategicHandle = BV.scheduler().runGlobalTimer(this::tickStrategic, strategicTicks, strategicTicks);
        saveHandle = BV.scheduler().runGlobalTimer(this::saveAll, saveTicks, saveTicks);
        cleanupHandle = BV.scheduler().runGlobalTimer(this::cleanupSweep, 600L, 600L);
        // 问题2修复：快速战斗 tick（10 tick = 0.5s），确保战斗职业持续追击
        combatHandle = BV.scheduler().runGlobalTimer(this::tickCombat, 10L, 10L);
        // 规范 3.3：职业专属任务 tick（100 tick = 5s，经实体区域线程执行，不阻塞主线程）
        workHandle = BV.scheduler().runGlobalTimer(this::tickWork, 100L, 100L);
        // 规范 3.3：跨职业社交检测 tick（40 tick = 2s）
        socialHandle = BV.scheduler().runGlobalTimer(this::tickSocial, 40L, 40L);
    }

    public void reconfigure(int newKingThreshold, int persistenceThreads,
                            int tacticalIntervalSec, int strategicIntervalSec, long autoSaveSec) {
        kingThreshold = Math.max(1, newKingThreshold);
        persistence.reconfigure(persistenceThreads);
        startTicking(tacticalIntervalSec, strategicIntervalSec, autoSaveSec);
    }

    private synchronized void stopTicking() {
        ScheduledHandle[] handles = {tacticalHandle, strategicHandle, saveHandle, cleanupHandle,
                combatHandle, workHandle, socialHandle};
        for (ScheduledHandle handle : handles) {
            if (handle != null) {
                handle.cancel();
            }
        }
        tacticalHandle = null;
        strategicHandle = null;
        saveHandle = null;
        cleanupHandle = null;
        combatHandle = null;
        workHandle = null;
        socialHandle = null;
    }

    /** 清理死亡/无效村民的内存占用（规范 4.2）。 */
    private void cleanupSweep() {
        for (BVillager bv : online.values()) {
            Villager entity = bv.entity();
            if (entity == null) {
                unload(bv.uuid());
                continue;
            }
            BV.scheduler().runForEntity(entity, () -> {
                if (!bv.isAlive()) {
                    if (entity.isDead()) {
                        removePermanently(bv.uuid());
                    } else {
                        unload(bv.uuid());
                    }
                }
            }, () -> unload(bv.uuid()));
        }
    }

    /** 战术层 tick：遍历在线村民，委托行为引擎（受最大并发村民限制）。 */
    private void tickTactical() {
        if (BV.behavior() == null || !BV.config().feature("ai-behavior")) {
            return;
        }
        int limit = BV.config().maxActiveAiVillagers();
        for (BVillager bv : roundRobin(limit, tacticalCursor)) {
            Villager entity = bv.entity();
            if (entity == null) {
                continue;
            }
            BV.scheduler().runForEntity(entity, () -> {
                bv.updateEntitySnapshot(entity);
                if (!bv.isAlive()) {
                    return;
                }
                if (BV.regions() != null) {
                    boolean inRegion = BV.regions().isProtected(entity.getLocation());
                    boolean wasSuspended = bv.protectedRegionSuspended();
                    bv.protectedRegionSuspended(inRegion);
                    if (inRegion) {
                        return;
                    }
                    if (wasSuspended) {
                        applyEntityRegistration(entity, bv, bv.profession(), bv.professionData(), false);
                    }
                }
                if (!bv.aiEnabled()) {
                    return;
                }
                try {
                    BV.behavior().tickTactical(bv);
                } catch (Throwable t) {
                    BV.plugin().getLogger().warning(
                            BV.messages().raw("log.tactical-tick-error")
                                    .replace("{uuid}", bv.uuid()).replace("{error}", String.valueOf(t)));
                }
            }, null);
        }
    }

    /**
     * 快速战斗 tick（问题2修复：每 0.5s 执行一次）。
     * <p>
     * 战斗职业村民持续追击并攻击附近敌对生物，克服原版恐慌行为带来的 fleeing。
     * 此 tick 不受 maxActiveAiVillagers 限制（战斗是反射层，无 AI 成本）。
     */
    private void tickCombat() {
        if (BV.behavior() == null || !BV.config().feature("ai-behavior")) {
            return;
        }
        for (BVillager bv : online.values()) {
            Villager entity = bv.entity();
            if (entity == null) {
                continue;
            }
            BV.scheduler().runForEntity(entity, () -> {
                if (!bv.isAlive()) {
                    return;
                }
                bv.updateEntitySnapshot(entity);
                if (BV.regions() != null) {
                    bv.protectedRegionSuspended(BV.regions().isProtected(entity.getLocation()));
                }
                if (!bv.aiEnabled()) {
                    return;
                }
                try {
                    BV.behavior().tickCombat(bv);
                } catch (Throwable t) {
                    BV.plugin().getLogger().warning(
                            BV.messages().raw("log.tactical-tick-error")
                                    .replace("{uuid}", bv.uuid()).replace("{error}", "combat: " + t));
                }
            }, null);
        }
    }

    /**
     * 职业专属任务 tick（规范 3.3：每 5s 执行一次）。
     * <p>
     * 分发各职业核心职责行为（农民农业生产、军事边境巡逻、矿工采矿等），
     * 经实体区域线程执行，不阻塞主线程；受 maxActiveAiVillagers 限制控制扫描成本。
     */
    private void tickWork() {
        if (BV.taskEngine() == null) {
            return;
        }
        if (!BV.config().feature("profession-tasks")) {
            return;
        }
        int limit = BV.config().maxActiveAiVillagers();
        for (BVillager bv : roundRobin(limit, workCursor)) {
            if (!bv.aiEnabled()) {
                continue;
            }
            Villager ent = bv.entity();
            if (ent == null) {
                continue;
            }
            BV.scheduler().runForEntity(ent, () -> {
                try {
                    if (!bv.isAlive()) {
                        return;
                    }
                    bv.updateEntitySnapshot(ent);
                    if (suspendInProtectedRegion(bv, ent)) {
                        return;
                    }
                    BV.taskEngine().tickWork(bv);
                } catch (Throwable t) {
                    BV.plugin().getLogger().warning(
                            BV.messages().raw("log.tactical-tick-error")
                                    .replace("{uuid}", bv.uuid()).replace("{error}", "work: " + t));
                }
            }, null);
        }
    }

    /**
     * 跨职业社交检测 tick（规范 3.3：每 2s 执行一次）。
     * <p>
     * 检测不同职业村民相遇并触发驻足攀谈，经实体区域线程执行，不阻塞主线程。
     */
    private void tickSocial() {
        if (BV.socialEngine() == null) {
            return;
        }
        if (!BV.config().feature("social-interaction")) {
            return;
        }
        long now = System.currentTimeMillis();
        for (BVillager bv : online.values()) {
            if (!bv.aiEnabled() || !BV.socialEngine().shouldSchedule(bv, now)) {
                continue;
            }
            Villager ent = bv.entity();
            if (ent == null) {
                continue;
            }
            BV.scheduler().runForEntity(ent, () -> {
                try {
                    if (!bv.isAlive()) {
                        return;
                    }
                    bv.updateEntitySnapshot(ent);
                    if (suspendInProtectedRegion(bv, ent)) {
                        return;
                    }
                    BV.socialEngine().tickSocial(bv);
                } catch (Throwable t) {
                    BV.plugin().getLogger().warning(
                            BV.messages().raw("log.tactical-tick-error")
                                    .replace("{uuid}", bv.uuid()).replace("{error}", "social: " + t));
                }
            }, null);
        }
    }

    /** 战略层 tick：仅国王/队长级别。 */
    private void tickStrategic() {
        if (BV.behavior() == null || !BV.config().feature("ai-behavior")) {
            return;
        }
        for (BVillager bv : online.values()) {
            if (bv.profession() != Profession.KING) {
                continue;
            }
            Villager entity = bv.entity();
            if (entity == null) {
                continue;
            }
            BV.scheduler().runForEntity(entity, () -> {
                if (!bv.isAlive() || suspendInProtectedRegion(bv, entity) || !bv.aiEnabled()) {
                    return;
                }
                try {
                    BV.behavior().tickStrategic(bv);
                } catch (Throwable t) {
                    BV.plugin().getLogger().warning(
                            BV.messages().raw("log.strategic-tick-error")
                                    .replace("{uuid}", bv.uuid()).replace("{error}", String.valueOf(t)));
                }
            }, null);
        }
    }

    /** 切换某村民 AI 开关，返回切换后的状态。 */
    public boolean toggleAI(String uuid) {
        BVillager bv = online.get(uuid);
        if (bv != null) {
            bv.aiEnabled(!bv.configuredAiEnabled());
            VillagerData snapshot = snapshot(bv);
            persistence.execute(uuid, () -> persistSnapshot(snapshot));
            return bv.configuredAiEnabled();
        }
        return false;
    }

    private static boolean suspendInProtectedRegion(BVillager bv, Villager entity) {
        if (BV.regions() == null) {
            return false;
        }
        boolean protectedRegion = BV.regions().isProtected(entity.getLocation());
        bv.protectedRegionSuspended(protectedRegion);
        return protectedRegion;
    }

    /** 重置某村民 AI 记忆。 */
    public void resetMemory(String uuid) {
        BV.ai().memory().remove(uuid);
    }

    /** 管理员强制指派职业（锁定，规范 2.2 第 6 点）。 */
    public boolean setProfession(String uuid, Profession prof) {
        BVillager bv = online.get(uuid);
        if (bv == null) {
            return false;
        }
        ProfessionData pd = BV.professions().data(prof);
        Profession previous = bv.profession();
        if (prof == Profession.KING && previous != Profession.KING
                && !BV.villages().setKing(bv.villageId(), uuid)) {
            return false;
        }
        if (previous == Profession.KING && prof != Profession.KING
                && !BV.villages().setKing(bv.villageId(), null)) {
            return false;
        }
        bv.profession(prof, pd, true);
        Villager entity = bv.entity();
        if (entity != null) {
            BV.scheduler().runForEntity(entity, () -> {
                entity.setProfession(dev.bettervillagers.profession.VanillaProfessionMapper.toVanilla(prof));
                EquipmentDurability.reset(entity, pd);
                updateDisplayName(bv);
                if (BV.trade() != null && !pd.trades().isEmpty()) {
                    try {
                        entity.setRecipes(BV.trade().generateOffers(bv));
                    } catch (Throwable t) {
                        BV.plugin().getLogger().warning(
                                BV.messages().raw("log.tactical-tick-error")
                                        .replace("{uuid}", uuid).replace("{error}", "setRecipes: " + t));
                    }
                }
            }, null);
        }
        VillagerData snapshot = snapshot(bv);
        persistence.execute(uuid, () -> persistSnapshot(snapshot));
        return true;
    }

    /* 非管理员转职（受冷却约束，规范 2.2：间隔 ≥ 1 游戏日）。 */
    /** 定时保存全部（规范 4.5 WAL）。 */
    public void saveAll() {
        if (shuttingDown) {
            return;
        }
        for (BVillager bv : online.values()) {
            VillagerData snapshot = snapshot(bv);
            persistence.execute(bv.uuid(), () -> persistSnapshot(snapshot));
        }
    }

    private VillagerData snapshot(BVillager bv) {
        return bv.toData(BV.ai().memory().export(bv.uuid()));
    }

    private void persistSnapshot(VillagerData data) {
        try {
            Boolean claimKing = pendingRegistrationClaims.get(data.uuid());
            if (claimKing == null) {
                BV.storage().villagers().upsert(data);
                if (!Profession.KING.id().equalsIgnoreCase(data.profession())) {
                    BV.storage().villages().clearKingIfOwned(data.villageId(), data.uuid());
                }
            } else {
                boolean persistedKing = BV.storage().villagers().insertNewAndAttach(data, claimKing);
                pendingRegistrationClaims.remove(data.uuid(), claimKing);
                if (claimKing && !persistedKing) {
                    BV.villages().clearKingIfOwned(data.villageId(), data.uuid());
                    resolveLostKingClaim(data);
                }
            }
            dev.bettervillagers.storage.AtomicFileWriter.deleteFallback(BV.plugin(), data.uuid());
        } catch (RuntimeException e) {
            Boolean claimKing = pendingRegistrationClaims.get(data.uuid());
            logSaveFailure(data, e, claimKing != null, Boolean.TRUE.equals(claimKing));
        }
    }

    private void logSaveFailure(VillagerData data, RuntimeException e,
                                boolean attachToVillage, boolean claimKing) {
        BV.plugin().getLogger().warning(BV.messages().raw("errors.villager-save")
                .replace("{uuid}", data.uuid()).replace("{error}", String.valueOf(e.getMessage())));
        dev.bettervillagers.storage.AtomicFileWriter.fallbackDump(
                BV.plugin(), data, attachToVillage, claimKing);
    }

    private List<BVillager> roundRobin(int limit, AtomicInteger cursor) {
        List<BVillager> snapshot = new ArrayList<>(online.values());
        if (snapshot.isEmpty() || limit <= 0) {
            return List.of();
        }
        int count = Math.min(limit, snapshot.size());
        int start = Math.floorMod(cursor.getAndAdd(count), snapshot.size());
        List<BVillager> selected = new ArrayList<>(count);
        for (int offset = 0; offset < snapshot.size() && selected.size() < count; offset++) {
            BVillager bv = snapshot.get((start + offset) % snapshot.size());
            if (bv.entity() != null) {
                selected.add(bv);
            }
        }
        return selected;
    }

    public void shutdown() {
        shuttingDown = true;
        registrationTokens.clear();
        pendingInheritedProfessions.clear();
        stopTicking();
        for (BVillager bv : online.values()) {
            VillagerData snapshot = snapshot(bv);
            persistence.execute(bv.uuid(), () -> persistSnapshot(snapshot));
        }
        persistence.shutdownAndAwait(30L, TimeUnit.SECONDS);
    }

    private record RegistrationSeed(String worldName, double x, double y, double z,
                                    int blockX, int blockY, int blockZ, String biome,
                                    boolean inProtectedRegion) {
    }

    private record RegistrationPlan(VillagerData data, Profession profession, boolean existing,
                                    boolean inProtectedRegion) {
    }
}
