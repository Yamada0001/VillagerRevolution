package dev.bettervillagers.building;

import dev.bettervillagers.BV;
import dev.bettervillagers.behavior.MovementHelper;
import dev.bettervillagers.behavior.VillagerState;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.scheduler.ScheduledHandle;
import dev.bettervillagers.villager.BVillager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分阶段施工任务：仅非战斗村民执行，禁止瞬间生成建筑。
 * <p>
 * 每 tick 批量处理少量步骤；可在阶段切换时再次调用 AI 决定是否继续/HOLD。
 */
final class ConstructionJob {

    private static final int STEPS_PER_TICK = 1;
    /** 单个工地最大工人数（原硬编码 8，规范：魔法值提取为常量）。 */
    private static final int MAX_WORKERS = 8;
    /** 工人到达判定距离平方（3 格）。 */
    private static final double ARRIVE_DIST_SQ = 9.0;
    /** 工人引导移动速度（与 ReflexEngine 战斗速度统一）。 */
    private static final double WORK_SPEED = 0.4;

    private final int villageId;
    private final String jobId = java.util.UUID.randomUUID().toString();
    private final String type;
    private final String worldName;
    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final Location regionLocation;
    private final List<ConstructionStep> steps;
    private final AtomicInteger cursor = new AtomicInteger(0);
    private final AtomicInteger pendingSteps = new AtomicInteger(0);
    private final AtomicBoolean rollbackStarted = new AtomicBoolean();
    private final List<String> workerUuids = new CopyOnWriteArrayList<>();
    private final Map<BlockPosition, String> originalBlocks = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> cancellationFuture = new CompletableFuture<>();
    private volatile ScheduledHandle handle;
    private volatile boolean finished;
    private volatile boolean cancelling;
    private volatile boolean finalizing;
    private volatile boolean journalReady;
    private volatile String lastPhase = "";
    private final String displayName;
    private volatile BuildType buildType;
    private volatile BuildCache boundCache;

    ConstructionJob(int villageId, String type, String worldName, Location regionLocation,
                    int centerX, int centerY, int centerZ,
                    List<ConstructionStep> steps, String displayName) {
        this.villageId = villageId;
        this.type = type;
        this.worldName = worldName;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.regionLocation = regionLocation == null ? null
                : new Location(regionLocation.getWorld(), centerX + 0.5, centerY, centerZ + 0.5);
        this.steps = new ArrayList<>(steps);
        this.displayName = displayName;
        this.buildType = BuildType.fromCommand(type);
    }

    /**
     * 绑定建筑缓存：取消时释放占位，完工保留防重复堆叠。
     */
    void bindCache(BuildCache cache, BuildType buildType) {
        this.boundCache = cache;
        if (buildType != null) {
            this.buildType = buildType;
        }
    }

    BuildType buildType() {
        return buildType;
    }

    int villageId() {
        return villageId;
    }

    String jobId() {
        return jobId;
    }

    boolean active() {
        return !finished && !cancelling && !finalizing;
    }

    String teleportData() {
        return centerX + "," + centerY + "," + centerZ + "," + worldName;
    }

    /** 指派非战斗村民为施工队。 */
    private void assignWorkers() {
        workerUuids.clear();
        if (BV.villagers() == null) {
            return;
        }
        for (BVillager bv : BV.villagers().all()) {
            if (!isNonCombatWorker(bv.profession())) {
                continue;
            }
            if (bv.villageId() != villageId && !inVillageRange(bv)) {
                continue;
            }
            if (bv.entity() == null || bv.state() == VillagerState.SOCIALIZING) {
                continue;
            }
            workerUuids.add(bv.uuid());
            bv.state(VillagerState.WORKING);
            if (workerUuids.size() >= MAX_WORKERS) {
                break;
            }
        }
        // 至少保证建筑师优先
        if (workerUuids.isEmpty()) {
            for (BVillager bv : BV.villagers().all()) {
                if (bv.profession() == Profession.BUILDER && bv.entity() != null) {
                    workerUuids.add(bv.uuid());
                    bv.state(VillagerState.WORKING);
                    break;
                }
            }
        }
    }

