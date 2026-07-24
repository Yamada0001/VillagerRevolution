package dev.bettervillagers.trade;

import java.util.List;

/**
 * 单个交易商品定义（问题6：丰富各职业商品，支持附魔）。
 * <p>
 * 由 professions.yml 的 trades 节解析，不可变。
 */
public record TradeOffer(
        String material,           // 物品材质名（Material）
        int amount,                // 出售数量
        int minPrice,              // 最低价格（绿宝石）
        int maxPrice,              // 最高价格
        List<EnchantSpec> enchants // 附魔列表（可为空）
) {
    /** 附魔规格。 */
    public record EnchantSpec(String name, int level, boolean treasure) {
    }

    /** 空附魔列表常量。 */
    public static List<EnchantSpec> noEnchants() {
        return List.of();
    }
}
