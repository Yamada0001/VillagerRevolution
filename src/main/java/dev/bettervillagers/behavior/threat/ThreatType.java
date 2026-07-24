package dev.bettervillagers.behavior.threat;

/** 威胁类型（规范 3.1 威胁识别）。 */
public enum ThreatType {
    HOSTILE_MOB,   // 敌对生物（僵尸/骷髅/掠夺者等）
    ILLEGAL_PLAYER,// 攻击村民的玩家
    ENVIRONMENT    // 环境危险（岩浆/悬崖/火焰）
}
