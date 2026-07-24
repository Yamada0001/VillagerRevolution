package dev.bettervillagers.villager;

import dev.bettervillagers.BV;
import dev.bettervillagers.i18n.MessageService;
import dev.bettervillagers.profession.EquipmentApplier;
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
    private final Map<String, Long> createdAt = new ConcurrentHashMap<>();
    private final int kingThreshold;
    private ScheduledHandle tacticalHandle;
    private ScheduledHandle strategicHandle;
    private ScheduledHandle saveHandle;
    private ScheduledHandle cleanupHandle;
    private ScheduledHandle combatHandle;
    private ScheduledHandle workHandle;
    private ScheduledHandle socialHandle;

    public VillagerManager(int kingThreshold) {
        this.kingThreshold = kingThreshold;
    }

    /** 注册新出现/加载的村民（区块加载或生成时调用）。 */
    public void register(Villager entity) {
        String uuid = entity.getUniqueId().toString();
        if (online.containsKey(uuid)) {
            return;
        }
        // 以下值须在主线程/区域线程（本方法调用线程）提取为纯值，
        // 避免在后续 runAsync 中访问实体/世界 API（规范：异步禁止访问游戏世界/实体）
        org.bukkit.Location spawnLoc = entity.getLocation();
        // 生电保护区内不激活 AI（规范 5.2）
        boolean inRedstone = BV.regions() != null && BV.regions().isProtected(spawnLoc);
        final String worldName = entity.getWorld().getName();
        final double locX = spawnLoc.getX();
        final double locY = spawnLoc.getY();
        final double locZ = spawnLoc.getZ();
        final int blockX = spawnLoc.getBlockX();
        final int blockY = spawnLoc.getBlockY();
        final int blockZ = spawnLoc.getBlockZ();
        // biome 在主线程预先取好（findOrCreate 异步线程使用）
        String biomeRaw = "plains";
        try {
            biomeRaw = spawnLoc.getBlock().getBiome().name().toLowerCase().replace('_', ' ');
        } catch (Throwable ignored) {
            // 某些平台/世界类型可能无法获取生物群系
        }
        final String biome = biomeRaw;

        BV.scheduler().runAsync(() -> {
            Optional<VillagerData> stored = BV.storage().villagers().find(uuid);
            VillagerData data;
            Profession prof;
            ProfessionData pd;
            boolean locked = false;

            if (stored.isPresent()) {
                data = stored.get();
                prof = Profession.parse(data.profession());
                pd = BV.professions().data(prof);
                locked = true; // 已有记录视为既定职业
                BV.ai().memory().load(uuid, data.aiMemoryJson());
            } else {
                // 生成时分配职业（规范 2.2）
                int villageId = BV.villages().findOrCreate(worldName, blockX, blockY, blockZ, biome);
                boolean kingPresent = BV.villages().hasKing(villageId);
                int population = BV.villages().get(villageId)
                        .map(v -> v.population()).orElse(0);
                prof = BV.professions().allocate(kingPresent, population, kingThreshold);
                pd = BV.professions().data(prof);
                long now = System.currentTimeMillis();
                // 问题1：用规则引擎先给一个中世纪名字，AI 异步生成后替换
                String initialName = VillagerNameGenerator.ruleBasedName();
                data = new VillagerData(uuid, initialName, prof.id(),
                        pd.stats().health(), pd.stats().attack(), pd.stats().defense().name(),
                        worldName, locX, locY, locZ,
                        villageId, !inRedstone, "[]", now, now);
                createdAt.put(uuid, now);
                if (prof == Profession.KING) {
                    BV.villages().setKing(villageId, uuid);
                }
                BV.villages().updatePopulation(villageId, population + 1);

                // 问题1：异步请求 AI 生成更好的中世纪风格名字
                final String finalUuid = uuid;
                VillagerNameGenerator.generateAsync(prof, aiName -> {
                    BVillager bv = online.get(finalUuid);
                    if (bv != null) {
                        bv.name(aiName);
                        // 显示名更新涉及实体 API，须切回实体所属区域线程
                        Villager ent = bv.entity();
                        if (ent != null) {
                            BV.scheduler().runForEntity(ent, () -> updateDisplayName(bv), null);
                        }
                    }
                });
            }

            BVillager bv = new BVillager(data, entity, prof, pd);
            bv.aiEnabled(!inRedstone && data.aiEnabled());
            online.put(uuid, bv);
            final boolean isNewVillager = !locked;

            // 修复国王检测：已存在的国王职业村民重新加载时也同步到村庄
            if (prof == Profession.KING && !BV.villages().hasKing(data.villageId())) {
                BV.villages().setKing(data.villageId(), uuid);
            }
            // 切回区域线程应用装备与命名
            BV.scheduler().runForEntity(entity, () -> {
                // 设置原版职业映射（让升级机制正常工作，外观由 EquipmentApplier 覆盖）
                entity.setProfession(dev.bettervillagers.profession.VanillaProfessionMapper.toVanilla(prof));
                EquipmentApplier.apply(entity, pd);
                updateDisplayName(bv);
                // 修复问题3：始终确保村民有交易列表（新村民或已有村民 recipes 为空时都设置）
                if (BV.trade() != null && !pd.trades().isEmpty()) {
                    try {
                        // 已注册村民若 recipes 为空也补充（避免老村民/已加载村民无交易）
                        if (isNewVillager || entity.getRecipes() == null || entity.getRecipes().isEmpty()) {
                            entity.setRecipes(BV.trade().generateOffers(bv));
                        }
                    } catch (Throwable t) {
                        BV.plugin().getLogger().warning(
                                BV.messages().raw("log.tactical-tick-error")
                                        .replace("{uuid}", uuid).replace("{error}", "setRecipes: " + t));
                    }
                }
            }, null);
        });
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
                .replace("{name}", bv.name()).replace("{profession}", profDisplay);
        Component display = MessageService.deserialize(displayRaw);
        entity.customName(display);
        entity.setCustomNameVisible(true);
    }

    /** 注销村民（区块卸载/死亡/退出时调用），异步保存。 */
    public void unregister(String uuid) {
        BVillager bv = online.remove(uuid);
        if (bv == null) {
            return;
        }
        createdAt.remove(uuid); // 清理注册时间戳，避免 Map 单调增长（规范 4.x 内存管理）
        saveOne(bv);
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
    public void startTicking(int tacticalIntervalSec, int strategicIntervalSec, long autoSaveSec) {
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

    /** 清理死亡/无效村民的内存占用（规范 4.2）。 */
    private void cleanupSweep() {
        for (BVillager bv : online.values()) {
            Villager entity = bv.entity();
            if (entity == null) {
                unregister(bv.uuid());
                continue;
            }
            BV.scheduler().runForEntity(entity, () -> {
                if (!bv.isAlive()) {
                    unregister(bv.uuid());
                }
            }, () -> unregister(bv.uuid()));
        }
    }

    /** 战术层 tick：遍历在线村民，委托行为引擎（受最大并发村民限制）。 */
    private void tickTactical() {
        if (BV.behavior() == null) {
            return;
        }
        int limit = BV.config().maxActiveAiVillagers();
        int[] scheduled = {0};
        for (BVillager bv : online.values()) {
            if (scheduled[0] >= limit) {
                break;
            }
            Villager entity = bv.entity();
            if (entity == null) {
                continue;
            }
            scheduled[0]++;
            BV.scheduler().runForEntity(entity, () -> {
                if (!bv.isAlive()) {
                    return;
                }
                if (BV.regions() != null) {
                    boolean inRegion = BV.regions().isProtected(entity.getLocation());
                    if (inRegion && bv.aiEnabled()) {
                        bv.aiEnabled(false);
                        return;
                    } else if (!inRegion && !bv.aiEnabled() && !bv.professionLocked()) {
                        bv.aiEnabled(true);
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
        if (BV.behavior() == null) {
            return;
        }
        for (BVillager bv : online.values()) {
            Villager entity = bv.entity();
            if (entity == null) {
                continue;
            }
            BV.scheduler().runForEntity(entity, () -> {
                if (!bv.isAlive() || !bv.aiEnabled()) {
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
        int n = 0;
        for (BVillager bv : online.values()) {
            if (!bv.isAlive() || !bv.aiEnabled()) {
                continue;
            }
            if (n++ >= limit) {
                break;
            }
            Villager ent = bv.entity();
            if (ent == null) {
                continue;
            }
            BV.scheduler().runForEntity(ent, () -> {
                try {
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
        for (BVillager bv : online.values()) {
            if (!bv.isAlive() || !bv.aiEnabled()) {
                continue;
            }
            Villager ent = bv.entity();
            if (ent == null) {
                continue;
            }
            BV.scheduler().runForEntity(ent, () -> {
                try {
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
        if (BV.behavior() == null) {
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
                if (!bv.aiEnabled() || !bv.isAlive()) {
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
            bv.aiEnabled(!bv.aiEnabled());
            return bv.aiEnabled();
        }
        return false;
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
        bv.profession(prof, pd, true);
        Villager entity = bv.entity();
        if (entity != null) {
            BV.scheduler().runForEntity(entity, () -> {
                entity.setProfession(dev.bettervillagers.profession.VanillaProfessionMapper.toVanilla(prof));
                EquipmentApplier.apply(entity, pd);
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
        if (prof == Profession.KING) {
            BV.villages().setKing(bv.villageId(), uuid);
        }
        return true;
    }

    /** 非管理员转职（受冷却约束，规范 2.2：间隔 ≥ 1 游戏日）。 */
    public boolean setProfessionNatural(String uuid, Profession prof, long nowTick) {
        BVillager bv = online.get(uuid);
        if (bv == null || bv.professionLocked()) {
            return false;
        }
        if (!bv.canChangeNonAdmin(nowTick)) {
            return false;
        }
        bv.lastProfessionChangeTick(nowTick);
        ProfessionData pd = BV.professions().data(prof);
        bv.profession(prof, pd, false);
        Villager entity = bv.entity();
        if (entity != null) {
            BV.scheduler().runForEntity(entity, () -> {
                EquipmentApplier.apply(entity, pd);
                updateDisplayName(bv);
            }, null);
        }
        return true;
    }

    /** 定时保存全部（规范 4.5 WAL）。 */
    public void saveAll() {
        BV.scheduler().runAsync(() -> {
            List<VillagerData> batch = new ArrayList<>();
            for (BVillager bv : online.values()) {
                String mem = BV.ai().memory().export(bv.uuid());
                batch.add(bv.toData(mem, createdAt.getOrDefault(bv.uuid(), System.currentTimeMillis())));
            }
            if (!batch.isEmpty()) {
                try {
                    BV.storage().villagers().upsertAll(batch);
                } catch (RuntimeException e) {
                    BV.plugin().getLogger().severe(
                            BV.messages().raw("log.batch-save-fail").replace("{error}", e.getMessage()));
                    for (VillagerData v : batch) {
                        dev.bettervillagers.storage.AtomicFileWriter.fallbackDump(
                                BV.plugin(), v.uuid(), v.aiMemoryJson());
                    }
                }
            }
        });
    }

    private void saveOne(BVillager bv) {
        BV.scheduler().runAsync(() -> {
            String mem = BV.ai().memory().export(bv.uuid());
            VillagerData data = bv.toData(mem, createdAt.getOrDefault(bv.uuid(), System.currentTimeMillis()));
            try {
                BV.storage().villagers().upsert(data);
            } catch (RuntimeException e) {
                BV.plugin().getLogger().warning(
                        BV.messages().raw("errors.villager-save")
                                .replace("{uuid}", data.uuid()).replace("{error}", e.getMessage()));
                dev.bettervillagers.storage.AtomicFileWriter.fallbackDump(
                        BV.plugin(), data.uuid(), data.aiMemoryJson());
            }
        });
    }

    public void shutdown() {
        if (tacticalHandle != null) tacticalHandle.cancel();
        if (strategicHandle != null) strategicHandle.cancel();
        if (saveHandle != null) saveHandle.cancel();
        if (cleanupHandle != null) cleanupHandle.cancel();
        if (combatHandle != null) combatHandle.cancel();
        if (workHandle != null) workHandle.cancel();
        if (socialHandle != null) socialHandle.cancel();
        saveAllSync();
    }

    private void saveAllSync() {
        List<VillagerData> batch = new ArrayList<>();
        for (BVillager bv : online.values()) {
            String mem = BV.ai().memory().export(bv.uuid());
            batch.add(bv.toData(mem, createdAt.getOrDefault(bv.uuid(), System.currentTimeMillis())));
        }
        if (!batch.isEmpty()) {
            try {
                BV.storage().villagers().upsertAll(batch);
            } catch (RuntimeException e) {
                BV.plugin().getLogger().severe(
                        BV.messages().raw("log.batch-save-fail").replace("{error}", e.getMessage()));
                for (VillagerData v : batch) {
                    dev.bettervillagers.storage.AtomicFileWriter.fallbackDump(
                            BV.plugin(), v.uuid(), v.aiMemoryJson());
                }
            }
        }
    }

    private String defaultName(Profession prof) {
        return BV.messages().raw("professions." + prof.id());
    }
}
