package dev.bettervillagers.building;

import dev.bettervillagers.BV;
import org.bukkit.Location;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 组织化建造管理器：缓存防堆叠 + 四阶段异步施工。
 * <p>
 * 流程：国王命令 → 建筑师 AI（可选）→ 异步场地评估（柏林分析）→ BuildCache 占位 → ConstructionJob。
 * 不修改原版世界柏林生成；写方块仅在区域线程分批执行。
 */
public final class BuildingManager {

    private final BuildCache cache = new BuildCache();
    /** 场地评估半径（格）：线型/街景类较窄，围合类较宽（原硬编码，规范：魔法值提取为常量）。 */
    private static final int ASSESS_RADIUS_NARROW = 6;
    private static final int ASSESS_RADIUS_WIDE = 8;
    private final List<ConstructionJob> activeJobs = new CopyOnWriteArrayList<>();
    private final TerrainAnalyzer analyzer = new TerrainAnalyzer();
    private final Map<Integer, BuildQuota> quotas = new ConcurrentHashMap<>();

    private record BuildQuota(long period, int accepted) {
    }

    public BuildCache cache() {
        return cache;
    }

    /**
     * 下发建造任务（异步）。
     *
     * @param typeName  类型名或命令词
     * @param villageId 村庄
     * @param center    偏好中心（会经缓存偏移）
     */
    public void issueTask(String typeName, int villageId, Location center) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        BuildType type = BuildType.fromCommand(typeName);
        if (type == null) {
            return;
        }
        // 集体活动：仅广播
        if (!type.physical()) {
            String displayName = display(type);
            BV.messages().broadcast("building-started", "structure", displayName);
            BV.scheduler().runAsyncDelayed(() ->
                    BV.messages().broadcast("building-completed", "structure", displayName), 100L);
            return;
        }

        // 同类型上限 / 阶段外任务仍可发，但缓存满则拒绝
        // 建造位置需满足 Y 轴门槛，并仅在村庄接收范围内处理命令
        if (cache.count(villageId, type) >= type.maxPerVillage()) {
            BV.plugin().getLogger().info(
                    BV.messages().raw("log.building-cache-full")
                            .replace("{type}", type.name())
                            .replace("{village}", String.valueOf(villageId)));
            return;
        }

        // 合法空位：找不到则禁止开工（修复回退原点堆叠）
        Location free = cache.findFreeLocation(villageId, type, center);
        if (free == null) {
            BV.plugin().getLogger().info(
                    BV.messages().raw("log.building-no-slot")
                            .replace("{type}", type.name())
                            .replace("{village}", String.valueOf(villageId)));
            return;
        }

        int radius = type == BuildType.ROAD || type == BuildType.STREETSCAPE ? ASSESS_RADIUS_NARROW : ASSESS_RADIUS_WIDE;
        Location candidate = free.clone();

