package dev.bettervillagers.behavior.strategic;

import dev.bettervillagers.BV;
import dev.bettervillagers.ai.AIContext;
import dev.bettervillagers.ai.AIResult;
import dev.bettervillagers.building.BuildingManager;
import dev.bettervillagers.building.BuildType;
import dev.bettervillagers.profession.ProfessionData;
import dev.bettervillagers.village.Village;
import dev.bettervillagers.villager.BVillager;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战略层 AI：四阶段「要想富先修路」建造优先级 + 异步下发。
 * <ol>
 *   <li>道路网络</li>
 *   <li>道路两侧街景</li>
 *   <li>存量房屋优化</li>
 *   <li>景观 + 防御城墙闭环</li>
 * </ol>
 */
public final class StrategicAI {

    private final long intervalMillis;
    private static final int DEFAULT_MAX_PARALLEL_SAME_PHASE = 3;
    /** Minecraft 夜间起始/结束 tick（与 SocialEngine 共用语义）。 */
    private static final long NIGHT_START_TICKS = 13000L;
    private static final long NIGHT_END_TICKS = 23000L;
    /** Sleeping hours may defer ordinary development, but emergency housing always bypasses this. */
    private static final double DEFAULT_NIGHT_SKIP_PROBABILITY = 0.25;
    private final Set<Integer> planningVillages = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Long> lastCommandTime = new ConcurrentHashMap<>();
    private volatile boolean shutdown;

    /**
     * 清理指定村庄的战略相关缓存（村庄合并/删除时调用，规范 4.x：避免静态 Map 残留 stale 条目）。
     */
    public void clearVillage(int villageId) {
        planningVillages.remove(villageId);
        lastCommandTime.remove(villageId);
    }

