package dev.bettervillagers.building;

/** 模板方块实体的安全应用策略。原始 NBT 永不直接写入世界。 */
enum BlockEntityPolicy {
    NONE,
    CLEAR_INVENTORY,
    CLEAR_SIGN
}