        analyzer.assessAsync(candidate, radius).thenAccept(assessment -> {
            if (assessment == null) {
                logUnsuitable(type.name());
                return;
            }
            // 命令坐标低于实际表层时拒绝，避免建筑计划落在地下
            if (candidate.getBlockY() < assessment.centerY()) {
                rollbackQuota(villageId, candidate.getWorld());
                BV.plugin().getLogger().info(BV.messages().raw("log.building-y-too-low")
                        .replace("{village}", String.valueOf(villageId))
                        .replace("{command-y}", String.valueOf(candidate.getBlockY()))
                        .replace("{surface-y}", String.valueOf(assessment.centerY())));
                return;
            }
            // 严格场地校验：熔岩/水域/悬崖/碰撞过密直接拒绝
            if (!assessment.suitable()) {
                logUnsuitable(type.name());
                return;
            }
            consultBuilderAi(type, assessment).thenAccept(approved -> {
                if (approved == null) {
                    return;
                }
                if (approved == BuildType.DESTROY && type != BuildType.DESTROY) {
                    // 建筑师不应把常规建设改成拆除
                    approved = type;
                }
                // 占位必须在真正开工前；失败则整单取消
                boolean occupied = cache.tryOccupy(
                        villageId, approved,
                        candidate.getWorld().getName(),
                        assessment.centerX(), assessment.targetLevelY(), assessment.centerZ());
                if (!occupied) {
                    BV.plugin().getLogger().info(
                            BV.messages().raw("log.building-cache-race")
                                    .replace("{type}", approved.name()));
                    return;
                }
                List<ConstructionStep> steps = BlueprintPlanner.plan(approved, assessment);
                if (steps.isEmpty()) {
                    cache.releaseOnCancel(villageId, approved, candidate.getWorld().getName(),
                            assessment.centerX(), assessment.targetLevelY(), assessment.centerZ());
                    rollbackQuota(villageId, candidate.getWorld());
                    return;
                }
                String displayName = display(approved);
                ConstructionJob job = new ConstructionJob(
                        villageId, approved.name(), candidate.getWorld().getName(),
                        assessment.centerX(), assessment.targetLevelY(), assessment.centerZ(),
                        steps, displayName);
                job.bindCache(cache, approved);
                activeJobs.add(job);
                job.start();
            });
        }).exceptionally(ex -> {
            BV.plugin().getLogger().warning(
                    BV.messages().raw("log.building-terrain-unsuitable")
                            .replace("{type}", type.name()) + " err=" + ex.getMessage());
            return null;
        });
    }

    private void rollbackQuota(int villageId, org.bukkit.World world) {
        // 规范：异步线程禁止访问世界 API（原 world.getFullTime()）。
        // 配额周期改用真实时间（毫秒），语义等价且不触碰游戏世界；
        // 注：当前配额仅在此处递减，无授予入口，故周期来源变更不影响既有行为。
        long period = System.currentTimeMillis()
                / (24L * 60 * 60 * 1000L * Math.max(1, BV.config().buildTaskPeriodDays()));
        synchronized (quotas) {
            BuildQuota current = quotas.get(villageId);
            if (current != null && current.period() == period && current.accepted() > 0) {
                quotas.put(villageId, new BuildQuota(period, current.accepted() - 1));
            }
        }
    }

    private void logUnsuitable(String type) {
        BV.plugin().getLogger().info(
                BV.messages().raw("log.building-terrain-unsuitable").replace("{type}", type));
    }

    private String display(BuildType t) {
        String key = "structure." + t.structureKey();
        String raw = BV.messages().raw(key);
        return raw.equals(key) ? t.name() : raw;
    }

    private java.util.concurrent.CompletableFuture<BuildType> consultBuilderAi(BuildType type, SiteAssessment a) {
        String system = BV.messages().raw("ai-prompt.builder-system");
        String user = BV.messages().raw("ai-prompt.builder-user")
                .replace("{loc}", a.centerX() + "," + a.targetLevelY() + "," + a.centerZ())
                .replace("{terrain}", a.summary())
                .replace("{complexity}", String.format("%.2f", a.complexity()))
                .replace("{ease}", String.format("%.2f", a.modificationEase()))
                .replace("{command}", type.name());
        var ctx = new dev.bettervillagers.ai.AIContext(
                "builder-plan-" + a.centerX() + "-" + a.centerZ(),
                "builder", "builder", "build", system, user);
        return BV.ai().decide(ctx).thenApply(r -> {
            if (r == null || !r.isUsable()) {
                return type;
            }
            String upper = r.text().toUpperCase();
            if (upper.contains("HOLD")) {
                return null;
            }
            BuildType parsed = BuildType.fromCommand(upper);
            // 建筑师只能微调同阶段类型，防止跳过「先修路」
            if (parsed != null && parsed.phase().order() == type.phase().order()) {
                return parsed;
            }
            return type;
        }).exceptionally(ex -> type);
    }

    public void onJobCompleted(ConstructionJob job) {
        activeJobs.remove(job);
        if (job.buildType() != null) {
            cache.markCompleted(job.villageId(), job.buildType());
        }
    }

    public void onJobCancelled(ConstructionJob job) {
        activeJobs.remove(job);
    }

    /**
     * 插件禁用时取消所有进行中的施工任务（规范 4.x：调度器/任务生命周期管理）。
     * <p>须在 {@link BV#shutdown()} 之前调用，避免 tick 回调中访问已置 null 的全局服务而 NPE。
     */
    public void shutdown() {
        for (ConstructionJob job : activeJobs) {
            try {
                job.cancel();
            } catch (Throwable ignored) {
                // 关闭阶段尽力取消，忽略单个任务异常
            }
        }
        activeJobs.clear();
    }

    public boolean isVillageBuilding(int villageId) {
        for (ConstructionJob j : activeJobs) {
            if (j.villageId() == villageId && !j.finished()) {
                return true;
            }
        }
        return false;
    }

    /** 同阶段并行上限（贴合真实工程节奏：同阶段可并行，跨阶段串行）。 */
    public int activeJobsInPhase(int villageId, BuildType.DevPhase phase) {
        int n = 0;
        for (ConstructionJob j : activeJobs) {
            if (j.villageId() == villageId && !j.finished()
                    && j.buildType() != null && j.buildType().phase() == phase) {
                n++;
            }
        }
        return n;
    }

    public int activeJobCount() {
        return (int) activeJobs.stream().filter(j -> !j.finished()).count();
    }

    /** 当前村庄应执行的发展阶段（要想富先修路）。 */
    public BuildType.DevPhase currentPhase(int villageId, int population) {
        int roadNeed = Math.max(3, Math.min(8, 2 + population / 6));
        int streetNeed = Math.max(2, Math.min(6, 1 + population / 8));
        int houseNeed = Math.max(2, Math.min(10, 1 + population / 5));
        if (!cache.roadsComplete(villageId, roadNeed)) {
            return BuildType.DevPhase.ROADS;
        }
        if (!cache.streetscapeComplete(villageId, streetNeed)) {
            return BuildType.DevPhase.STREETSCAPE;
        }
        if (!cache.housingComplete(villageId, houseNeed, Math.max(1, houseNeed / 2))) {
            return BuildType.DevPhase.HOUSING;
        }
        return BuildType.DevPhase.DEFENSE_LANDSCAPE;
    }

    /** 阶段内推荐建造类型（可并行多种同阶段任务）。 */
    public BuildType recommendType(int villageId, int population) {
        BuildType.DevPhase phase = currentPhase(villageId, population);
        return switch (phase) {
            case ROADS -> BuildType.ROAD;
            case STREETSCAPE -> BuildType.STREETSCAPE;
            case HOUSING -> {
                int houses = cache.count(villageId, BuildType.HOUSE);
                int upgrades = cache.count(villageId, BuildType.UPGRADE_HOUSE);
                int farms = cache.count(villageId, BuildType.FARM);
                if (houses > 0 && upgrades < houses) {
                    yield BuildType.UPGRADE_HOUSE;
                }
                if (farms < Math.max(1, population / 8)) {
                    yield BuildType.FARM;
                }
                yield BuildType.HOUSE;
            }
            case DEFENSE_LANDSCAPE -> {
                int walls = cache.count(villageId, BuildType.WALL);
                int lands = cache.count(villageId, BuildType.LANDSCAPE);
                if (lands < Math.max(1, walls)) {
                    yield BuildType.LANDSCAPE;
                }
                yield BuildType.WALL;
            }
        };
    }
}
