package dev.bettervillagers.building;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 根据场地评估与建筑类型生成分阶段施工步骤（异步纯计算）。
 * <p>
 * 增强：高度图贴地、生物群系材质、柏林变异尺寸/装饰、生活化陈设、轻微风化。
 * 不修改原版世界柏林生成器。
 */
public final class BlueprintPlanner {

    private BlueprintPlanner() {
    }

    public static List<ConstructionStep> plan(BuildType type, SiteAssessment site) {
        if (type == null || site == null) {
            return List.of();
        }
        List<ConstructionStep> steps = new ArrayList<>();
        int cx = site.centerX();
        int cz = site.centerZ();
        Random rng = new Random(site.seed() ^ ((long) cx << 20) ^ ((long) cz << 8) ^ type.ordinal() * 0x9E3779B97F4A7C15L);
        BiomePalette palette = BiomePalette.of(site.biomeKey(), site.dominantSurface(), rng);

        // 贴地轻量平整：按高度图逐列处理，避免整片平推导致悬空/挖山
        int prepR = prepRadius(type);
        for (int dx = -prepR; dx <= prepR; dx++) {
            for (int dz = -prepR; dz <= prepR; dz++) {
                int wx = cx + dx;
                int wz = cz + dz;
                int ground = site.surfaceYAt(wx, wz);
                int maxClear = type == BuildType.ROAD || type == BuildType.STREETSCAPE ? 2 : 3;
                for (int dy = 1; dy <= maxClear; dy++) {
                    steps.add(ConstructionStep.breakBlock(wx, ground + dy, wz, "SITE_PREP"));
                }
                if (type != BuildType.DESTROY) {
                    // 与目标层差过大时填/削：仅地基层
                    int base = site.targetLevelY();
                    if (ground < base && base - ground <= 3) {
                        for (int y = ground + 1; y <= base; y++) {
                            steps.add(ConstructionStep.place(wx, y, wz, palette.fill, "SITE_PREP"));
                        }
                    } else if (ground > base && ground - base <= 2) {
                        for (int y = base + 1; y <= ground; y++) {
                            steps.add(ConstructionStep.breakBlock(wx, y, wz, "SITE_PREP"));
                        }
                    }
                }
            }
        }

        switch (type) {
            case ROAD -> planRoadNetwork(steps, site, palette, rng);
            case STREETSCAPE -> planStreetscape(steps, site, palette, rng);
            case HOUSE -> planHouse(steps, site, palette, rng, false);
            case UPGRADE_HOUSE -> planHouse(steps, site, palette, rng, true);
            case FARM -> planFarm(steps, site, palette, rng);
            case TRADE_FAIR -> planFair(steps, site, palette, rng);
            case WALL -> planWall(steps, site, palette, rng);
            case LANDSCAPE -> planLandscape(steps, site, palette, rng);
            case DESTROY -> planDestroy(steps, site);
            default -> {
            }
        }
        return steps;
    }

    public static List<ConstructionStep> plan(String type, SiteAssessment site) {
        BuildType bt = BuildType.fromCommand(type);
        return bt == null ? List.of() : plan(bt, site);
    }

    private static int prepRadius(BuildType type) {
        return switch (type) {
            case WALL -> 5;
            case HOUSE, UPGRADE_HOUSE -> 3;
            case FARM -> 3;
            case ROAD, STREETSCAPE -> 1;
            case TRADE_FAIR, LANDSCAPE -> 2;
            case DESTROY -> 2;
            default -> 2;
        };
    }

    private static int yAt(SiteAssessment site, int x, int z) {
        return site.surfaceYAt(x, z);
    }

