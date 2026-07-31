package dev.bettervillagers.building;

import dev.bettervillagers.BV;
import dev.bettervillagers.storage.BuildLayoutRecord;
import org.bukkit.Location;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Coordinates terrain assessment, blueprint planning, build jobs, and persisted layouts.
 */
public final class BuildingManager {

    private final BuildCache cache = new BuildCache();
    private final List<ConstructionJob> activeJobs = new CopyOnWriteArrayList<>();
    private final TerrainAnalyzer analyzer = new TerrainAnalyzer();
    private final StructureTemplateLibrary templates = new StructureTemplateLibrary();
    private final RoadLayout roads = new RoadLayout();
    private final Map<ConstructionJob, BuildLayoutRecord> plannedLayouts = new ConcurrentHashMap<>();
    private final Map<ConstructionJob, PendingRoad> pendingRoads = new ConcurrentHashMap<>();

    public BuildingManager() {
        templates.installDefaults();
    }

    public Location nextRoadSite(int villageId, Location center) {
        return roads.nextSite(villageId, center, cache, templates);
    }

    public void restoreLayouts() {
        BV.scheduler().runAsync(() -> {
            for (BuildLayoutRecord record : BV.storage().buildLayouts().findAll()) {
                cache.restore(record);
            }
            roads.restore(BV.storage().roadPorts().findAll());
        });
    }

    public void clearVillage(int villageId) {
        cache.clear(villageId);
        roads.clear(villageId);
        BV.scheduler().runAsync(() -> {
            BV.storage().buildLayouts().deleteVillage(villageId);
            BV.storage().roadPorts().deleteVillage(villageId);
        });
    }

    public BuildCache cache() {
        return cache;
    }

    public void issueTask(String typeName, int villageId, Location center) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        BuildType type = BuildType.fromCommand(typeName);
        if (type == null) {
            return;
        }
        if (!type.physical()) {
            String displayName = display(type);
            BV.messages().broadcast("building-started", "structure", displayName);
            BV.scheduler().runAsyncDelayed(() ->
                    BV.messages().broadcast("building-completed", "structure", displayName), 100L);
            return;
        }
        if (cache.count(villageId, type) >= type.maxPerVillage()) {
            BV.plugin().getLogger().info(
                    BV.messages().raw("log.building-cache-full")
                            .replace("{type}", type.name())
                            .replace("{village}", String.valueOf(villageId)));
            return;
        }

        Location candidate = type == BuildType.ROAD ? center.clone() : cache.findFreeLocation(villageId, type, center);
        if (candidate == null) {
            BV.plugin().getLogger().info(BV.messages().raw("log.building-no-slot")
                    .replace("{type}", type.name())
                    .replace("{village}", String.valueOf(villageId)));
            return;
        }

        RoadLayout.Reservation reservation = type == BuildType.ROAD ? roads.takeReservation(villageId, candidate) : null;
        StructureTemplate initialTemplate = reservation == null ? null : reservation.template();
        StructureTemplate.Placement initialPlacement = reservation == null ? null : reservation.placement();
        int radius = reservation == null ? templates.assessmentRadius(type)
                : Math.max(initialTemplate.transformedWidth(initialPlacement),
                        initialTemplate.transformedDepth(initialPlacement)) / 2 + 1;

