package dev.bettervillagers.building;

import org.bukkit.Material;

/**
 * 单步施工指令（禁止瞬间生成整栋建筑，由非战斗村民分阶段执行）。
 */
record ConstructionStep(Kind kind, int x, int y, int z, Material material,
                        String blockData, BlockEntityPolicy blockEntityPolicy, String phase) {

    enum Kind { PLACE, BREAK }

    static ConstructionStep place(int x, int y, int z, Material mat, String phase) {
        return new ConstructionStep(Kind.PLACE, x, y, z, mat, null, BlockEntityPolicy.NONE, phase);
    }

    static ConstructionStep place(int x, int y, int z, Material mat, String blockData,
                                  BlockEntityPolicy policy, String phase) {
        return new ConstructionStep(Kind.PLACE, x, y, z, mat, blockData, policy, phase);
    }

}