    private boolean inVillageRange(BVillager bv) {
        BVillager.PositionSnapshot position = bv.lastKnownPosition();
        if (position == null) {
            return false;
        }
        var opt = BV.villages().get(villageId);
        if (opt.isEmpty()) {
            return false;
        }
        var v = opt.get();
        return v.covers(position.world(), (int) Math.floor(position.x()),
                (int) Math.floor(position.y()), (int) Math.floor(position.z()));
    }

    private static boolean isNonCombatWorker(Profession p) {
        return p == Profession.BUILDER
                || p == Profession.CIVILIAN
                || p == Profession.FARMER
                || p == Profession.MINER
                || p == Profession.CHEF
                || p == Profession.MERCHANT
                || p == Profession.BUTCHER;
    }

    /** 启动全局定时施工循环（异步调度入口，写操作回区域线程）。 */
    void start() {
        BV.scheduler().runAsync(() -> {
            try {
                BV.storage().constructionJournals().create(jobId);
                journalReady = true;
                if (cancelling) {
                    beginRollbackWhenReady();
                } else {
                    BV.scheduler().runGlobal(this::startAfterJournalCreated);
                }
            } catch (Throwable t) {
                BV.plugin().getLogger().log(java.util.logging.Level.SEVERE,
                        "Unable to create construction journal " + jobId, t);
                finished = true;
                releaseCacheSlot();
                releaseWorkers();
                if (BV.building() != null) {
                    BV.building().onJobCancelled(this);
                }
                cancellationFuture.complete(null);
            }
        });
    }

    private void startAfterJournalCreated() {
        if (cancelling || finished) {
            beginRollbackWhenReady();
            return;
        }
        assignWorkers();
        BV.messages().broadcastClickable("building-started", teleportData(), "structure", displayName);
        handle = BV.scheduler().runGlobalTimer(this::tick, 10L, 4L);
    }

    void cancel() {
        if (finished || cancelling || finalizing) {
            return;
        }
        cancelling = true;
        if (handle != null) {
            handle.cancel();
        }
        beginRollbackWhenReady();
    }

    private void releaseCacheSlot() {
        if (boundCache == null || buildType == null) {
            return;
        }
        boundCache.releaseOnCancel(villageId, buildType, worldName, centerX, centerZ);
    }

    private void tick() {
        if (finished || cancelling || finalizing || regionLocation == null) {
            return;
        }
        // 全局任务只负责投递，所有世界、区块与实体访问在目标区域线程执行。
        BV.scheduler().runAtRegion(regionLocation, this::tickAtRegion);
    }

    private void tickAtRegion() {
        if (finished || cancelling || finalizing) {
            return;
        }
        if (cursor.get() >= steps.size()) {
            if (pendingSteps.get() == 0) {
                complete();
            }
            return;
        }
        // The durable write-ahead log for one step must finish before the next
        // step snapshots the same coordinate.
        if (pendingSteps.get() > 0) {
            return;
        }
        // 规范 5.1：工地区块卸载时暂停 tick，避免 runAtRegion 强制加载/生成区块造成卡顿
        World world = regionLocation.getWorld();
        if (world == null || !world.isChunkLoaded(centerX >> 4, centerZ >> 4)) {
            return; // 区块未加载，等下次 tick 再推进
        }
        // 阶段切换：异步询问建筑师 AI 是否继续（实时推演）
        ConstructionStep peek = steps.get(cursor.get());
        if (!peek.phase().equals(lastPhase)) {
            lastPhase = peek.phase();
            requestPhaseAi(peek.phase());
        }

        for (int i = 0; i < STEPS_PER_TICK && cursor.get() < steps.size(); i++) {
            int idx = cursor.getAndIncrement();
            ConstructionStep step = steps.get(idx);
            executeStep(step, idx);
        }
    }

