package dev.bettervillagers.behavior;

/** 村民行为有限状态机（规范 3.1：空闲/工作/战斗/逃跑/休息/交易/社交）。 */
public enum VillagerState {
    IDLE,        // 空闲
    WORKING,     // 工作
    COMBAT,      // 战斗
    FLEEING,     // 逃跑
    RESTING,     // 休息
    TRADING,     // 交易
    PATROLING,   // 巡逻（军事职业专属）
    SOCIALIZING  // 社交攀谈（跨职业相遇触发）
}
