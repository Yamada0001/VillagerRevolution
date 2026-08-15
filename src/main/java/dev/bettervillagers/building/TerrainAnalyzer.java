package dev.bettervillagers.building;

import dev.bettervillagers.BV;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Captures terrain on the correct region thread and analyzes it off-thread.
 */
final class TerrainAnalyzer {

    private static final Set<Material> PROTECTED = Set.of(
            Material.BEDROCK, Material.WATER, Material.LAVA, Material.OBSIDIAN,
            Material.CHEST, Material.ENDER_CHEST, Material.BARREL,
            Material.CRAFTING_TABLE, Material.FURNACE, Material.BLAST_FURNACE,
            Material.SMOKER, Material.BREWING_STAND, Material.ENCHANTING_TABLE,
            Material.ANVIL, Material.BEACON, Material.SPAWNER,
            Material.BELL, Material.LECTERN, Material.COMPOSTER, Material.LOOM,
            Material.SMITHING_TABLE, Material.STONECUTTER, Material.CARTOGRAPHY_TABLE,
            Material.FLETCHING_TABLE, Material.GRINDSTONE, Material.CAULDRON
    );

    private static final Set<Material> HARD_STRUCTURE = Set.of(
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
            Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG,
            Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.STONE_BRICKS,
            Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS,
            Material.HAY_BLOCK, Material.BOOKSHELF, Material.WHITE_BED, Material.RED_BED
    );

    private final PerlinNoise noise;
    private volatile Executor computeExecutor;

    TerrainAnalyzer(Executor computeExecutor) {
        this(0xB17D_ACE5L, computeExecutor);
    }

    private TerrainAnalyzer(long seed, Executor computeExecutor) {
        this.noise = new PerlinNoise(seed);
        this.computeExecutor = computeExecutor;
    }

    void computeExecutor(Executor computeExecutor) {
        this.computeExecutor = computeExecutor;
    }

    CompletableFuture<SiteAssessment> assessAsync(Location center, int radius) {
        if (center == null || center.getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }
        return captureSnapshotAsync(center, radius)
                .thenApplyAsync(snapshot -> snapshot == null ? null : analyze(snapshot), computeExecutor);
    }

    /**
     * Performs an exact three-dimensional clearance scan after a template and its placement are known.
     * Every world read is kept on the owning region thread so this remains safe on Folia.
     */
    CompletableFuture<ClearanceAssessment> validateClearanceAsync(
            World world, int minX, int maxX, int minZ, int maxZ,
            int baseY, int maxBuildY, int horizontalBuffer, int verticalBuffer) {
        if (world == null || minX > maxX || minZ > maxZ) {
            return CompletableFuture.completedFuture(new ClearanceAssessment(0, 0, 1));
        }
        int safeHorizontalBuffer = Math.clamp(horizontalBuffer, 0, 8);
        int safeVerticalBuffer = Math.clamp(verticalBuffer, 0, 8);
        int scanMinX = minX - safeHorizontalBuffer;
        int scanMaxX = maxX + safeHorizontalBuffer;
        int scanMinZ = minZ - safeHorizontalBuffer;
        int scanMaxZ = maxZ + safeHorizontalBuffer;
        int scanMinY = Math.max(world.getMinHeight(), baseY + 1);
        int scanMaxY = Math.min(world.getMaxHeight() - 1,
                Math.max(scanMinY, maxBuildY + safeVerticalBuffer));

        Map<Long, List<ClearanceColumn>> byChunk = new HashMap<>();
        for (int x = scanMinX; x <= scanMaxX; x++) {
            for (int z = scanMinZ; z <= scanMaxZ; z++) {
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
                boolean footprint = x >= minX && x <= maxX && z >= minZ && z <= maxZ;
                byChunk.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new ClearanceColumn(x, z, footprint));
            }
        }

