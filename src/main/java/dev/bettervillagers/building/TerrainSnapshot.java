package dev.bettervillagers.building;

import org.bukkit.Material;

/**
 * 场地只读快照（区域线程采集 → 异步线程分析）。
 * <p>
 * 不持有任何 World/Block 引用，保证跨线程安全；世界生成逻辑不受影响。
 */
public final class TerrainSnapshot {

    private final String worldName;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int sizeX;
    private final int sizeZ;
    /** 列地表高度（相对 origin，按 x + z * sizeX 索引）。 */
    private final int[] surfaceY;
    /** 列顶部方块类型。 */
    private final Material[] surfaceMat;
    /** 列上方 1~3 格阻挡（空气=false）。 */
    private final boolean[] blocked;
    private final long seed;
    private final String biomeKey;

    public TerrainSnapshot(String worldName, int originX, int originY, int originZ,
                           int sizeX, int sizeZ, int[] surfaceY, Material[] surfaceMat,
                           boolean[] blocked, long seed) {
        this(worldName, originX, originY, originZ, sizeX, sizeZ, surfaceY, surfaceMat, blocked, seed, "plains");
    }

    public TerrainSnapshot(String worldName, int originX, int originY, int originZ,
                           int sizeX, int sizeZ, int[] surfaceY, Material[] surfaceMat,
                           boolean[] blocked, long seed, String biomeKey) {
        this.worldName = worldName;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.surfaceY = surfaceY;
        this.surfaceMat = surfaceMat;
        this.blocked = blocked;
        this.seed = seed;
        this.biomeKey = biomeKey == null ? "plains" : biomeKey;
    }

    public String biomeKey() {
        return biomeKey;
    }

    public String worldName() {
        return worldName;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public long seed() {
        return seed;
    }

    public int index(int lx, int lz) {
        return lx + lz * sizeX;
    }

    public int surfaceY(int lx, int lz) {
        return surfaceY[index(lx, lz)];
    }

    public Material surfaceMat(int lx, int lz) {
        return surfaceMat[index(lx, lz)];
    }

    public boolean blocked(int lx, int lz) {
        return blocked[index(lx, lz)];
    }

    public int worldX(int lx) {
        return originX + lx;
    }

    public int worldZ(int lz) {
        return originZ + lz;
    }
}
