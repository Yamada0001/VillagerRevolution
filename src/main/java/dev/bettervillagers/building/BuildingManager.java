package dev.bettervillagers.building;

import dev.bettervillagers.BV;
import dev.bettervillagers.storage.BuildLayoutRecord;
import dev.bettervillagers.storage.ConstructionChangeRecord;
import dev.bettervillagers.scheduler.PlatformDetector;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates terrain assessment, blueprint planning, build jobs, and persisted layouts.
 */
public final class BuildingManager {

    private final BuildCache cache = new BuildCache();
    private final List<ConstructionJob> activeJobs = new CopyOnWriteArrayList<>();
    private volatile ExecutorService computeExecutor;
    private volatile int computeThreads;
    private final List<ExecutorService> retiredExecutors = new CopyOnWriteArrayList<>();
    private final TerrainAnalyzer analyzer;
    private final StructureTemplateLibrary templates = new StructureTemplateLibrary();
    private final RoadLayout roads = new RoadLayout();
    private final Map<ConstructionJob, BuildLayoutRecord> plannedLayouts = new ConcurrentHashMap<>();
    private final Map<ConstructionJob, PendingRoad> pendingRoads = new ConcurrentHashMap<>();
    private final java.util.Set<java.util.concurrent.CompletableFuture<Void>> pendingCommits =
            ConcurrentHashMap.newKeySet();

    public BuildingManager() {
        this(4);
    }

    public BuildingManager(int asyncThreads) {
        this.computeThreads = Math.max(1, asyncThreads);
        this.computeExecutor = newComputeExecutor(computeThreads);
        this.analyzer = new TerrainAnalyzer(computeExecutor);
        templates.installDefaults();
    }

    private static ExecutorService newComputeExecutor(int threads) {
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "BetterVillagers-BuildCompute");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void reconfigureAsyncThreads(int requestedThreads) {
        int next = Math.max(1, requestedThreads);
        if (next == computeThreads) {
            return;
        }
        ExecutorService previous = computeExecutor;
        computeExecutor = newComputeExecutor(next);
        computeThreads = next;
        analyzer.computeExecutor(computeExecutor);
        previous.shutdown();
        retiredExecutors.add(previous);
    }

    public Location nextRoadSite(int villageId, Location center) {
        return roads.nextSite(villageId, center, cache, templates);
    }