    private void requestPhaseAi(String phase) {
        // 异步推演下一步：不阻塞施工；结果仅用于日志/可选 HOLD
        BV.scheduler().runAsync(() -> {
            try {
                String system = BV.messages().raw("ai-prompt.builder-phase-system");
                String user = BV.messages().raw("ai-prompt.builder-phase-user")
                        .replace("{type}", type)
                        .replace("{phase}", phase)
                        .replace("{loc}", centerX + "," + centerY + "," + centerZ)
                        .replace("{progress}", cursor.get() + "/" + steps.size());
                var ctx = new dev.bettervillagers.ai.AIContext(
                        "build-phase-" + villageId + "-" + phase,
                        "builder", "builder", "build", system, user);
                BV.ai().decide(ctx).thenAccept(r -> {
                    if (r != null && r.isUsable()
                            && r.text().toUpperCase().contains("HOLD")) {
                        // AI 建议暂停：仅记录，不强制中断（保障流程）
                        BV.plugin().getLogger().info(
                                BV.messages().raw("log.building-phase-hold")
                                        .replace("{phase}", phase)
                                        .replace("{village}", String.valueOf(villageId)));
                    }
                });
            } catch (Throwable t) {
                BV.plugin().getLogger().warning(
                        BV.messages().raw("log.tactical-tick-error")
                                .replace("{uuid}", "build-" + villageId)
                                .replace("{error}", "phase-ai: " + t));
            }
        });
    }

    private void executeStep(ConstructionStep step, int sequence) {
        World world = regionLocation.getWorld();
        if (world == null) {
            return;
        }
        Location loc = new Location(world, step.x() + 0.5, step.y(), step.z() + 0.5);
        // 引导最近工人走向工地（非瞬间生成）
        guideNearestWorker(loc);
        pendingSteps.incrementAndGet();
        BV.scheduler().runAtRegion(loc, () -> prepareStep(loc, step, sequence));
    }

    private void prepareStep(Location loc, ConstructionStep step, int sequence) {
        BV.guard().assertRegionThread(loc);
        if (cancelling || finished) {
            stepFinished();
            return;
        }
        if (!loc.isChunkLoaded()) {
            cursor.compareAndSet(sequence + 1, sequence);
            stepFinished();
            return;
        }
        if (BV.regions() != null && BV.regions().isProtected(worldName, step.x(), step.y(), step.z())) {
            failStep(new IllegalStateException("Construction entered a protected region"));
            stepFinished();
            return;
        }
        Block block = loc.getBlock();
        // Existing block entities may contain inventories, text, loot tables or plugin data.
        // Construction never overwrites them, so rollback only needs lossless BlockData snapshots.
        if (block.getState() instanceof TileState) {
            failStep(new IllegalStateException("Construction would overwrite an existing block entity"));
            stepFinished();
            return;
        }
        String oldBlockData = block.getBlockData().getAsString();
        originalBlocks.putIfAbsent(new BlockPosition(step.x(), step.y(), step.z()), oldBlockData);
        dev.bettervillagers.storage.ConstructionChangeRecord change =
                new dev.bettervillagers.storage.ConstructionChangeRecord(
                        jobId, sequence, worldName, step.x(), step.y(), step.z(), oldBlockData);
        BV.scheduler().runAsync(() -> {
            try {
                BV.storage().constructionJournals().append(change);
                BV.scheduler().runAtRegion(loc, () -> {
                    try {
                        if (!cancelling && !finished) {
                            if (BV.regions() != null
                                    && BV.regions().isProtected(worldName, step.x(), step.y(), step.z())) {
                                throw new IllegalStateException("Construction entered a protected region");
                            }
                            String current = loc.getBlock().getBlockData().getAsString();
                            if (!current.equals(oldBlockData)) {
                                throw new IllegalStateException("Block changed while construction step was pending");
                            }
                            applyRecordedStep(loc, step);
                        }
                    } catch (Throwable t) {
                        failStep(t);
                    } finally {
                        stepFinished();
                    }
                });
            } catch (Throwable t) {
                failStep(t);
                stepFinished();
            }
        });
    }

