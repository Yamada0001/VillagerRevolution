package dev.bettervillagers.building;

/**
 * Terrain assessment used by the building planner.
 */
final class SiteAssessment {

    private final int centerX;
    private final int centerZ;
    private final int targetLevelY;
    private final double complexity;
    private final double modificationEase;
    private final double avgSlope;
    private final double obstacleDensity;
    private final String summary;
    private final String biomeKey;
    private final int waterColumns;
    private final int lavaColumns;
    private final long seed;
    private final int mapSize;

    SiteAssessment(int centerX, int centerZ, int targetLevelY,
                   double complexity, double modificationEase,
                   double avgSlope, double obstacleDensity,
                   String summary, String biomeKey,
                   int waterColumns, int lavaColumns, long seed, int mapSize) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.targetLevelY = targetLevelY;
        this.complexity = complexity;
        this.modificationEase = modificationEase;
        this.avgSlope = avgSlope;
        this.obstacleDensity = obstacleDensity;
        this.summary = summary;
        this.biomeKey = biomeKey == null ? "plains" : biomeKey;
        this.waterColumns = waterColumns;
        this.lavaColumns = lavaColumns;
        this.seed = seed;
        this.mapSize = mapSize;
    }

    int centerX() {
        return centerX;
    }

    int centerZ() {
        return centerZ;
    }

    int targetLevelY() {
        return targetLevelY;
    }

    double complexity() {
        return complexity;
    }

    double modificationEase() {
        return modificationEase;
    }

    String summary() {
        return summary;
    }

    String biomeKey() {
        return biomeKey;
    }

    long seed() {
        return seed;
    }

    boolean suitable() {
        int cells = Math.max(1, mapSize * mapSize);
        return lavaColumns == 0
                && waterColumns <= cells * 0.35
                && avgSlope <= 3.5
                && (obstacleDensity <= 0.55 || modificationEase >= 0.3)
                && complexity < 0.88
                && modificationEase > 0.18;
    }
}
