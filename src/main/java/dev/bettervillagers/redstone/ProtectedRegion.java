package dev.bettervillagers.redstone;

/** 生电保护区（规范 5.x / 8.2 protected_regions 表）。 */
public record ProtectedRegion(
        int id,
        String name,
        String world,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        String owner
) {
    /** 是否包含指定坐标。 */
    public boolean contains(String worldName, int x, int y, int z) {
        return world.equalsIgnoreCase(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }
}