    private void applyRecordedStep(Location loc, ConstructionStep step) {
        BV.guard().assertRegionThread(loc);
        Block block = loc.getBlock();
        Material existing = block.getType();
        // 保护重要容器/工作站；硬结构仅允许 SITE_PREP 清理软障碍
        if (TerrainAnalyzer.isProtected(existing)) {
            throw new IllegalStateException("Construction collided with protected block " + existing);
        }
        if (step.kind() == ConstructionStep.Kind.BREAK) {
            if (!existing.isAir() && existing != Material.BEDROCK
                    && !TerrainAnalyzer.isHardStructure(existing)) {
                // applyPhysics=false：批量施工不触发邻居物理级联（规范 5.2）
                block.setType(Material.AIR, false);
            } else if (!existing.isAir() && existing != Material.BEDROCK
                    && "SITE_PREP".equals(step.phase())
                    && !TerrainAnalyzer.isProtected(existing)) {
                // 场地准备允许清理非保护固体（树叶/草等已在保护外）
                String n = existing.name();
                if (n.endsWith("_LEAVES") || !existing.isSolid() || n.contains("GRASS")) {
                    block.setType(Material.AIR, false);
                }
            } else if (!existing.isAir()) {
                throw new IllegalStateException("Construction could not clear block " + existing);
            }
            return;
        }
        if (existing == step.material() && step.blockData() == null) {
            return;
        }
        // 勿穿模既有石砖/原木结构（碰撞）
        if (TerrainAnalyzer.isHardStructure(existing) && existing.isSolid()) {
            throw new IllegalStateException("Construction collided with existing structure " + existing);
        }
        if (step.material() == null || step.material().isAir()) {
            throw new IllegalArgumentException("Place step has no solid material");
        }
        // 放置前可拆除软固体
        if (existing.isSolid() && existing != step.material()) {
            block.setType(Material.AIR, false);
        }
        // applyPhysics=false：尤其防止放置 WATER/FARMLAND 等触发物理/流体级联（规范 5.2）
        if (step.blockData() != null) {
            try {
                BlockData data = Bukkit.createBlockData(step.blockData());
                block.setBlockData(data, false);
                applyBlockEntityPolicy(block, step.blockEntityPolicy());
                return;
            } catch (IllegalArgumentException ignored) {
                // 模板包含当前服务端不支持的状态时，降级为材质放置。
            }
        }
        block.setType(step.material(), false);
        applyBlockEntityPolicy(block, step.blockEntityPolicy());
    }

    private static void applyBlockEntityPolicy(Block block, BlockEntityPolicy policy) {
        if (policy == null || policy == BlockEntityPolicy.NONE) {
            return;
        }
        if (policy == BlockEntityPolicy.CLEAR_INVENTORY && block.getState() instanceof Container container) {
            container.getInventory().clear();
            container.update(true, false);
        } else if (policy == BlockEntityPolicy.CLEAR_SIGN && block.getState() instanceof Sign sign) {
            for (org.bukkit.block.sign.Side side : org.bukkit.block.sign.Side.values()) {
                sign.getSide(side).line(0, net.kyori.adventure.text.Component.empty());
                sign.getSide(side).line(1, net.kyori.adventure.text.Component.empty());
                sign.getSide(side).line(2, net.kyori.adventure.text.Component.empty());
                sign.getSide(side).line(3, net.kyori.adventure.text.Component.empty());
            }
            sign.update(true, false);
        }
    }