        List<CompletableFuture<ClearanceSlice>> captures = new ArrayList<>(byChunk.size());
        for (List<ClearanceColumn> columns : byChunk.values()) {
            ClearanceColumn first = columns.getFirst();
            int chunkX = first.worldX() >> 4;
            int chunkZ = first.worldZ() >> 4;
            Location regionKey = new Location(world, (chunkX << 4) + 8, baseY, (chunkZ << 4) + 8);
            CompletableFuture<ClearanceSlice> capture = new CompletableFuture<>();
            captures.add(capture);
            BV.scheduler().runAtRegion(regionKey, () -> {
                try {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        capture.complete(new ClearanceSlice(0, 0, 1));
                        return;
                    }
                    int footprintObstacles = 0;
                    int surroundingsObstacles = 0;
                    for (ClearanceColumn column : columns) {
                        boolean obstructed = false;
                        for (int y = scanMinY; y <= scanMaxY; y++) {
                            if (isClearanceObstacle(world.getBlockAt(column.worldX(), y, column.worldZ()).getType())) {
                                obstructed = true;
                                break;
                            }
                        }
                        if (obstructed) {
                            if (column.footprint()) {
                                footprintObstacles++;
                            } else {
                                surroundingsObstacles++;
                            }
                        }
                    }
                    capture.complete(new ClearanceSlice(footprintObstacles, surroundingsObstacles, 0));
                } catch (Throwable failure) {
                    capture.completeExceptionally(failure);
                }
            });
        }

        return CompletableFuture.allOf(captures.toArray(CompletableFuture[]::new))
                .thenApplyAsync(ignored -> {
                    int footprintObstacles = 0;
                    int surroundingsObstacles = 0;
                    int unloadedChunks = 0;
                    for (CompletableFuture<ClearanceSlice> capture : captures) {
                        ClearanceSlice slice = capture.join();
                        footprintObstacles += slice.footprintObstacles();
                        surroundingsObstacles += slice.surroundingsObstacles();
                        unloadedChunks += slice.unloadedChunks();
                    }
                    return new ClearanceAssessment(
                            footprintObstacles, surroundingsObstacles, unloadedChunks);
                }, computeExecutor);
    }

    private CompletableFuture<TerrainSnapshot> captureSnapshotAsync(Location center, int radius) {
        int size = radius * 2 + 1;
        int originX = center.getBlockX() - radius;
        int originZ = center.getBlockZ() - radius;
        World world = center.getWorld();
        CompletableFuture<CaptureHeader> headerFuture = new CompletableFuture<>();

        BV.scheduler().runAtRegion(center, () -> {
            try {
                int preferY = resolvePreferY(world, center);
                String biomeKey = "plains";
                try {
                    Biome biome = world.getBiome(center.getBlockX(), preferY, center.getBlockZ());
                    biomeKey = biome.getKey().getKey();
                } catch (Throwable ignored) {
                }
                headerFuture.complete(new CaptureHeader(preferY, world.getSeed(), biomeKey));
            } catch (Throwable t) {
                headerFuture.completeExceptionally(t);
            }
        });
        return headerFuture.thenCompose(header -> captureColumns(
                world, originX, originZ, size, header));
    }

    private CompletableFuture<TerrainSnapshot> captureColumns(World world, int originX, int originZ,
                                                               int size, CaptureHeader header) {
        int[] surfaceY = new int[size * size];
        Material[] surfaceMat = new Material[size * size];
        boolean[] blocked = new boolean[size * size];
        Map<Long, List<Column>> byChunk = new HashMap<>();
        for (int localZ = 0; localZ < size; localZ++) {
            for (int localX = 0; localX < size; localX++) {
                int worldX = originX + localX;
                int worldZ = originZ + localZ;
                int chunkX = worldX >> 4;
                int chunkZ = worldZ >> 4;
                long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
                byChunk.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new Column(localX, localZ, worldX, worldZ));
            }
        }

        List<CompletableFuture<Void>> captures = new ArrayList<>(byChunk.size());
        for (List<Column> columns : byChunk.values()) {
            Column first = columns.getFirst();
            int chunkX = first.worldX() >> 4;
            int chunkZ = first.worldZ() >> 4;
            Location regionKey = new Location(world, (chunkX << 4) + 8, header.preferY(), (chunkZ << 4) + 8);
            CompletableFuture<Void> capture = new CompletableFuture<>();
            captures.add(capture);
            BV.scheduler().runAtRegion(regionKey, () -> {
                try {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        for (Column column : columns) {
                            int index = column.localX() + column.localZ() * size;
                            surfaceY[index] = header.preferY();
                            surfaceMat[index] = Material.AIR;
                            blocked[index] = true;
                        }
                    } else {
                        for (Column column : columns) {
                            int index = column.localX() + column.localZ() * size;
                            int y = sampleColumnSurfaceY(world, column.worldX(), header.preferY(), column.worldZ());
                            surfaceY[index] = y;
                            Block foot = world.getBlockAt(column.worldX(), y, column.worldZ());
                            surfaceMat[index] = foot.getType();
                            blocked[index] = columnBlocked(
                                    world, column.worldX(), y, column.worldZ(), foot.getType());
                        }
                    }
                    capture.complete(null);
                } catch (Throwable t) {
                    capture.completeExceptionally(t);
                }
            });
        }
        return CompletableFuture.allOf(captures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new TerrainSnapshot(originX, originZ, size, size,
                        surfaceY, surfaceMat, blocked, header.seed(), header.biomeKey()));
    }

    private boolean columnBlocked(World world, int x, int surfaceY, int z, Material footType) {
        for (int dy = 1; dy <= 4; dy++) {
            Material material = world.getBlockAt(x, surfaceY + dy, z).getType();
            if (isHardObstacle(material)) {
                return true;
            }
        }
        Material above = world.getBlockAt(x, surfaceY + 1, z).getType();
        return above == Material.WATER || above == Material.LAVA
                || footType == Material.WATER || footType == Material.LAVA;
    }

    private int resolvePreferY(World world, Location center) {
        int x = center.getBlockX();
        int z = center.getBlockZ();
        int high;
        try {
            high = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        } catch (Throwable ignored) {
            high = center.getBlockY();
        }
        return Math.abs(center.getBlockY() - high) > 6 ? high : center.getBlockY();
    }

    private SiteAssessment analyze(TerrainSnapshot snapshot) {
        int sizeX = snapshot.sizeX();
        int sizeZ = snapshot.sizeZ();
        int cellCount = sizeX * sizeZ;
        int[] heights = new int[cellCount];
        for (int i = 0; i < cellCount; i++) {
            heights[i] = snapshot.surfaceY(i % sizeX, i / sizeX);
        }
        java.util.Arrays.sort(heights);
        int targetY = heights[cellCount / 2];

        double slopeSum = 0;
        int slopeSamples = 0;
        int obstacles = 0;
        int dig = 0;
        int fill = 0;
        double perlinSum = 0;
        double heightVar = 0;
        double meanHeight = 0;
        int waterColumns = 0;
        int lavaColumns = 0;
        Map<Material, Integer> materialCounts = new HashMap<>();
        for (int height : heights) {
            meanHeight += height;
        }
        meanHeight /= cellCount;

        for (int localZ = 0; localZ < sizeZ; localZ++) {
            for (int localX = 0; localX < sizeX; localX++) {
                int height = snapshot.surfaceY(localX, localZ);
                heightVar += (height - meanHeight) * (height - meanHeight);
                if (snapshot.blocked(localX, localZ)) {
                    obstacles++;
                }
                Material surface = snapshot.surfaceMat(localX, localZ);
                materialCounts.merge(surface, 1, Integer::sum);
                if (surface == Material.WATER || surface == Material.KELP || surface == Material.SEAGRASS) {
                    waterColumns++;
                }
                if (surface == Material.LAVA || surface == Material.MAGMA_BLOCK) {
                    lavaColumns++;
                }
                int delta = height - targetY;
                if (delta > 0) {
                    dig += delta;
                } else if (delta < 0) {
                    fill -= delta;
                }
                if (localX + 1 < sizeX) {
                    slopeSum += Math.abs(snapshot.surfaceY(localX + 1, localZ) - height);
                    slopeSamples++;
                }
                if (localZ + 1 < sizeZ) {
                    slopeSum += Math.abs(snapshot.surfaceY(localX, localZ + 1) - height);
                    slopeSamples++;
                }
                double wx = snapshot.worldX(localX) * 0.05;
                double wz = snapshot.worldZ(localZ) * 0.05;
                double wy = height * 0.02;
                perlinSum += Math.abs(noise.fbm(wx, wy, wz));
            }
        }

        Material dominant = Material.GRASS_BLOCK;
        int dominantCount = 0;
        for (var entry : materialCounts.entrySet()) {
            if (entry.getValue() > dominantCount) {
                dominantCount = entry.getValue();
                dominant = entry.getKey();
            }
        }

        double avgSlope = slopeSamples == 0 ? 0 : slopeSum / slopeSamples;
        double obstacleDensity = (double) obstacles / cellCount;
        double perlinRoughness = perlinSum / cellCount;
        double stdHeight = Math.sqrt(heightVar / cellCount);
        double complexity = clamp01(
                0.28 * clamp01(avgSlope / 4.0)
                        + 0.22 * clamp01(obstacleDensity * 2)
                        + 0.22 * clamp01(stdHeight / 6.0)
                        + 0.18 * clamp01(perlinRoughness * 1.5)
                        + 0.10 * clamp01(waterColumns / (double) cellCount * 3));

        double modificationWork = (dig + fill) / (double) cellCount;
        double modificationEase = clamp01(1.0 - 0.40 * clamp01(modificationWork / 3.0)
                - 0.30 * complexity
                - 0.20 * clamp01(obstacleDensity)
                - 0.10 * clamp01(waterColumns / (double) cellCount * 4));

        int centerX = snapshot.originX() + sizeX / 2;
        int centerZ = snapshot.originZ() + sizeZ / 2;
        int centerSurfaceY = snapshot.surfaceY(sizeX / 2, sizeZ / 2);
        String summary = String.format(
                "xyz=(%d,%d,%d) targetY=%d surfaceY=%d slope=%.2f obstacles=%d dig=%d fill=%d water=%d lava=%d biome=%s surface=%s complexity=%.2f ease=%.2f",
                centerX, centerSurfaceY, centerZ, targetY, centerSurfaceY, avgSlope, obstacles, dig, fill,
                waterColumns, lavaColumns, snapshot.biomeKey(), dominant.name(), complexity, modificationEase);

        return new SiteAssessment(centerX, centerZ, centerSurfaceY, complexity, modificationEase,
                avgSlope, obstacleDensity, summary, snapshot.biomeKey(),
                waterColumns, lavaColumns, snapshot.seed(), sizeX);
    }

    private int sampleColumnSurfaceY(World world, int x, int preferY, int z) {
        int min = world.getMinHeight() + 1;
        int max = world.getMaxHeight() - 2;
        int high;
        try {
            high = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        } catch (Throwable ignored) {
            high = preferY;
        }
        high = Math.clamp(high, min, max);
        for (int y = high; y >= min; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type == Material.WATER || type == Material.LAVA) {
                return y;
            }
            if (!type.isAir() && !isSoftPassable(type)) {
                break;
            }
        }
        for (int y = high; y >= min; y--) {
            Block foot = world.getBlockAt(x, y, z);
            Material type = foot.getType();
            if (!isStandableSurface(type)) {
                continue;
            }
            Material up = world.getBlockAt(x, y + 1, z).getType();
            if (up.isAir() || !up.isSolid() || isSoftPassable(up)) {
                return y;
            }
        }
        return high;
    }

    private static boolean isStandableSurface(Material type) {
        if (!type.isSolid()) {
            return false;
        }
        if (PROTECTED.contains(type) || HARD_STRUCTURE.contains(type)) {
            return false;
        }
        String name = type.name();
        return !name.endsWith("_LEAVES") && type != Material.SNOW && type != Material.POWDER_SNOW;
    }

    private static boolean isSoftPassable(Material material) {
        String name = material.name();
        return name.endsWith("_LEAVES") || name.endsWith("_SAPLING") || material == Material.SNOW
                || material == Material.TALL_GRASS || material == Material.SHORT_GRASS || material == Material.FERN
                || material == Material.DEAD_BUSH || material == Material.VINE;
    }

    private static boolean isHardObstacle(Material material) {
        if (material.isAir() || isSoftPassable(material)) {
            return false;
        }
        return PROTECTED.contains(material) || HARD_STRUCTURE.contains(material) || material.isSolid();
    }

    static boolean isClearanceObstacle(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        if (name.equals("AIR") || name.endsWith("_AIR") || isReplaceableDecoration(material)) {
            return false;
        }
        if (name.endsWith("_LEAVES")) {
            return true;
        }
        return PROTECTED.contains(material) || HARD_STRUCTURE.contains(material) || material.isSolid();
    }

    private static boolean isReplaceableDecoration(Material material) {
        String name = material.name();
        return name.endsWith("_SAPLING") || name.endsWith("_FLOWER") || name.endsWith("_TULIP")
                || name.endsWith("_PETALS") || name.endsWith("_CARPET")
                || name.equals("DANDELION") || name.equals("POPPY") || name.equals("BLUE_ORCHID")
                || name.equals("ALLIUM") || name.equals("AZURE_BLUET") || name.equals("OXEYE_DAISY")
                || name.equals("CORNFLOWER") || name.equals("LILY_OF_THE_VALLEY")
                || name.equals("WITHER_ROSE") || name.equals("TORCHFLOWER")
                || material == Material.SNOW || material == Material.TALL_GRASS
                || material == Material.SHORT_GRASS || material == Material.FERN
                || material == Material.DEAD_BUSH || material == Material.VINE;
    }

    private static double clamp01(double value) {
        return Math.clamp(value, 0.0, 1.0);
    }

    static boolean isProtected(Material material) {
        return PROTECTED.contains(material);
    }

    static boolean isHardStructure(Material material) {
        return HARD_STRUCTURE.contains(material) || PROTECTED.contains(material);
    }

    private record CaptureHeader(int preferY, long seed, String biomeKey) {
    }

    private record Column(int localX, int localZ, int worldX, int worldZ) {
    }

    record ClearanceAssessment(int footprintObstacles, int surroundingsObstacles, int unloadedChunks) {
        boolean clear() {
            return footprintObstacles == 0 && surroundingsObstacles == 0 && unloadedChunks == 0;
        }
    }

    private record ClearanceColumn(int worldX, int worldZ, boolean footprint) {
    }

    private record ClearanceSlice(int footprintObstacles, int surroundingsObstacles, int unloadedChunks) {
    }
}
