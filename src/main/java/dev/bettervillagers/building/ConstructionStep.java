package dev.bettervillagers.building;

import org.bukkit.Material;

/**
 * 单步施工指令（禁止瞬间生成整栋建筑，由非战斗村民分阶段执行）。
 */
public final class ConstructionStep {

    public enum Kind { PLACE, BREAK }

    private final Kind kind;
    private final int x;
    private final int y;
    private final int z;
    private final Material material;
    private final String phase;

    public ConstructionStep(Kind kind, int x, int y, int z, Material material, String phase) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.z = z;
        this.material = material;
        this.phase = phase;
    }

    public static ConstructionStep place(int x, int y, int z, Material mat, String phase) {
        return new ConstructionStep(Kind.PLACE, x, y, z, mat, phase);
    }

    public static ConstructionStep breakBlock(int x, int y, int z, String phase) {
        return new ConstructionStep(Kind.BREAK, x, y, z, Material.AIR, phase);
    }

    public Kind kind() {
        return kind;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public Material material() {
        return material;
    }

    public String phase() {
        return phase;
    }
}