    public StrategicAI(long intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    public void decide(BVillager king) {
        if (shutdown) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - king.lastStrategic() < intervalMillis) {
            return;
        }
        king.lastStrategic(now);
        Village village = BV.villages().get(king.villageId()).orElse(null);
        if (village == null || king.entity() == null) {
            return;
        }
        if (BV.diplomacy() != null) {
            BV.diplomacy().review(king, village);
        }
        if (BV.activities() != null) {
            BV.activities().tick(village, king);
        }
        if (!BV.config().feature("autonomous-building")) {
            return;
        }
        Long lastCmd = lastCommandTime.get(king.villageId());
        long commandCooldownMillis = Math.max(10L, BV.config().raw().getLong(
                "building.king-command-cooldown-seconds", 45L)) * 1000L;
        if (lastCmd != null && now - lastCmd < commandCooldownMillis) {
            debugDeferred(village.id(), "command-cooldown");
            return;
        }
        if (planningVillages.contains(king.villageId())) {
            debugDeferred(village.id(), "site-planning-in-progress");
            return;
        }
        if (BV.building() == null) {
            return;
        }
        int pop = BV.villages().countVillagersInVillage(village.id());
        var housing = BV.building().housingStatus(village.id(), pop,
                BV.config().raw().getInt("building.housing.emergency-minimum-population", 12),
                BV.config().raw().getInt("building.housing.residents-per-house", 4),
                BV.config().raw().getInt("building.housing.reserve-houses", 1));
        if (!housing.shortage() && king.entity() != null) {
            long worldTime = king.entity().getWorld().getTime();
            double nightSkipProbability = Math.clamp(BV.config().raw().getDouble(
                    "building.king-night-skip-probability", DEFAULT_NIGHT_SKIP_PROBABILITY), 0.0, 1.0);
            if (worldTime >= NIGHT_START_TICKS && worldTime <= NIGHT_END_TICKS
                    && Math.random() < nightSkipProbability) {
                debugDeferred(village.id(), "night-rest");
                return;
            }
        }
        BuildType.DevPhase phase = housing.shortage()
                ? BuildType.DevPhase.HOUSING : BV.building().currentPhase(village.id(), pop);

        int normalParallelLimit = Math.clamp(BV.config().raw().getInt(
                "building.max-parallel-per-phase", DEFAULT_MAX_PARALLEL_SAME_PHASE), 1, 8);
        int housingParallelLimit = Math.clamp(BV.config().raw().getInt(
                "building.housing.max-parallel-builds", 4), 2, 8);
        int parallelLimit = housing.shortage() ? housingParallelLimit : normalParallelLimit;
        // 同阶段可并行；跨阶段仍串行，避免两套规划争抢同一片区域。
        int same = BV.building().activeJobsInPhase(village.id(), phase);
        if (same >= parallelLimit) {
            debugDeferred(village.id(), "parallel-limit");
            return;
        }
        if (same == 0 && BV.building().isVillageBuilding(village.id())) {
            // 本村有施工但不是当前发展阶段：等待完成后再跨阶段
            debugDeferred(village.id(), "other-phase-active");
            return;
        }

        if (!BV.villages().hasBuilder(village.id())) {
            BV.plugin().getLogger().info(
                    BV.messages().raw("log.building-no-builder")
                            .replace("{village}", String.valueOf(village.id())));
            return;
        }

        planningVillages.add(village.id());
        if (housing.shortage()) {
            int batchSize = housingBatchSize(housing, same, housingParallelLimit);
            if (batchSize <= 0) {
                planningVillages.remove(village.id());
                return;
            }
            BV.scheduler().runGlobal(() -> dispatchHousingPlan(king, village, housing, batchSize));
            return;
        }
        ProfessionData pd = king.professionData();
        double bravery = pd != null ? pd.personality().bravery() : 0.3;
        double greed = pd != null ? pd.personality().greed() : 0.3;

        BuildType recommended = BV.building().recommendType(village.id(), pop);
        String phaseName = phase.name();
        String system = BV.messages().raw("ai-prompt.strategic-king-system");
        String locText = village.centerX() + "," + village.centerY() + "," + village.centerZ();
        String user = BV.messages().raw("ai-prompt.strategic-king-user")
                .replace("{pop}", String.valueOf(pop))
                .replace("{loc}", locText)
                .replace("{bravery}", String.format("%.2f", bravery))
                .replace("{greed}", String.format("%.2f", greed))
                .replace("{integrity}", "phase=" + phaseName + ",recommend=" + recommended.name()
                        + ",roads=" + BV.building().cache().count(village.id(), BuildType.ROAD)
                        + ",street=" + BV.building().cache().count(village.id(), BuildType.STREETSCAPE)
                        + ",house=" + BV.building().cache().count(village.id(), BuildType.HOUSE)
                        + ",wall=" + BV.building().cache().count(village.id(), BuildType.WALL)
                        + BV.messages().raw("ai-prompt.strategic-phase-order"));

        AIContext ctx = new AIContext(king.uuid(), king.name(), "king", "strategic", system, user);
        BV.ai().decide(ctx)
                .thenAccept(r -> {
                    if (!isRunning()) {
                        planningVillages.remove(village.id());
                        return;
                    }
                    applyPlan(king, village, r, recommended, phase);
                })
                .exceptionally(ex -> {
                    planningVillages.remove(village.id());
                    if (isRunning()) {
                        BV.plugin().getLogger().warning(
                                BV.messages().raw("log.strategic-tick-error")
                                        .replace("{uuid}", king.uuid()).replace("{error}", String.valueOf(ex)));
                    }
                    return null;
                });
    }

    private void applyPlan(BVillager king, Village village, AIResult result,
                           BuildType recommended, BuildType.DevPhase phase) {
        if (!isRunning()) {
            planningVillages.remove(village.id());
            return;
        }
        boolean dispatched = false;
        try {
            BuildType type = recommended;
            boolean isDegraded = result == null || !result.isUsable();
            if (!isDegraded) {
                String upper = result.text().toUpperCase();
                if (!upper.contains("HOLD")) {
                    BuildType parsed = BuildType.fromCommand(upper);
                    // 严格阶段约束：AI 不得跳阶段
                    if (parsed != null && parsed.physical() && parsed.phase() == phase) {
                        type = parsed;
                    }
                }
            }
            // 推荐结果本身必须满足当前阶段，避免异常配置导致空指针或跨阶段建造。
            if (type == null || !type.physical() || type.phase() != phase) {
                return;
            }

            final BuildType finalType = type;
            String displayName = BV.messages().raw("structure." + finalType.structureKey());
            if (displayName.startsWith("structure.")) {
                displayName = finalType.name();
            }
            final String notificationName = displayName;
            BV.scheduler().runGlobal(() -> dispatchPlan(king, village, finalType, notificationName));
            dispatched = true;
        } finally {
            if (!dispatched) {
                planningVillages.remove(village.id());
            }
        }
    }

