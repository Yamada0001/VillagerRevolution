package dev.bettervillagers.profession;

import dev.bettervillagers.trade.TradeOffer;

import java.util.List;
import java.util.Map;

/**
 * 职业完整数据（规范 2.x / 6.3 professions.yml）。
 * <p>
 * 不可变；外观（装备）与战斗属性解耦（规范 2.3：外观与属性分离存储）。
 */
public record ProfessionData(
        Profession profession,
        boolean enabled,
        int maxPerVillage,
        int spawnWeight,
        double inheritRate,
        Stats stats,
        Map<String, EquipmentSpec> equipment,
        double durabilityLossRate,
        Map<String, Double> behaviorWeights,
        Personality personality,
        List<String> blockWhitelist,
        List<TradeOffer> trades
) {
    /** 战斗属性数值层。 */
    public record Stats(double health, double attack, Defense defense) {
    }

    /** 性格参数（影响 AI prompt 偏置，规范 3.1）。 */
    public record Personality(double bravery, double greed) {
    }
}