    private static void planRoadNetwork(List<ConstructionStep> steps, SiteAssessment site,
                                        BiomePalette p, Random rng) {
        int cx = site.centerX();
        int cz = site.centerZ();
        int len = 8 + rng.nextInt(5);
        for (int i = -len; i <= len; i++) {
            placePath(steps, site, cx + i, cz, p, rng);
            placePath(steps, site, cx + i, cz + 1, p, rng);
            placePath(steps, site, cx, cz + i, p, rng);
            placePath(steps, site, cx + 1, cz + i, p, rng);
        }
        // 路口铺装 + 少量破损石
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                Material mat = rng.nextDouble() < 0.18 ? p.weathered : p.pathAccent;
                steps.add(ConstructionStep.place(x, yAt(site, x, z), z, mat, "DETAIL"));
            }
        }
    }

    private static void placePath(List<ConstructionStep> steps, SiteAssessment site,
                                  int x, int z, BiomePalette p, Random rng) {
        int y = yAt(site, x, z);
        Material mat = rng.nextDouble() < 0.12 ? p.weathered : p.path;
        steps.add(ConstructionStep.place(x, y, z, mat, "STRUCTURE"));
    }

    private static void planStreetscape(List<ConstructionStep> steps, SiteAssessment site,
                                        BiomePalette p, Random rng) {
        int cx = site.centerX();
        int cz = site.centerZ();
        int spacing = 3 + rng.nextInt(2);
        for (int i = -8; i <= 8; i += spacing) {
            // 路灯
            int lx = cx + i;
            int lz = cz - 2;
            int y = yAt(site, lx, lz);
            steps.add(ConstructionStep.place(lx, y, lz, p.wall, "STRUCTURE"));
            steps.add(ConstructionStep.place(lx, y + 1, lz, p.wall, "STRUCTURE"));
            steps.add(ConstructionStep.place(lx, y + 2, lz, p.lantern, "DETAIL"));
            // 行道树
            int tx = cx + i;
            int tz = cz + 2;
            int ty = yAt(site, tx, tz);
            steps.add(ConstructionStep.place(tx, ty, tz, p.log, "STRUCTURE"));
            steps.add(ConstructionStep.place(tx, ty + 1, tz, p.leaves, "DETAIL"));
            steps.add(ConstructionStep.place(tx, ty + 2, tz, p.leaves, "DETAIL"));
            if (rng.nextBoolean()) {
                steps.add(ConstructionStep.place(tx + 1, ty + 1, tz, p.leaves, "DETAIL"));
            }
            // 石凳
            int bx = cx + i + 1;
            int bz = cz - 2;
            steps.add(ConstructionStep.place(bx, yAt(site, bx, bz), bz, p.slab, "DETAIL"));
            // 偶发藤蔓/花
            if (rng.nextDouble() < 0.35) {
                steps.add(ConstructionStep.place(lx + 1, y + 1, lz, p.plant, "DETAIL"));
            }
        }
    }

    private static void planWall(List<ConstructionStep> steps, SiteAssessment site,
                                 BiomePalette p, Random rng) {
        int cx = site.centerX();
        int cz = site.centerZ();
        int r = 4 + rng.nextInt(3);
        for (int x = -r; x <= r; x++) {
            placeWallSeg(steps, site, cx + x, cz - r, p, rng);
            placeWallSeg(steps, site, cx + x, cz + r, p, rng);
        }
        for (int z = -r; z <= r; z++) {
            placeWallSeg(steps, site, cx - r, cz + z, p, rng);
            placeWallSeg(steps, site, cx + r, cz + z, p, rng);
        }
    }

    private static void placeWallSeg(List<ConstructionStep> steps, SiteAssessment site,
                                     int x, int z, BiomePalette p, Random rng) {
        int y = yAt(site, x, z);
        int h = 2 + (rng.nextDouble() < 0.2 ? 1 : 0);
        for (int dy = 0; dy <= h; dy++) {
            Material m = rng.nextDouble() < 0.15 ? p.weathered : p.stone;
            steps.add(ConstructionStep.place(x, y + dy, z, m, "STRUCTURE"));
        }
        if (rng.nextBoolean()) {
            steps.add(ConstructionStep.place(x, y + h + 1, z, p.wall, "DETAIL"));
        }
        if (rng.nextDouble() < 0.2) {
            steps.add(ConstructionStep.place(x, y + 1, z + (rng.nextBoolean() ? 1 : -1), p.vineLike, "DETAIL"));
        }
    }

    private static void planFarm(List<ConstructionStep> steps, SiteAssessment site,
                                 BiomePalette p, Random rng) {
        int cx = site.centerX();
        int cz = site.centerZ();
        int half = 2 + rng.nextInt(2);
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                int wx = cx + x;
                int wz = cz + z;
                int y = yAt(site, wx, wz);
                steps.add(ConstructionStep.place(wx, y, wz, Material.FARMLAND, "STRUCTURE"));
                if (rng.nextDouble() < 0.4) {
                    steps.add(ConstructionStep.place(wx, y + 1, wz, p.crop, "DETAIL"));
                }
            }
        }
        steps.add(ConstructionStep.place(cx, yAt(site, cx, cz), cz, Material.WATER, "DETAIL"));
        // 稻草堆生活痕迹
        if (rng.nextBoolean()) {
            steps.add(ConstructionStep.place(cx + half, yAt(site, cx + half, cz) + 1, cz, Material.HAY_BLOCK, "DETAIL"));
        }
    }

    private static void planHouse(List<ConstructionStep> steps, SiteAssessment site,
                                  BiomePalette p, Random rng, boolean upgrade) {
        int cx = site.centerX();
        int cz = site.centerZ();
        int size = upgrade ? (3 + rng.nextInt(2)) : (2 + rng.nextInt(2));
        int wallH = upgrade ? 3 + rng.nextInt(2) : 2 + (rng.nextBoolean() ? 1 : 0);
        Material wall = upgrade ? p.stone : p.planks;
        Material roof = upgrade ? p.roofUpgrade : p.roof;

        // 地基贴地
        for (int x = -size; x <= size; x++) {
            for (int z = -size; z <= size; z++) {
                int wx = cx + x;
                int wz = cz + z;
                int y = yAt(site, wx, wz);
                steps.add(ConstructionStep.place(wx, y, wz, p.foundation, "FOUNDATION"));
            }
        }

        int baseY = yAt(site, cx, cz);

        // 四角立柱
        int[][] corners = {{-size, -size}, {size, -size}, {-size, size}, {size, size}};
        for (int[] c : corners) {
            for (int y = 1; y <= wallH + 1; y++) {
                Material m = rng.nextDouble() < 0.12 ? p.weathered : wall;
                steps.add(ConstructionStep.place(cx + c[0], baseY + y, cz + c[1], m, "STRUCTURE"));
            }
        }

        // 墙体 + 随机窗
        for (int y = 1; y <= wallH; y++) {
            for (int x = -size + 1; x <= size - 1; x++) {
                boolean window = y == Math.max(1, wallH - 1) && Math.abs(x) <= 1 && rng.nextDouble() < 0.7;
                Material m = window ? Material.GLASS_PANE : (rng.nextDouble() < 0.1 ? p.weathered : wall);
                steps.add(ConstructionStep.place(cx + x, baseY + y, cz - size, m, window ? "DETAIL" : "STRUCTURE"));
                steps.add(ConstructionStep.place(cx + x, baseY + y, cz + size, m, window ? "DETAIL" : "STRUCTURE"));
            }
            for (int z = -size + 1; z <= size - 1; z++) {
                boolean door = y == 1 && z == 0;
                if (door) {
                    // 门洞
                    continue;
                }
                boolean window = y == Math.max(1, wallH - 1) && z == 0 && rng.nextDouble() < 0.5;
                Material m = window ? Material.GLASS_PANE : wall;
                steps.add(ConstructionStep.place(cx - size, baseY + y, cz + z, m, window ? "DETAIL" : "STRUCTURE"));
                steps.add(ConstructionStep.place(cx + size, baseY + y, cz + z, m, window ? "DETAIL" : "STRUCTURE"));
            }
        }

        // 屋顶（略不规则）
        for (int x = -size; x <= size; x++) {
            for (int z = -size; z <= size; z++) {
                int ry = baseY + wallH + 1;
                if (Math.abs(x) == size || Math.abs(z) == size) {
                    steps.add(ConstructionStep.place(cx + x, ry, cz + z, roof, "STRUCTURE"));
                } else if (rng.nextDouble() < 0.85) {
                    steps.add(ConstructionStep.place(cx + x, ry, cz + z, roof, "STRUCTURE"));
                }
            }
        }

        // 生活化陈设
        steps.add(ConstructionStep.place(cx, baseY + 1, cz - size + 1, Material.CRAFTING_TABLE, "DETAIL"));
        if (upgrade || rng.nextDouble() < 0.6) {
            steps.add(ConstructionStep.place(cx + 1, baseY + 1, cz - size + 1, Material.CHEST, "DETAIL"));
        }
        if (rng.nextDouble() < 0.7) {
            steps.add(ConstructionStep.place(cx - 1, baseY + 1, cz + size - 1, p.bed, "DETAIL"));
        }
        steps.add(ConstructionStep.place(cx, baseY + 2, cz, p.lantern, "DETAIL"));
        // 门前台阶 / 围栏
        steps.add(ConstructionStep.place(cx, baseY, cz - size - 1, p.slab, "DETAIL"));
        if (rng.nextDouble() < 0.5) {
            steps.add(ConstructionStep.place(cx - 1, baseY + 1, cz - size - 1, p.fence, "DETAIL"));
            steps.add(ConstructionStep.place(cx + 1, baseY + 1, cz - size - 1, p.fence, "DETAIL"));
        }
        // 藤蔓/花箱
        if (rng.nextDouble() < 0.45) {
            steps.add(ConstructionStep.place(cx + size, baseY + 2, cz, p.vineLike, "DETAIL"));
        }
        if (rng.nextDouble() < 0.4) {
            steps.add(ConstructionStep.place(cx - size - 1, baseY + 1, cz, p.plant, "DETAIL"));
        }
    }

    private static void planFair(List<ConstructionStep> steps, SiteAssessment site,
                                 BiomePalette p, Random rng) {
        int cx = site.centerX();
        int cz = site.centerZ();
        for (int i = 0; i < 4; i++) {
            int x = cx + i * 2 - 3;
            int y = yAt(site, x, cz);
            steps.add(ConstructionStep.place(x, y, cz, Material.HAY_BLOCK, "STRUCTURE"));
            if (rng.nextBoolean()) {
                steps.add(ConstructionStep.place(x, y + 1, cz, p.lantern, "DETAIL"));
            }
        }
        steps.add(ConstructionStep.place(cx, yAt(site, cx, cz) + 1, cz, p.lantern, "DETAIL"));
    }

    private static void planLandscape(List<ConstructionStep> steps, SiteAssessment site,
                                      BiomePalette p, Random rng) {
        int cx = site.centerX();
        int cz = site.centerZ();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                int wx = cx + x;
                int wz = cz + z;
                int y = yAt(site, wx, wz);
                if (Math.abs(x) == 2 || Math.abs(z) == 2) {
                    steps.add(ConstructionStep.place(wx, y, wz, p.grass, "STRUCTURE"));
                    if (rng.nextDouble() < 0.45) {
                        steps.add(ConstructionStep.place(wx, y + 1, wz, p.plant, "DETAIL"));
                    }
                }
            }
        }
        int cy = yAt(site, cx, cz);
        steps.add(ConstructionStep.place(cx, cy, cz, p.stone, "FOUNDATION"));
        steps.add(ConstructionStep.place(cx, cy + 1, cz, Material.WATER, "DETAIL"));
        steps.add(ConstructionStep.place(cx, cy + 2, cz, p.lantern, "DETAIL"));
        // 长椅
        steps.add(ConstructionStep.place(cx + 2, yAt(site, cx + 2, cz), cz, p.slab, "DETAIL"));
    }

    private static void planDestroy(List<ConstructionStep> steps, SiteAssessment site) {
        int cx = site.centerX();
        int cz = site.centerZ();
        int r = 2;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int y = yAt(site, cx + x, cz + z);
                for (int dy = 0; dy <= 3; dy++) {
                    steps.add(ConstructionStep.breakBlock(cx + x, y + dy, cz + z, "SITE_PREP"));
                }
            }
        }
    }

    /** 生物群系驱动的材质板 + 轻微随机。 */
    private static final class BiomePalette {
        final Material path;
        final Material pathAccent;
        final Material planks;
        final Material log;
        final Material leaves;
        final Material stone;
        final Material foundation;
        final Material roof;
        final Material roofUpgrade;
        final Material wall;
        final Material slab;
        final Material fence;
        final Material lantern;
        final Material plant;
        final Material crop;
        final Material grass;
        final Material fill;
        final Material weathered;
        final Material vineLike;
        final Material bed;

        private BiomePalette(Material path, Material pathAccent, Material planks, Material log, Material leaves,
                             Material stone, Material foundation, Material roof, Material roofUpgrade,
                             Material wall, Material slab, Material fence, Material lantern, Material plant,
                             Material crop, Material grass, Material fill, Material weathered,
                             Material vineLike, Material bed) {
            this.path = path;
            this.pathAccent = pathAccent;
            this.planks = planks;
            this.log = log;
            this.leaves = leaves;
            this.stone = stone;
            this.foundation = foundation;
            this.roof = roof;
            this.roofUpgrade = roofUpgrade;
            this.wall = wall;
            this.slab = slab;
            this.fence = fence;
            this.lantern = lantern;
            this.plant = plant;
            this.crop = crop;
            this.grass = grass;
            this.fill = fill;
            this.weathered = weathered;
            this.vineLike = vineLike;
            this.bed = bed;
        }

        static BiomePalette of(String biome, String dominant, Random rng) {
            String b = biome == null ? "" : biome.toLowerCase();
            boolean cold = b.contains("snow") || b.contains("ice") || b.contains("frozen") || b.contains("taiga");
            boolean desert = b.contains("desert") || b.contains("badlands");
            boolean dark = b.contains("dark_forest") || b.contains("swamp");
            boolean savanna = b.contains("savanna");

            Material planks = Material.OAK_PLANKS;
            Material log = Material.OAK_LOG;
            Material leaves = Material.OAK_LEAVES;
            Material path = Material.DIRT_PATH;
            Material stone = Material.COBBLESTONE;
            Material roof = Material.OAK_STAIRS;
            Material plant = Material.POPPY;
            Material weathered = Material.MOSSY_COBBLESTONE;

            if (cold) {
                planks = Material.SPRUCE_PLANKS;
                log = Material.SPRUCE_LOG;
                leaves = Material.SPRUCE_LEAVES;
                stone = Material.STONE_BRICKS;
                roof = Material.SPRUCE_STAIRS;
                plant = Material.FERN;
                weathered = Material.ANDESITE;
            } else if (desert) {
                planks = Material.SANDSTONE;
                log = Material.ACACIA_LOG;
                leaves = Material.ACACIA_LEAVES;
                path = Material.SMOOTH_SANDSTONE;
                stone = Material.CUT_SANDSTONE;
                roof = Material.SANDSTONE_STAIRS;
                plant = Material.DEAD_BUSH;
                weathered = Material.SANDSTONE;
            } else if (dark) {
                planks = Material.DARK_OAK_PLANKS;
                log = Material.DARK_OAK_LOG;
                leaves = Material.DARK_OAK_LEAVES;
                roof = Material.DARK_OAK_STAIRS;
                plant = Material.RED_MUSHROOM;
                weathered = Material.MOSSY_STONE_BRICKS;
            } else if (savanna) {
                planks = Material.ACACIA_PLANKS;
                log = Material.ACACIA_LOG;
                leaves = Material.ACACIA_LEAVES;
                roof = Material.ACACIA_STAIRS;
                plant = Material.SHORT_GRASS;
            } else if (rng.nextDouble() < 0.25) {
                planks = Material.BIRCH_PLANKS;
                log = Material.BIRCH_LOG;
                leaves = Material.BIRCH_LEAVES;
                roof = Material.BIRCH_STAIRS;
            }

            // 主导地表微调填方
            Material fill = Material.DIRT;
            if (dominant != null) {
                if (dominant.contains("SAND")) {
                    fill = Material.SAND;
                } else if (dominant.contains("STONE")) {
                    fill = Material.COBBLESTONE;
                } else if (dominant.contains("SNOW")) {
                    fill = Material.SNOW_BLOCK;
                }
            }

            return new BiomePalette(
                    path, Material.COBBLESTONE, planks, log, leaves, stone,
                    Material.STONE_BRICKS, roof, Material.DEEPSLATE_BRICKS,
                    Material.COBBLESTONE_WALL, Material.STONE_BRICK_SLAB,
                    Material.OAK_FENCE, Material.LANTERN, plant,
                    Material.WHEAT, Material.GRASS_BLOCK, fill, weathered,
                    Material.VINE, Material.RED_BED
            );
        }
    }
}