    private void dispatchPlan(BVillager king, Village village, BuildType type, String notificationName) {
        if (!isRunning() || !BV.config().feature("autonomous-building")) {
            planningVillages.remove(village.id());
            return;
        }
        org.bukkit.Location center = pickSite(village, type);
        if (center == null || center.getWorld() == null) {
            planningVillages.remove(village.id());
            return;
        }
        scheduleIssue(village, type, center, false).whenComplete((started, failure) -> {
            try {
                if (failure != null) {
                    logRejected(village.id(), type, failure);
                } else if (Boolean.TRUE.equals(started)) {
                    recordSuccessfulCommand(village, king.name(), notificationName);
                } else {
                    logRejected(village.id(), type, null);
                }
            } finally {
                planningVillages.remove(village.id());
            }
        });
    }

    private void dispatchHousingPlan(BVillager king, Village village,
                                     BuildingManager.HousingStatus housing, int batchSize) {
        if (!isRunning() || !BV.config().feature("autonomous-building")) {
            planningVillages.remove(village.id());
            return;
        }
        java.util.List<org.bukkit.Location> sites = pickHousingSites(village, batchSize);
        if (sites.isEmpty()) {
            planningVillages.remove(village.id());
            return;
        }
        java.util.List<java.util.concurrent.CompletableFuture<Boolean>> attempts = sites.stream()
                .map(site -> scheduleIssue(village, BuildType.HOUSE, site, true))
                .toList();
        java.util.concurrent.CompletableFuture.allOf(
                attempts.toArray(java.util.concurrent.CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> {
                    try {
                        int started = (int) attempts.stream()
                                .filter(attempt -> attempt.isDone() && !attempt.isCompletedExceptionally()
                                        && Boolean.TRUE.equals(attempt.join()))
                                .count();
                        if (started > 0) {
                            String command = BV.messages().raw("king-housing-command")
                                    .replace("{population}", String.valueOf(housing.population()))
                                    .replace("{houses}", String.valueOf(housing.housingUnits()))
                                    .replace("{required}", String.valueOf(housing.requiredHousingUnits()))
                                    .replace("{count}", String.valueOf(started));
                            recordSuccessfulCommand(village, king.name(), command);
                        } else {
                            logRejected(village.id(), BuildType.HOUSE, failure);
                        }
                    } finally {
                        planningVillages.remove(village.id());
                    }
                });
    }

    private java.util.concurrent.CompletableFuture<Boolean> scheduleIssue(
            Village village, BuildType type, org.bukkit.Location center, boolean exactType) {
        java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
        try {
            BV.scheduler().runAtRegion(center, () -> {
                try {
                    int surfaceY = center.getWorld().getHighestBlockYAt(
                            center.getBlockX(), center.getBlockZ(),
                            org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                    center.setY(surfaceY);
                    java.util.concurrent.CompletableFuture<Boolean> issue = exactType
                            ? BV.building().issueExactTask(type.name(), village.id(), center)
                            : BV.building().issueTask(type.name(), village.id(), center);
                    issue.whenComplete((started, failure) -> {
                        if (failure != null) {
                            result.completeExceptionally(failure);
                        } else {
                            result.complete(Boolean.TRUE.equals(started));
                        }
                    });
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result.orTimeout(60, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void recordSuccessfulCommand(Village village, String kingName, String notification) {
        lastCommandTime.put(village.id(), System.currentTimeMillis());
        if (isRunning()) {
            BV.scheduler().runGlobal(() -> {
                if (isRunning()) {
                    notifyVillagePlayers(village, kingName, notification);
                }
            });
        }
    }

    private void logRejected(int villageId, BuildType type, Throwable failure) {
        String error = failure == null ? "site-rejected" : String.valueOf(failure.getMessage());
        BV.plugin().getLogger().info(BV.messages().raw("log.king-building-rejected")
                .replace("{village}", String.valueOf(villageId))
                .replace("{type}", type.name())
                .replace("{error}", error));
    }

    public void shutdown() {
        shutdown = true;
        planningVillages.clear();
        lastCommandTime.clear();
    }

    private boolean isRunning() {
        return !shutdown && BV.plugin() != null && BV.plugin().isEnabled()
                && BV.scheduler() != null && BV.messages() != null && BV.building() != null;
    }

    private void notifyVillagePlayers(Village village, String kingName, String command) {
        int extra = BV.config().villageEntryRangeExtra();
        double radius = village.radius() + extra;
        double radiusSq = radius * radius;
        String message = BV.messages().raw("village-entry-command")
                .replace("{king}", dev.bettervillagers.i18n.MessageService.escapeUntrusted(kingName))
                .replace("{command}", command);
        org.bukkit.Bukkit.getOnlinePlayers().forEach(player ->
            BV.scheduler().runForEntity(player, () -> {
                if (!player.getWorld().getName().equals(village.world())) {
                    return;
                }
                org.bukkit.Location pl = player.getLocation();
                double dx = pl.getX() - village.centerX();
                double dy = pl.getY() - village.centerY();
                double dz = pl.getZ() - village.centerZ();
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    player.sendMessage(dev.bettervillagers.i18n.MessageService.deserialize(message));
                }
            }, null));
    }

    static int housingBatchSize(BuildingManager.HousingStatus housing,
                                int activeHousingPhaseJobs, int parallelLimit) {
        if (housing == null || !housing.shortage()) {
            return 0;
        }
        int missingHousing = Math.max(0,
                housing.requiredHousingUnits() - housing.housingUnits());
        int availableSlots = Math.max(0, parallelLimit - Math.max(0, activeHousingPhaseJobs));
        return Math.min(missingHousing, availableSlots);
    }

    private java.util.List<org.bukkit.Location> pickHousingSites(Village village, int count) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(village.world());
        if (world == null || count <= 0) {
            return java.util.List.of();
        }
        org.bukkit.Location base = new org.bukkit.Location(
                world, village.centerX(), village.centerY(), village.centerZ());
        int existing = BV.building().cache().count(village.id(), BuildType.HOUSE);
        double startAngle = java.util.concurrent.ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        java.util.List<org.bukkit.Location> sites = new java.util.ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            int sequence = existing + offset;
            int ring = sequence / 6;
            int slot = sequence % 6;
            double radius = 20.0 + ring * 10.0;
            double angle = startAngle + slot * (Math.PI * 2 / 6.0) + ring * (Math.PI / 6.0);
            sites.add(base.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
        }
        return sites;
    }

    private void debugDeferred(int villageId, String reason) {
        if (!BV.config().debugMode()) {
            return;
        }
        BV.plugin().getLogger().info(BV.messages().raw("log.king-building-deferred")
                .replace("{village}", String.valueOf(villageId))
                .replace("{reason}", reason));
    }

    /**
     * 按类型选择与村庄格局协调的落点：
     * 道路沿中心十字偏移；街景贴路；房屋内环；城墙外环。
     */
    private org.bukkit.Location pickSite(Village village, BuildType type) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(village.world());
        if (world == null) {
            return null;
        }
        org.bukkit.Location base = new org.bukkit.Location(
                world, village.centerX(), village.centerY(), village.centerZ());
        double angle = Math.random() * Math.PI * 2;
        int idx = BV.building().cache().count(village.id(), type);
        return switch (type) {
            case ROAD -> BV.building().nextRoadSite(village.id(), base);
            case STREETSCAPE -> {
                double r = 6 + (idx % 4) * 4;
                yield base.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
            }
            case HOUSE, UPGRADE_HOUSE, FARM, TRADE_FAIR -> {
                double r = 10 + (idx % 5) * 5;
                yield base.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
            }
            case WALL -> {
                double r = Math.max(16, village.radius() * 0.8);
                double a = (idx * (Math.PI / 2)) + angle * 0.1;
                yield base.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            }
            case LANDSCAPE -> {
                double r = 8 + idx * 3;
                yield base.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
            }
            default -> base.clone().add(Math.cos(angle) * 10, 0, Math.sin(angle) * 10);
        };
    }
}
