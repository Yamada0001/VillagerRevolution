package dev.bettervillagers.village;

/** 村庄（规范 8.2 villages 表）。 */
public record Village(
        int id,
        String world,
        int centerX,
        int centerY,
        int centerZ,
        int radius,
        String kingUuid,
        int population,
        String name
) {
    /** 指定坐标是否落在村庄半径内（规范 2.2：三维球形覆盖）。 */
    public boolean covers(String worldName, int x, int y, int z) {
        if (!world.equalsIgnoreCase(worldName)) {
            return false;
        }
        double dx = x - centerX;
        double dy = y - centerY;
        double dz = z - centerZ;
        return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
    }
}
