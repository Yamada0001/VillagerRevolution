package dev.bettervillagers.trade;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.bettervillagers.BV;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.villager.BVillager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 自主交易与动态定价（规范 3.4 / 4.4 交易价格缓存）。
 * <p>
 * 价格由供需 + 随机扰动生成并缓存；AI 公平性判断可拒绝明显不公交易。
 */
public final class TradeService {

    private final Cache<String, Double> priceCache;
    private final int quantizeStep;

    public TradeService(long ttlSeconds, int maxSize, int quantizeStep) {
        this.quantizeStep = Math.max(1, quantizeStep);
        this.priceCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(Math.max(1, ttlSeconds)))
                .maximumSize(Math.max(16, maxSize))
                .build();
    }

    /** 生成（或取缓存）某物品的动态价格。 */
    public double priceOf(Material material, int supply, int demand) {
        supply = Math.max(0, supply);
        demand = Math.max(0, demand);
        // 缓存键需包含供需分段量化，避免不同供需命中同一缓存价
        String key = material.name() + ":s" + (supply / quantizeStep) + ":d" + (demand / quantizeStep);
        Double cached = priceCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        double base = Math.max(1, material.getMaxStackSize() / 8.0);
        double factor = 1.0 + (demand - supply) * 0.1;
        double price = Math.max(0.5, base * factor * (0.9 + ThreadLocalRandom.current().nextDouble(0.2)));
        priceCache.put(key, price);
        return price;
    }

    /**
     * 为村民生成基于职业的丰富交易列表（问题6：含附魔装备/武器/工具）。
     * <p>
     * 读取 professions.yml 中该职业的 trades 定义，生成带附魔的 MerchantRecipe。
     * 价格在 min-price/max-price 区间随机；每次调用刷新可用库存与价格。
     * 返回可直接设置到 {@link org.bukkit.entity.Villager#setRecipes(List)} 的列表。
     */
    public List<MerchantRecipe> generateOffers(BVillager merchant) {
        return generateOffers(merchant, 0.0);
    }

    public List<MerchantRecipe> generateOffers(BVillager merchant, double discount) {
        discount = Math.clamp(discount, 0.0, 0.5);
        List<MerchantRecipe> recipes = new ArrayList<>();
        if (merchant == null || merchant.profession() == null) {
            return recipes;
        }
        Profession prof = merchant.profession();
        List<TradeOffer> defs = BV.professions().tradesOf(prof);
        for (TradeOffer def : defs) {
            ItemStack result = BV.professions().buildTradeItem(def);
            int basePrice = def.minPrice() + (def.maxPrice() > def.minPrice()
                    ? ThreadLocalRandom.current().nextInt(def.maxPrice() - def.minPrice() + 1)
                    : 0);
            // 暂无全局成交统计时需求取 0；基础价不应伪装成需求量参与市场公式。
            int marketPrice = Math.max(basePrice, (int) Math.round(priceOf(result.getType(), def.amount(), 0)));
            int price = Math.max(1, (int) Math.floor(marketPrice * (1.0 - discount)));
            // 随机库存（每次重新生成时刷新）
            int maxUses = 8 + ThreadLocalRandom.current().nextInt(8);
            MerchantRecipe recipe = new MerchantRecipe(result, 0, maxUses, true);
            recipe.setExperienceReward(true); // 确保交易给经验（允许村民升级）
            // 修复问题3：村民交易后获得经验值，支持原版升级机制
            recipe.setVillagerExperience(2);
            recipe.addIngredient(new ItemStack(Material.EMERALD, price));
            // 部分高价商品额外需要第二种材料（提升丰富度）
            if (price >= 8 && ThreadLocalRandom.current().nextBoolean()) {
                recipe.addIngredient(new ItemStack(pickSecondary(prof), 1 + ThreadLocalRandom.current().nextInt(2)));
            }
            recipes.add(recipe);
        }
        return recipes;
    }

    /** 选取该职业的第二交易材料（提升交易丰富度）。 */
    private Material pickSecondary(Profession prof) {
        return switch (prof) {
            case KNIGHT, SOLDIER -> ThreadLocalRandom.current().nextBoolean() ? Material.IRON_INGOT : Material.GOLD_INGOT;
            case ARCHER, BUILDER -> Material.STICK;
            case BUTCHER, CHEF, MINER -> Material.COAL;
            case FARMER -> Material.WHEAT;
            case MERCHANT -> Material.GOLD_INGOT;
            default -> Material.DIRT; // 兜底非 AIR，避免空原料异常
        };
    }
}
