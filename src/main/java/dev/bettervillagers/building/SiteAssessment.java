package dev.bettervillagers.building;

/**
 * 场地三维综合评估结果（异步分析输出）。
 * <p>
 * 量化：复杂度、改造简易度、平均/目标地面高度、坡度、障碍密度、柏林粗糙度、
 * 水域/熔岩风险、生物群系与主导地表材质（环境融合）。
 */
public final class SiteAssessment {

    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final int targetLevelY;
    private final double complexity;
    private final double modificationEase;
    private final double avgSlope;
    private final double obstacleDensity;
    private final double perlinRoughness;
    private final int obstacleCount;
    private final int fillBlocks;
    private final int digBlocks;
    private final String summary;
    private final String biomeKey;
    private final String dominantSurface;
    private final int waterColumns;
    private final int lavaColumns;
    private final long seed;
    private final int[] heightMap;
    private final int mapSize;
    private final int originX;
    private final int originZ;

    public SiteAssessment(int centerX, int centerY, int centerZ, int targetLevelY,
                          double complexity, double modificationEase, double avgSlope,
                          double obstacleDensity, double perlinRoughness,
                          int obstacleCount, int fillBlocks, int digBlocks, String summary,
                          String biomeKey, String dominantSurface, int waterColumns, int lavaColumns,
                          long seed, int[] heightMap, int mapSize, int originX, int originZ) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.targetLevelY = targetLevelY;
        this.complexity = complexity;
        this.modificationEase = modificationEase;
        this.avgSlope = avgSlope;
        this.obstacleDensity = obstacleDensity;
        this.perlinRoughness = perlinRoughness;
        this.obstacleCount = obstacleCount;
        this.fillBlocks = fillBlocks;
        this.digBlocks = digBlocks;
        this.summary = summary;
        this.biomeKey = biomeKey == null ? "plains" : biomeKey;
        this.dominantSurface = dominantSurface == null ? "GRASS_BLOCK" : dominantSurface;
        this.waterColumns = waterColumns;
        this.lavaColumns = lavaColumns;
        this.seed = seed;
        this.heightMap = heightMap;
        this.mapSize = mapSize;
        this.originX = originX;
        this.originZ = originZ;
    }

    public int centerX() {
        return centerX;
    }

    public int centerY() {
        return centerY;
    }

    public int centerZ() {
        return centerZ;
    }

    public int targetLevelY() {
        return targetLevelY;
    }

    public double complexity() {
        return complexity;
    }

    public double modificationEase() {
        return modificationEase;
    }

    public double avgSlope() {
        return avgSlope;
    }

    public double obstacleDensity() {
        return obstacleDensity;
    }

    public double perlinRoughness() {
        return perlinRoughness;
    }

    public int obstacleCount() {
        return obstacleCount;
    }

    public int fillBlocks() {
        return fillBlocks;
    }

    public int digBlocks() {
        return digBlocks;
    }

    public String summary() {
        return summary;
    }

    public String biomeKey() {
        return biomeKey;
    }

    public String dominantSurface() {
        return dominantSurface;
    }

    public int waterColumns() {
        return waterColumns;
    }

    public int lavaColumns() {
        return lavaColumns;
    }

    public long seed() {
        return seed;
    }

    /**
     * 查询某世界坐标列的地表高度（来自快照高度图）；越界则返回 targetLevelY。
     */
    public int surfaceYAt(int worldX, int worldZ) {
        if (heightMap == null || mapSize <= 0) {
            return targetLevelY;
        }
        int lx = worldX - originX;
        int lz = worldZ - originZ;
        if (lx < 0 || lz < 0 || lx >= mapSize || lz >= mapSize) {
            return targetLevelY;
        }
        return heightMap[lx + lz * mapSize];
    }

    /**
     * 是否适合直接建造。
     * 拒绝：熔岩、大片水域、极陡坡、高障碍密度、改造过难。
     */
    public boolean suitable() {
        if (lavaColumns > 0) {
            return false;
        }
        int cells = Math.max(1, mapSize * mapSize);
        if (waterColumns > cells * 0.35) {
            return false;
        }
        if (avgSlope > 3.5) {
            return false;
        }
        if (obstacleDensity > 0.55 && modificationEase < 0.3) {
            return false;
        }
        return complexity < 0.88 && modificationEase > 0.18;
    }
}
