package dev.bettervillagers.building;

import dev.bettervillagers.BV;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 周围环境异步检测与场地评估（Async + 不破坏原版柏林世界生成）。
 * <p>
 * 增强：多层地表采样、水域/熔岩/结构障碍、生物群系与主导地表、高度图回传供贴地建造。
 */
public final class TerrainAnalyzer {

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

    /** 视作“既有结构/自然巨物”不可硬穿的固体。 */
    private static final Set<Material> HARD_STRUCTURE = Set.of(
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
            Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG,
            Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.STONE_BRICKS,
            Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS,
            Material.HAY_BLOCK, Material.BOOKSHELF, Material.WHITE_BED, Material.RED_BED
    );

    private final PerlinNoise noise;

    public TerrainAnalyzer(long seed) {
        this.noise = new PerlinNoise(seed);
    }

    public TerrainAnalyzer() {
        this(0xB17D_ACE5L);
    }

    public CompletableFuture<SiteAssessment> assessAsync(Location center, int radius) {
        if (center == null || center.getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<TerrainSnapshot> snapFut = captureSnapshotAsync(center, radius);
        return snapFut.thenApplyAsync(snap -> {
            if (snap == null) {
                return null;
            }
            return analyze(snap);
        });
    }

    public CompletableFuture<TerrainSnapshot> captureSnapshotAsync(Location center, int radius) {
        CompletableFuture<TerrainSnapshot> fut = new CompletableFuture<>();
        int size = radius * 2 + 1;
        int ox = center.getBlockX() - radius;
        int oz = center.getBlockZ() - radius;
        World world = center.getWorld();
        long seed = world.getSeed();
        // 先用世界高度图校正 preferY，避免候选点悬空/深埋
        BV.scheduler().runAtRegion(center, () -> {
            try {
                int preferY = resolvePreferY(world, center);
                int oy = preferY;
                int[] surfaceY = new int[size * size];
                Material[] surfaceMat = new Material[size * size];
                boolean[] blocked = new boolean[size * size];
                String biomeKey = "plains";
                try {
                    Biome b = world.getBiome(center.getBlockX(), preferY, center.getBlockZ());
                    biomeKey = b.getKey().getKey();
                } catch (Throwable ignored) {
                }
                for (int lz = 0; lz < size; lz++) {
                    for (int lx = 0; lx < size; lx++) {
                        int wx = ox + lx;
                        int wz = oz + lz;
                        int idx = lx + lz * size;
                        int sy = sampleColumnSurfaceY(world, wx, preferY, wz);
                        surfaceY[idx] = sy;
                        Block foot = world.getBlockAt(wx, sy, wz);
                        surfaceMat[idx] = foot.getType();
                        boolean blk = false;
                        for (int dy = 1; dy <= 4; dy++) {
                            Material m = world.getBlockAt(wx, sy + dy, wz).getType();
                            if (isHardObstacle(m)) {
                                blk = true;
                                break;
                            }
                        }
                        // 水面/熔岩柱也视为阻塞
                        Material above = world.getBlockAt(wx, sy + 1, wz).getType();
                        if (above == Material.WATER || above == Material.LAVA
                                || foot.getType() == Material.WATER || foot.getType() == Material.LAVA) {
                            blk = true;
                        }
                        blocked[idx] = blk;
                    }
                }
                fut.complete(new TerrainSnapshot(
                        world.getName(), ox, oy, oz, size, size,
                        surfaceY, surfaceMat, blocked, seed, biomeKey));
            } catch (Throwable t) {
                fut.completeExceptionally(t);
            }
        });
        return fut;
    }

    /** 以世界最高固体/运动方块为准，再结合玩家候选 Y 纠偏。 */
    private int resolvePreferY(World world, Location center) {
        int x = center.getBlockX();
        int z = center.getBlockZ();
        int high;
        try {
            high = world.getHighestBlockYAt(x, z);
        } catch (Throwable t) {
            high = center.getBlockY();
        }
        // 候选 Y 若离真实地表过远，强制贴地（修半空/地底）
        if (Math.abs(center.getBlockY() - high) > 6) {
            return high;
        }
        return center.getBlockY();
    }

    public SiteAssessment analyze(TerrainSnapshot snap) {
        int sizeX = snap.sizeX();
        int sizeZ = snap.sizeZ();
        int n = sizeX * sizeZ;
        int[] heights = new int[n];
        for (int i = 0; i < n; i++) {
            heights[i] = snap.surfaceY(i % sizeX, i / sizeX);
        }
        java.util.Arrays.sort(heights);
        int targetY = heights[n / 2];

        double slopeSum = 0;
        int slopeSamples = 0;
        int obstacles = 0;
        int dig = 0;
        int fill = 0;
        double perlinSum = 0;
        double heightVar = 0;
        double meanH = 0;
        int waterCols = 0;
        int lavaCols = 0;
        Map<Material, Integer> matCount = new HashMap<>();
        for (int i = 0; i < n; i++) {
            meanH += heights[i];
        }
        meanH /= n;

        int[] heightMap = new int[n];
        for (int lz = 0; lz < sizeZ; lz++) {
            for (int lx = 0; lx < sizeX; lx++) {
                int h = snap.surfaceY(lx, lz);
                heightMap[lx + lz * sizeX] = h;
                heightVar += (h - meanH) * (h - meanH);
                if (snap.blocked(lx, lz)) {
                    obstacles++;
                }
                Material sm = snap.surfaceMat(lx, lz);
                matCount.merge(sm, 1, Integer::sum);
                if (sm == Material.WATER || sm == Material.KELP || sm == Material.SEAGRASS) {
                    waterCols++;
                }
                if (sm == Material.LAVA || sm == Material.MAGMA_BLOCK) {
                    lavaCols++;
                }
                int dy = h - targetY;
                if (dy > 0) {
                    dig += dy;
                } else if (dy < 0) {
                    fill += -dy;
                }
                if (lx + 1 < sizeX) {
                    slopeSum += Math.abs(snap.surfaceY(lx + 1, lz) - h);
                    slopeSamples++;
                }
                if (lz + 1 < sizeZ) {
                    slopeSum += Math.abs(snap.surfaceY(lx, lz + 1) - h);
                    slopeSamples++;
                }
                double wx = snap.worldX(lx) * 0.05;
                double wz = snap.worldZ(lz) * 0.05;
                double wy = h * 0.02;
                perlinSum += Math.abs(noise.fbm(wx, wy, wz, 4, 0.5, 2.0));
            }
        }

        Material dominant = Material.GRASS_BLOCK;
        int bestC = 0;
        for (var e : matCount.entrySet()) {
            if (e.getValue() > bestC) {
                bestC = e.getValue();
                dominant = e.getKey();
            }
        }

        double avgSlope = slopeSamples == 0 ? 0 : slopeSum / slopeSamples;
        double obstacleDensity = (double) obstacles / n;
        double perlinRoughness = perlinSum / n;
        double stdHeight = Math.sqrt(heightVar / n);

        double complexity = clamp01(
                0.28 * clamp01(avgSlope / 4.0)
                        + 0.22 * clamp01(obstacleDensity * 2)
                        + 0.22 * clamp01(stdHeight / 6.0)
                        + 0.18 * clamp01(perlinRoughness * 1.5)
                        + 0.10 * clamp01(waterCols / (double) n * 3));

        double modWork = (dig + fill) / (double) Math.max(1, n);
        double modificationEase = clamp01(1.0 - 0.40 * clamp01(modWork / 3.0)
                - 0.30 * complexity
                - 0.20 * clamp01(obstacleDensity)
                - 0.10 * clamp01(waterCols / (double) n * 4));

        int cx = snap.originX() + sizeX / 2;
        int cz = snap.originZ() + sizeZ / 2;
        // 中心列真实地表，避免平面 target 与中心错位
        int centerSurfaceY = snap.surfaceY(sizeX / 2, sizeZ / 2);
        String summary = String.format(
                "xyz=(%d,%d,%d) targetY=%d surfaceY=%d slope=%.2f obstacles=%d dig=%d fill=%d water=%d lava=%d biome=%s surface=%s complexity=%.2f ease=%.2f",
                cx, centerSurfaceY, cz, targetY, centerSurfaceY, avgSlope, obstacles, dig, fill,
                waterCols, lavaCols, snap.biomeKey(), dominant.name(), complexity, modificationEase);

        return new SiteAssessment(cx, centerSurfaceY, cz, centerSurfaceY, complexity, modificationEase,
                avgSlope, obstacleDensity, perlinRoughness, obstacles, fill, dig, summary,
                snap.biomeKey(), dominant.name(), waterCols, lavaCols, snap.seed(),
                heightMap, sizeX, snap.originX(), snap.originZ());
    }

    private int sampleColumnSurfaceY(World world, int x, int preferY, int z) {
        int min = world.getMinHeight() + 1;
        int max = world.getMaxHeight() - 2;
        int high;
        try {
            high = world.getHighestBlockYAt(x, z);
        } catch (Throwable t) {
            high = preferY;
        }
        high = Math.min(Math.max(high, min), max);
        // 先保留流体表面，避免向下落到海床后丢失水域风险
        for (int y = high; y >= min; y--) {
            Material t = world.getBlockAt(x, y, z).getType();
            if (t == Material.WATER || t == Material.LAVA) {
                return y;
            }
            if (!t.isAir() && !isSoftPassable(t)) {
                break;
            }
        }
        // 从最高点向下找可站立固体（跳过树叶/雪层等非承载）
        for (int y = high; y >= min; y--) {
            Block foot = world.getBlockAt(x, y, z);
            Material t = foot.getType();
            if (!isStandableSurface(t)) {
                continue;
            }
            Material up = world.getBlockAt(x, y + 1, z).getType();
            if (up.isAir() || !up.isSolid() || isSoftPassable(up)) {
                return y;
            }
        }
        return high;
    }

    private static boolean isStandableSurface(Material t) {
        if (!t.isSolid()) {
            return false;
        }
        if (PROTECTED.contains(t) && t != Material.WATER) {
            return t != Material.BEDROCK && t != Material.LAVA;
        }
        // 树叶/屏障不算地表
        String n = t.name();
        return !n.endsWith("_LEAVES") && t != Material.SNOW && t != Material.POWDER_SNOW;
    }

    private static boolean isSoftPassable(Material m) {
        String n = m.name();
        return n.endsWith("_LEAVES") || n.endsWith("_SAPLING") || m == Material.SNOW
                || m == Material.TALL_GRASS || m == Material.SHORT_GRASS || m == Material.FERN
                || m == Material.DEAD_BUSH || m == Material.VINE;
    }

    private static boolean isHardObstacle(Material m) {
        if (m.isAir() || isSoftPassable(m)) {
            return false;
        }
        if (PROTECTED.contains(m)) {
            return true;
        }
        if (HARD_STRUCTURE.contains(m)) {
            return true;
        }
        return m.isSolid();
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    public static boolean isProtected(Material m) {
        return PROTECTED.contains(m);
    }

    public static boolean isHardStructure(Material m) {
        return HARD_STRUCTURE.contains(m) || PROTECTED.contains(m);
    }

    public SiteAssessment assessSyncOnRegion(Location center, int radius) {
        AtomicReference<TerrainSnapshot> ref = new AtomicReference<>();
        try {
            captureSnapshotAsync(center, radius).thenAccept(ref::set).join();
        } catch (Exception e) {
            // 规范 3.3：禁止静默吞异常，记录上下文便于排查（坐标/半径/原因）
            BV.plugin().getLogger().warning(
                    BV.messages().raw("log.tactical-tick-error")
                            .replace("{uuid}", "terrain-assess")
                            .replace("{error}", "assessSync: " + e));
            return null;
        }
        TerrainSnapshot snap = ref.get();
        return snap == null ? null : analyze(snap);
    }
}