    public java.util.concurrent.CompletableFuture<Void> restoreLayouts() {
        java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
        BV.scheduler().runAsync(() -> {
            try {
                for (BuildLayoutRecord record : BV.storage().buildLayouts().findAll()) {
                    cache.restore(record);
                }
                roads.restore(BV.storage().roadPorts().findAll());
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.thenCompose(ignored -> recoverInterruptedJobs());
    }

    private java.util.concurrent.CompletableFuture<Void> recoverInterruptedJobs() {
        java.util.concurrent.CompletableFuture<List<String>> jobsFuture = new java.util.concurrent.CompletableFuture<>();
        BV.scheduler().runAsync(() -> {
            try {
                jobsFuture.complete(BV.storage().constructionJournals().findPreparedJobs());
            } catch (Throwable t) {
                jobsFuture.completeExceptionally(t);
            }
        });
        return jobsFuture.thenCompose(jobIds -> {
            java.util.concurrent.CompletableFuture<Void> recovery =
                    java.util.concurrent.CompletableFuture.completedFuture(null);
            for (String jobId : jobIds) {
                recovery = recovery.thenCompose(ignored -> rollbackJournal(jobId));
            }
            return recovery;
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

    /** Applies the already-persisted village-id migration to runtime building state. */
    public void mergeVillage(int fromId, int toId) {
        for (ConstructionJob job : List.copyOf(activeJobs)) {
            if (job.villageId() == fromId) {
                job.cancel();
            }
        }
        cache.mergeVillage(fromId, toId);
        roads.mergeVillage(fromId, toId);
    }

    public BuildCache cache() {
        return cache;
    }

    public int activeJobCount() {
        return activeJobs.size();
    }

<<<<<<< Updated upstream
    public void issueTask(String typeName, int villageId, Location center) {
        if (!BV.config().feature("autonomous-building") || center == null || center.getWorld() == null) {
            return;
=======
    public java.util.concurrent.CompletableFuture<Boolean> issueTask(
            String typeName, int villageId, Location center) {
        return issueTask(typeName, villageId, center, false);
    }

    /** Emergency plans use the exact requested type and do not allow builder AI to substitute it. */
    public java.util.concurrent.CompletableFuture<Boolean> issueExactTask(
            String typeName, int villageId, Location center) {
        return issueTask(typeName, villageId, center, true);
    }

    private java.util.concurrent.CompletableFuture<Boolean> issueTask(
            String typeName, int villageId, Location center, boolean exactType) {
        if (!BV.config().feature("autonomous-building") || center == null || center.getWorld() == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
>>>>>>> Stashed changes
        }
        BuildType type = BuildType.fromCommand(typeName);
        if (type == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        if (!type.physical()) {
            String displayName = display(type);
            BV.scheduler().runGlobal(() -> BV.messages().broadcast("building-started", "structure", displayName));
            BV.scheduler().runAsyncDelayed(() -> BV.scheduler().runGlobal(() ->
                    BV.messages().broadcast("building-completed", "structure", displayName)), 100L);
<<<<<<< Updated upstream
            return;
=======
            return java.util.concurrent.CompletableFuture.completedFuture(true);
>>>>>>> Stashed changes
        }
        if (cache.count(villageId, type) >= type.maxPerVillage()) {
            BV.plugin().getLogger().info(
                    BV.messages().raw("log.building-cache-full")
                            .replace("{type}", type.name())
                            .replace("{village}", String.valueOf(villageId)));
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }

        Location candidate = type == BuildType.ROAD ? center.clone() : cache.findFreeLocation(villageId, type, center);
        if (candidate == null) {
            BV.plugin().getLogger().info(BV.messages().raw("log.building-no-slot")
                    .replace("{type}", type.name())
                    .replace("{village}", String.valueOf(villageId)));
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }

        RoadLayout.Reservation reservation = type == BuildType.ROAD ? roads.takeReservation(villageId, candidate) : null;
        StructureTemplate initialTemplate = reservation == null ? null : reservation.template();
        StructureTemplate.Placement initialPlacement = reservation == null ? null : reservation.placement();
        int radius = reservation == null ? templates.assessmentRadius(type)
                : Math.max(initialTemplate.transformedWidth(initialPlacement),
                        initialTemplate.transformedDepth(initialPlacement)) / 2 + 1;

        String worldName = center.getWorld().getName();
<<<<<<< Updated upstream
        analyzer.assessAsync(candidate, radius).thenCompose(assessment -> {
            if (assessment == null || !assessment.suitable()) {
                roads.rollback(villageId, reservation);
                logUnsuitable(type.name());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return consultBuilderAi(type, assessment).thenAccept(approved ->
                    startApprovedBuild(villageId, worldName, candidate, reservation, assessment, type, approved));
=======
        return analyzer.assessAsync(candidate, radius).thenCompose(assessment -> {
            if (assessment == null || !assessment.suitable()) {
                roads.rollback(villageId, reservation);
                logUnsuitable(type.name());
                return java.util.concurrent.CompletableFuture.completedFuture(false);
            }
            java.util.concurrent.CompletableFuture<BuildType> approval = exactType
                    ? java.util.concurrent.CompletableFuture.completedFuture(type)
                    : consultBuilderAi(type, assessment);
            return approval.thenCompose(approved -> startApprovedBuild(
                    villageId, worldName, candidate, reservation, assessment, type, approved));
>>>>>>> Stashed changes
        }).exceptionally(failure -> {
            roads.rollback(villageId, reservation);
            BV.plugin().getLogger().log(java.util.logging.Level.SEVERE,
                    "Unable to prepare construction task " + type + " for village " + villageId, failure);
<<<<<<< Updated upstream
            return null;
        });
    }

    private void startApprovedBuild(int villageId, String worldName, Location candidate,
                                    RoadLayout.Reservation reservation,
                                    SiteAssessment assessment, BuildType requested, BuildType approved) {
=======
            return false;
        });
    }

    private java.util.concurrent.CompletableFuture<Boolean> startApprovedBuild(
            int villageId, String worldName, Location candidate,
            RoadLayout.Reservation reservation,
            SiteAssessment assessment, BuildType requested, BuildType approved) {
>>>>>>> Stashed changes
        if (!BV.config().feature("autonomous-building") || BV.villages().get(villageId).isEmpty()
                || candidate.getWorld() == null || !candidate.getWorld().getName().equals(worldName)
                || approved == null || approved == BuildType.DESTROY && requested != BuildType.DESTROY) {
            roads.rollback(villageId, reservation);
            return java.util.concurrent.CompletableFuture.completedFuture(false);
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

<<<<<<< Updated upstream
        if (touchesProtectedRegion(worldName, steps)) {
            roads.rollback(villageId, reservation);
            return;
        }

        if (!cache.tryOccupy(villageId, approved, worldName,
                assessment.centerX(), assessment.centerZ(), minX, maxX, minZ, maxZ)) {
            roads.rollback(villageId, reservation);
            return;
        }
        if (steps.isEmpty()) {
            cache.releaseOnCancel(villageId, approved, worldName,
                    assessment.centerX(), assessment.centerZ());
            roads.rollback(villageId, reservation);
            return;
        }

        ConstructionJob job = new ConstructionJob(villageId, approved.name(), worldName, candidate,
                assessment.centerX(), assessment.targetLevelY(), assessment.centerZ(), steps, display(approved));
        job.bindCache(cache, approved);
        activeJobs.add(job);
        plannedLayouts.put(job, new BuildLayoutRecord(villageId, worldName, approved.name(),
                templateId, assessment.centerX(), assessment.targetLevelY(), assessment.centerZ(),
                minX, maxX, minZ, maxZ,
                placement == null ? StructureTemplate.Rotation.NONE.name() : placement.rotation().name(),
                placement == null ? StructureTemplate.Mirror.NONE.name() : placement.mirror().name(),
                clusterId));
        if (reservation != null) {
            pendingRoads.put(job, new PendingRoad(candidate.clone(), reservation));
=======
        if (steps.isEmpty() || touchesProtectedRegion(worldName, steps)) {
            roads.rollback(villageId, reservation);
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }

        int finalMinX = minX;
        int finalMaxX = maxX;
        int finalMinZ = minZ;
        int finalMaxZ = maxZ;
        String finalTemplateId = templateId;
        String finalClusterId = clusterId;
        List<ConstructionStep> finalSteps = List.copyOf(steps);
        int maxBuildY = finalSteps.stream().mapToInt(ConstructionStep::y)
                .max().orElse(assessment.targetLevelY());
        int horizontalBuffer = BV.config().raw().getInt(
                "building.site-clearance.horizontal-buffer", 2);
        int verticalBuffer = BV.config().raw().getInt(
                "building.site-clearance.vertical-buffer", 2);
        java.util.concurrent.CompletableFuture<TerrainAnalyzer.ClearanceAssessment> clearanceFuture =
                approved == BuildType.DESTROY
                        ? java.util.concurrent.CompletableFuture.completedFuture(
                                new TerrainAnalyzer.ClearanceAssessment(0, 0, 0))
                        : analyzer.validateClearanceAsync(candidate.getWorld(),
                                finalMinX, finalMaxX, finalMinZ, finalMaxZ,
                                assessment.targetLevelY(), maxBuildY,
                                horizontalBuffer, verticalBuffer);

        return clearanceFuture.thenApply(clearance -> {
            if (clearance == null || !clearance.clear()) {
                roads.rollback(villageId, reservation);
                logObstructed(approved, clearance);
                return false;
            }
            return startClearedBuild(villageId, worldName, candidate, reservation,
                    assessment, approved, placement, finalSteps,
                    finalTemplateId, finalClusterId,
                    finalMinX, finalMaxX, finalMinZ, finalMaxZ);
        });
    }

    private boolean startClearedBuild(
            int villageId, String worldName, Location candidate,
            RoadLayout.Reservation reservation, SiteAssessment assessment,
            BuildType approved, StructureTemplate.Placement placement,
            List<ConstructionStep> steps, String templateId, String clusterId,
            int minX, int maxX, int minZ, int maxZ) {
        if (!cache.tryOccupy(villageId, approved, worldName,
                assessment.centerX(), assessment.centerZ(), minX, maxX, minZ, maxZ)) {
            roads.rollback(villageId, reservation);
            return false;
        }

        ConstructionJob job = null;
        try {
            job = new ConstructionJob(villageId, approved.name(), worldName, candidate,
                    assessment.centerX(), assessment.targetLevelY(), assessment.centerZ(),
                    steps, display(approved));
            job.bindCache(cache, approved);
            activeJobs.add(job);
            plannedLayouts.put(job, new BuildLayoutRecord(villageId, worldName, approved.name(),
                    templateId, assessment.centerX(), assessment.targetLevelY(), assessment.centerZ(),
                    minX, maxX, minZ, maxZ,
                    placement == null ? StructureTemplate.Rotation.NONE.name() : placement.rotation().name(),
                    placement == null ? StructureTemplate.Mirror.NONE.name() : placement.mirror().name(),
                    clusterId));
            if (reservation != null) {
                pendingRoads.put(job, new PendingRoad(candidate.clone(), reservation));
            }
            job.start();
            return true;
        } catch (Throwable failure) {
            if (job != null) {
                activeJobs.remove(job);
                plannedLayouts.remove(job);
                pendingRoads.remove(job);
                try {
                    job.cancel();
                } catch (Throwable ignored) {
                }
            }
            cache.releaseOnCancel(villageId, approved, worldName,
                    assessment.centerX(), assessment.centerZ());
            roads.rollback(villageId, reservation);
            throw new java.util.concurrent.CompletionException(failure);
>>>>>>> Stashed changes
        }
    }

    private static int defaultHalfSize(BuildType type) {
        return Math.max(3, type.minSpacing() / 2 + 2);
    }

    private boolean touchesProtectedRegion(String worldName, List<ConstructionStep> steps) {
        if (BV.regions() == null || worldName == null || steps == null || steps.isEmpty()) {
            return false;
        }
        for (ConstructionStep step : steps) {
            if (BV.regions().isProtected(worldName, step.x(), step.y(), step.z())) {
                return true;
            }
        }
        return false;
    }

    private void logUnsuitable(String type) {
        BV.plugin().getLogger().info(
                BV.messages().raw("log.building-terrain-unsuitable").replace("{type}", type));
    }

    private void logObstructed(BuildType type, TerrainAnalyzer.ClearanceAssessment clearance) {
        int footprint = clearance == null ? -1 : clearance.footprintObstacles();
        int surroundings = clearance == null ? -1 : clearance.surroundingsObstacles();
        int unloaded = clearance == null ? -1 : clearance.unloadedChunks();
        BV.plugin().getLogger().info(BV.messages().raw("log.building-clearance-obstructed")
                .replace("{type}", type.name())
                .replace("{footprint}", String.valueOf(footprint))
                .replace("{surroundings}", String.valueOf(surroundings))
                .replace("{unloaded}", String.valueOf(unloaded)));
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
        PendingRoad pendingRoad = pendingRoads.get(job);
        if (pendingRoad != null) {
            roads.commit(job.villageId(), pendingRoad.site(), pendingRoad.reservation());
        }
        BuildLayoutRecord record = plannedLayouts.get(job);
        if (record != null) {
            cache.rememberCompleted(record);
        }
        List<BuildLayoutRecord> layouts = cache.exportVillage(job.villageId());
        List<dev.bettervillagers.storage.RoadPortRecord> ports = roads.exportVillage(job.villageId());
        java.util.concurrent.CompletableFuture<Void> commit = new java.util.concurrent.CompletableFuture<>();
        pendingCommits.add(commit);
        BV.scheduler().runAsync(() -> {
            try {
                BV.storage().constructionJournals().completeWithLayout(
                        job.jobId(), job.villageId(), layouts, ports);
                commit.complete(null);
            } catch (Throwable t) {
                commit.completeExceptionally(t);
            }
        });
        commit.whenComplete((ignored, failure) -> {
            pendingCommits.remove(commit);
            if (failure == null) {
                activeJobs.remove(job);
                pendingRoads.remove(job);
                plannedLayouts.remove(job);
                BV.scheduler().runGlobal(job::commitCompleted);
                return;
            }
            if (record != null) {
                cache.forgetCompleted(record);
            }
            if (pendingRoad != null) {
                roads.rollbackCommitted(job.villageId(), pendingRoad.site(), pendingRoad.reservation());
            }
            pendingRoads.remove(job);
            plannedLayouts.remove(job);
            job.prepareRollbackAfterCommitFailure();
            BV.plugin().getLogger().log(java.util.logging.Level.SEVERE,
                    "Construction commit failed; rolling back job " + job.jobId(), failure);
            rollbackJob(job);
        });
    }

    void onJobCancelled(ConstructionJob job) {
        activeJobs.remove(job);
        PendingRoad pendingRoad = pendingRoads.remove(job);
        if (pendingRoad != null) {
            roads.rollback(job.villageId(), pendingRoad.reservation());
        }
        plannedLayouts.remove(job);
    }

    void rollbackJob(ConstructionJob job) {
        rollbackJournal(job.jobId()).whenComplete((ignored, failure) -> {
            if (failure != null) {
                BV.plugin().getLogger().log(java.util.logging.Level.SEVERE,
                        "Construction rollback remains pending for job " + job.jobId(), failure);
                job.rollbackFailed(failure);
                return;
            }
            BV.scheduler().runGlobal(job::rollbackCompleted);
        });
    }

    private java.util.concurrent.CompletableFuture<Void> rollbackJournal(String jobId) {
        java.util.concurrent.CompletableFuture<List<ConstructionChangeRecord>> load =
                new java.util.concurrent.CompletableFuture<>();
        BV.scheduler().runAsync(() -> {
            try {
                load.complete(BV.storage().constructionJournals().findChanges(jobId));
            } catch (Throwable t) {
                load.completeExceptionally(t);
            }
        });
        return load.thenCompose(this::restoreChanges)
                .thenCompose(ignored -> {
                    java.util.concurrent.CompletableFuture<Void> cleaned =
                            new java.util.concurrent.CompletableFuture<>();
                    BV.scheduler().runAsync(() -> {
                        try {
                            BV.storage().constructionJournals().rolledBack(jobId);
                            cleaned.complete(null);
                        } catch (Throwable t) {
                            cleaned.completeExceptionally(t);
                        }
                    });
                    return cleaned;
                });
    }

    private java.util.concurrent.CompletableFuture<Void> restoreChanges(
            List<ConstructionChangeRecord> changes) {
        if (changes.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        // The first snapshot for a coordinate is its pre-job value. Restoring just that
        // snapshot avoids ordering races when a blueprint touched the same block twice.
        Map<BlockKey, ConstructionChangeRecord> originals = new LinkedHashMap<>();
        for (ConstructionChangeRecord change : changes) {
            originals.putIfAbsent(new BlockKey(
                    change.world(), change.x(), change.y(), change.z()), change);
        }
        java.util.concurrent.CompletableFuture<Void> restored = new java.util.concurrent.CompletableFuture<>();
        BV.scheduler().runGlobal(() -> {
            try {
                List<java.util.concurrent.CompletableFuture<Void>> blockFutures =
                        new java.util.ArrayList<>(originals.size());
                for (ConstructionChangeRecord change : originals.values()) {
                    World world = Bukkit.getWorld(change.world());
                    if (world == null) {
                        throw new IllegalStateException("World unavailable during construction rollback: "
                                + change.world());
                    }
                    Location location = new Location(world, change.x(), change.y(), change.z());
                    java.util.concurrent.CompletableFuture<Void> blockFuture =
                            new java.util.concurrent.CompletableFuture<>();
                    blockFutures.add(blockFuture);
                    BV.scheduler().runAtRegion(location, () -> {
                        try {
                            if (!world.isChunkLoaded(change.x() >> 4, change.z() >> 4)) {
                                world.getChunkAt(change.x() >> 4, change.z() >> 4);
                            }
                            location.getBlock().setBlockData(
                                    Bukkit.createBlockData(change.oldBlockData()), false);
                            blockFuture.complete(null);
                        } catch (Throwable t) {
                            blockFuture.completeExceptionally(t);
                        }
                    });
                }
                java.util.concurrent.CompletableFuture.allOf(
                        blockFutures.toArray(java.util.concurrent.CompletableFuture[]::new))
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                restored.complete(null);
                            } else {
                                restored.completeExceptionally(failure);
                            }
                        });
            } catch (Throwable t) {
                restored.completeExceptionally(t);
            }
        });
        return restored;
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
        List<ConstructionJob> jobs = List.copyOf(activeJobs);
        for (ConstructionJob job : jobs) {
            try {
                job.cancel();
            } catch (Throwable ignored) {
            }
        }
        if (!PlatformDetector.isFolia() && Bukkit.isPrimaryThread()) {
            for (ConstructionJob job : jobs) {
                try {
                    if (job.rollbackRequested()) {
                        job.restoreOnPaperShutdown();
                    }
                } catch (Throwable failure) {
                    BV.plugin().getLogger().log(java.util.logging.Level.SEVERE,
                            "Immediate Paper construction rollback failed for " + job.jobId(), failure);
                }
            }
        } else if (PlatformDetector.isFolia()) {
            try {
                java.util.concurrent.CompletableFuture.allOf(jobs.stream()
                                .filter(ConstructionJob::rollbackRequested)
                                .map(ConstructionJob::cancellationFuture)
                                .toArray(java.util.concurrent.CompletableFuture[]::new))
                        .get(15L, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Startup recovery will replay any journal whose region task did not finish in time.
            }
        }
        activeJobs.clear();
        try {
            java.util.concurrent.CompletableFuture.allOf(
                    pendingCommits.toArray(java.util.concurrent.CompletableFuture[]::new))
                    .get(10L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A PREPARED journal is intentionally retained for next-start rollback.
        }
        List<ExecutorService> executors = new java.util.ArrayList<>(retiredExecutors);
        executors.add(computeExecutor);
        executors.forEach(ExecutorService::shutdown);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
            for (ExecutorService executor : executors) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    executor.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executors.forEach(ExecutorService::shutdownNow);
        }
    }

    public void cancelAll() {
        for (ConstructionJob job : List.copyOf(activeJobs)) {
            job.cancel();
        }
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

    public HousingStatus housingStatus(int villageId, int population,
                                       int minimumPopulation, int residentsPerHouse, int reserveHouses) {
        int housingUnits = cache.count(villageId, BuildType.HOUSE)
                + cache.count(villageId, BuildType.UPGRADE_HOUSE);
        return evaluateHousing(population, housingUnits, minimumPopulation, residentsPerHouse, reserveHouses);
    }

    static HousingStatus evaluateHousing(int population, int housingUnits,
                                         int minimumPopulation, int residentsPerHouse, int reserveHouses) {
        int safePopulation = Math.max(0, population);
        int safeHousing = Math.max(0, housingUnits);
        int safeMinimumPopulation = Math.max(1, minimumPopulation);
        int safeResidentsPerHouse = Math.max(1, residentsPerHouse);
        int safeReserve = Math.max(0, reserveHouses);
        int required = Math.ceilDiv(safePopulation, safeResidentsPerHouse) + safeReserve;
        return new HousingStatus(safePopulation, safeHousing, required,
                safePopulation >= safeMinimumPopulation && safeHousing < required);
    }

    public record HousingStatus(int population, int housingUnits, int requiredHousingUnits, boolean shortage) {
    }

    private record PendingRoad(Location site, RoadLayout.Reservation reservation) {
    }

    private record BlockKey(String world, int x, int y, int z) {
    }
}