        analyzer.assessAsync(candidate, radius).thenAccept(assessment -> {
            if (assessment == null || !assessment.suitable()) {
                roads.rollback(villageId, reservation);
                logUnsuitable(type.name());
                return;
            }
            consultBuilderAi(type, assessment).thenAccept(approved ->
                    startApprovedBuild(villageId, candidate, reservation, assessment, type, approved));
        });
    }

    private void startApprovedBuild(int villageId, Location candidate, RoadLayout.Reservation reservation,
                                    SiteAssessment assessment, BuildType requested, BuildType approved) {
        if (approved == null || approved == BuildType.DESTROY && requested != BuildType.DESTROY) {
            roads.rollback(villageId, reservation);
            return;
        }
        StructureTemplate template = reservation == null
                ? templates.choose(approved, assessment.biomeKey(), assessment.seed(),
                        assessment.centerX(), assessment.centerZ()).orElse(null)
                : reservation.template();
        StructureTemplate.Placement placement = reservation == null
                ? (template == null ? null : StructureTemplate.Placement.centered(template))
                : reservation.placement();

        int width = template == null ? defaultHalfSize(approved) * 2 + 1 : template.transformedWidth(placement);
        int depth = template == null ? defaultHalfSize(approved) * 2 + 1 : template.transformedDepth(placement);
        int minX = assessment.centerX() - (placement == null ? width / 2 : placement.anchorX());
        int minZ = assessment.centerZ() - (placement == null ? depth / 2 : placement.anchorZ());
        int maxX = minX + width - 1;
        int maxZ = minZ + depth - 1;
        String clusterId = "";
        String templateId = template == null ? "" : template.id();
        List<ConstructionStep> steps = BlueprintPlanner.plan(approved, assessment, template, placement);

        if (approved == BuildType.LANDSCAPE) {
            int population = BV.villages().get(villageId)
                    .map(dev.bettervillagers.village.Village::population)
                    .orElse(0);
            StructureCluster cluster = templates.chooseCluster(assessment.biomeKey(), assessment.seed(), villageId,
                    population, cache.completedClusters(villageId)).orElse(null);
            StructureClusterPlanner.Plan clusterPlan = cluster == null ? null
                    : StructureClusterPlanner.plan(cluster, templates, assessment.centerX(),
                            assessment.targetLevelY(), assessment.centerZ());
            if (clusterPlan != null) {
                clusterId = clusterPlan.clusterId();
                templateId = cluster.rootTemplate();
                steps = clusterPlan.steps();
                minX = clusterPlan.minX();
                maxX = clusterPlan.maxX();
                minZ = clusterPlan.minZ();
                maxZ = clusterPlan.maxZ();
            }
        }

        if (!cache.tryOccupy(villageId, approved, candidate.getWorld().getName(),
                assessment.centerX(), assessment.centerZ(), minX, maxX, minZ, maxZ)) {
            roads.rollback(villageId, reservation);
            return;
        }
        if (steps.isEmpty()) {
            cache.releaseOnCancel(villageId, approved, candidate.getWorld().getName(),
                    assessment.centerX(), assessment.centerZ());
            roads.rollback(villageId, reservation);
            return;
        }

        ConstructionJob job = new ConstructionJob(villageId, approved.name(), candidate.getWorld().getName(),
                assessment.centerX(), assessment.targetLevelY(), assessment.centerZ(), steps, display(approved));
        job.bindCache(cache, approved);
        activeJobs.add(job);
        plannedLayouts.put(job, new BuildLayoutRecord(villageId, candidate.getWorld().getName(), approved.name(),
                templateId, assessment.centerX(), assessment.targetLevelY(), assessment.centerZ(),
                minX, maxX, minZ, maxZ,
                placement == null ? StructureTemplate.Rotation.NONE.name() : placement.rotation().name(),
                placement == null ? StructureTemplate.Mirror.NONE.name() : placement.mirror().name(),
                clusterId));
        if (reservation != null) {
            pendingRoads.put(job, new PendingRoad(candidate.clone(), reservation));
        }
        job.start();
    }

    private static int defaultHalfSize(BuildType type) {
        return Math.max(3, type.minSpacing() / 2 + 2);
    }

    private void logUnsuitable(String type) {
        BV.plugin().getLogger().info(
                BV.messages().raw("log.building-terrain-unsuitable").replace("{type}", type));
    }

    private String display(BuildType type) {
        String key = "structure." + type.structureKey();
        String raw = BV.messages().raw(key);
        return raw.equals(key) ? type.name() : raw;
    }

    private java.util.concurrent.CompletableFuture<BuildType> consultBuilderAi(BuildType type, SiteAssessment assessment) {
        String system = BV.messages().raw("ai-prompt.builder-system");
        String user = BV.messages().raw("ai-prompt.builder-user")
                .replace("{loc}", assessment.centerX() + "," + assessment.targetLevelY() + "," + assessment.centerZ())
                .replace("{terrain}", assessment.summary())
                .replace("{complexity}", String.format("%.2f", assessment.complexity()))
                .replace("{ease}", String.format("%.2f", assessment.modificationEase()))
                .replace("{command}", type.name());
        var ctx = new dev.bettervillagers.ai.AIContext(
                "builder-plan-" + assessment.centerX() + "-" + assessment.centerZ(),
                "builder", "builder", "build", system, user);
        return BV.ai().decide(ctx).thenApply(response -> {
            if (response == null || !response.isUsable()) {
                return type;
            }
            String upper = response.text().toUpperCase();
            if (upper.contains("HOLD")) {
                return null;
            }
            BuildType parsed = BuildType.fromCommand(upper);
            if (parsed != null && parsed.phase().order() == type.phase().order()) {
                return parsed;
            }
            return type;
        }).exceptionally(ex -> handleBuilderAiFailure(type, ex));
    }

    private BuildType handleBuilderAiFailure(BuildType fallback, Throwable ex) {
        if (BV.config().debugMode() && ex != null) {
            BV.plugin().getLogger().warning("Builder AI failed: " + ex.getMessage());
        }
        return fallback;
    }

    void onJobCompleted(ConstructionJob job) {
        activeJobs.remove(job);
        PendingRoad pendingRoad = pendingRoads.remove(job);
        if (pendingRoad != null) {
            roads.commit(job.villageId(), pendingRoad.site(), pendingRoad.reservation());
        }
        BuildLayoutRecord record = plannedLayouts.remove(job);
        if (record != null) {
            cache.rememberCompleted(record);
            persistVillage(record.villageId());
        }
    }

    void onJobCancelled(ConstructionJob job) {
        activeJobs.remove(job);
        PendingRoad pendingRoad = pendingRoads.remove(job);
        if (pendingRoad != null) {
            roads.rollback(job.villageId(), pendingRoad.reservation());
        }
        plannedLayouts.remove(job);
    }

    private void persistVillage(int villageId) {
        List<BuildLayoutRecord> layouts = cache.exportVillage(villageId);
        var ports = roads.exportVillage(villageId);
        BV.scheduler().runAsync(() -> {
            BV.storage().buildLayouts().replaceVillage(villageId, layouts);
            BV.storage().roadPorts().replaceVillage(villageId, ports);
        });
    }

    public void shutdown() {
        for (ConstructionJob job : activeJobs) {
            try {
                job.cancel();
            } catch (Throwable ignored) {
            }
        }
        activeJobs.clear();
    }

    public boolean isVillageBuilding(int villageId) {
        for (ConstructionJob job : activeJobs) {
            if (job.villageId() == villageId && job.active()) {
                return true;
            }
        }
        return false;
    }

    public int activeJobsInPhase(int villageId, BuildType.DevPhase phase) {
        int count = 0;
        for (ConstructionJob job : activeJobs) {
            if (job.villageId() == villageId && job.active()
                    && job.buildType() != null && job.buildType().phase() == phase) {
                count++;
            }
        }
        return count;
    }

    public BuildType.DevPhase currentPhase(int villageId, int population) {
        int roadNeed = Math.clamp(2 + population / 6, 3, 8);
        int streetNeed = Math.clamp(1 + population / 8, 2, 6);
        int houseNeed = Math.clamp(1 + population / 5, 2, 10);
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

    public BuildType recommendType(int villageId, int population) {
        BuildType.DevPhase phase = currentPhase(villageId, population);
        return switch (phase) {
            case ROADS -> BuildType.ROAD;
            case STREETSCAPE -> BuildType.STREETSCAPE;
            case HOUSING -> {
                int houses = cache.count(villageId, BuildType.HOUSE);
                int upgrades = cache.count(villageId, BuildType.UPGRADE_HOUSE);
                int farms = cache.count(villageId, BuildType.FARM);
                int fairs = cache.count(villageId, BuildType.TRADE_FAIR);
                if (houses > 0 && upgrades < houses) {
                    yield BuildType.UPGRADE_HOUSE;
                }
                if (farms < Math.max(1, population / 8)) {
                    yield BuildType.FARM;
                }
                if (population >= 8 && fairs < Math.max(1, population / 16)) {
                    yield BuildType.TRADE_FAIR;
                }
                yield BuildType.HOUSE;
            }
            case DEFENSE_LANDSCAPE -> {
                int walls = cache.count(villageId, BuildType.WALL);
                int lands = cache.count(villageId, BuildType.LANDSCAPE);
                if (lands < Math.max(2, walls + 1)) {
                    yield BuildType.LANDSCAPE;
                }
                yield BuildType.WALL;
            }
        };
    }

    private record PendingRoad(Location site, RoadLayout.Reservation reservation) {
    }
}