    private void guideNearestWorker(Location target) {
        if (BV.villagers() == null || workerUuids.isEmpty()) {
            return;
        }
        BVillager best = null;
        double bestD = Double.MAX_VALUE;
        for (String uuid : workerUuids) {
            var opt = BV.villagers().get(uuid);
            if (opt.isEmpty()) {
                continue;
            }
            BVillager bv = opt.get();
            LivingEntity e = bv.entity();
            BVillager.PositionSnapshot position = bv.lastKnownPosition();
            if (e == null || position == null || bv.state() == VillagerState.SOCIALIZING) {
                continue;
            }
            if (target.getWorld() == null || !position.world().equals(target.getWorld().getName())) {
                continue;
            }
            double dx = position.x() - target.getX();
            double dy = position.y() - target.getY();
            double dz = position.z() - target.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = bv;
            }
        }
        if (best == null) {
            return;
        }
        BVillager worker = best;
        LivingEntity scheduledEntity = worker.entity();
        if (scheduledEntity == null) {
            return;
        }
        BV.scheduler().runForEntity(scheduledEntity, () -> {
            LivingEntity e = worker.entity();
            if (e == null || worker.state() == VillagerState.SOCIALIZING) {
                return;
            }
            if (e.getWorld().equals(target.getWorld())
                    && e.getLocation().distanceSquared(target) > ARRIVE_DIST_SQ) {
                MovementHelper.moveToward(e, target, WORK_SPEED);
            }
            worker.state(VillagerState.WORKING);
        }, null);
    }

    private void complete() {
        if (finished || cancelling || finalizing) {
            return;
        }
        finalizing = true;
        if (handle != null) {
            handle.cancel();
        }
        if (BV.building() != null) {
            BV.building().onJobCompleted(this);
        }
    }

    private void failStep(Throwable failure) {
        BV.plugin().getLogger().log(java.util.logging.Level.SEVERE,
                "Construction job " + jobId + " failed and will be rolled back", failure);
        cancelling = true;
        if (handle != null) {
            handle.cancel();
        }
    }

    private void stepFinished() {
        int remaining = pendingSteps.decrementAndGet();
        if (remaining < 0) {
            pendingSteps.compareAndSet(remaining, 0);
            remaining = 0;
        }
        if (cancelling && remaining == 0) {
            beginRollbackWhenReady();
        }
    }

    private void beginRollbackWhenReady() {
        if (!cancelling || pendingSteps.get() != 0 || !journalReady
                || !rollbackStarted.compareAndSet(false, true)) {
            return;
        }
        if (BV.building() != null) {
            BV.building().rollbackJob(this);
        }
    }

    void rollbackCompleted() {
        finished = true;
        releaseCacheSlot();
        releaseWorkers();
        if (BV.building() != null) {
            BV.building().onJobCancelled(this);
        }
        cancellationFuture.complete(null);
    }

    void rollbackFailed(Throwable failure) {
        cancellationFuture.completeExceptionally(failure);
    }

    CompletableFuture<Void> cancellationFuture() {
        return cancellationFuture;
    }

    boolean rollbackRequested() {
        return cancelling;
    }

    /** Paper runs disable on its world-owning primary thread, so restore immediately there. */
    void restoreOnPaperShutdown() {
        World world = regionLocation == null ? null : regionLocation.getWorld();
        if (world == null) {
            return;
        }
        for (Map.Entry<BlockPosition, String> entry : originalBlocks.entrySet()) {
            BlockPosition position = entry.getKey();
            world.getBlockAt(position.x(), position.y(), position.z())
                    .setBlockData(Bukkit.createBlockData(entry.getValue()), false);
        }
    }

    void commitCompleted() {
        finalizing = false;
        finished = true;
        releaseWorkers();
        BV.messages().broadcastClickable("building-completed", teleportData(),
                "structure", displayName);
    }

    void prepareRollbackAfterCommitFailure() {
        finalizing = false;
        cancelling = true;
    }

    private void releaseWorkers() {
        if (BV.villagers() == null) {
            return;
        }
        for (String uuid : workerUuids) {
            BV.villagers().get(uuid).ifPresent(bv -> {
                if (bv.state() == VillagerState.WORKING) {
                    bv.state(VillagerState.IDLE);
                }
            });
        }
        workerUuids.clear();
    }

    private record BlockPosition(int x, int y, int z) {
    }
}
