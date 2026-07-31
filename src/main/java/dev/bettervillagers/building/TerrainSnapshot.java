package dev.bettervillagers.building;

import org.bukkit.Material;

/**
 * Immutable terrain sample captured on a region thread and analyzed off-thread.
 */
final class TerrainSnapshot {

    private final int originX;
    private final int originZ;
    private final int sizeX;
    private final int sizeZ;
    private final int[] surfaceY;
    private final Material[] surfaceMat;
    private final boolean[] blocked;
    private final long seed;
    private final String biomeKey;

    TerrainSnapshot(int originX, int originZ, int sizeX, int sizeZ,
                    int[] surfaceY, Material[] surfaceMat, boolean[] blocked,
                    long seed, String biomeKey) {
        this.originX = originX;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.surfaceY = surfaceY;
        this.surfaceMat = surfaceMat;
        this.blocked = blocked;
        this.seed = seed;
        this.biomeKey = biomeKey == null ? "plains" : biomeKey;
    }

    String biomeKey() {
        return biomeKey;
    }

    int originX() {
        return originX;
    }

    int originZ() {
        return originZ;
    }

    int sizeX() {
        return sizeX;
    }

    int sizeZ() {
        return sizeZ;
    }

    long seed() {
        return seed;
    }

    int surfaceY(int lx, int lz) {
        return surfaceY[index(lx, lz)];
    }

    Material surfaceMat(int lx, int lz) {
        return surfaceMat[index(lx, lz)];
    }

    boolean blocked(int lx, int lz) {
        return blocked[index(lx, lz)];
    }

    int worldX(int lx) {
        return originX + lx;
    }

    int worldZ(int lz) {
        return originZ + lz;
    }

    private int index(int lx, int lz) {
        return lx + lz * sizeX;
    }
}
