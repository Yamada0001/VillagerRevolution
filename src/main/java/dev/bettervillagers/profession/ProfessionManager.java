package dev.bettervillagers.profession;

import dev.bettervillagers.trade.TradeOffer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 职业管理器（规范 2.1 / 2.2 / 2.3）。
 * <p>
 * 负责加载 {@code professions.yml}、提供职业数据、加权随机分配（含国王约束）
 * 与装备/属性应用（外观与数值层分离）。
 */
public final class ProfessionManager {

    private final Plugin plugin;
    private final Map<Profession, ProfessionData> data = new EnumMap<>(Profession.class);
    private final Map<String, EquipmentSlot> slotMap = new HashMap<>();

    public ProfessionManager(Plugin plugin) {
        this.plugin = plugin;
        // 装备槽位映射
        slotMap.put("main-hand", EquipmentSlot.HAND);
        slotMap.put("off-hand", EquipmentSlot.OFF_HAND);
        slotMap.put("helmet", EquipmentSlot.HEAD);
        slotMap.put("chestplate", EquipmentSlot.CHEST);
        slotMap.put("leggings", EquipmentSlot.LEGS);
        slotMap.put("boots", EquipmentSlot.FEET);
        load();
    }

    /** 加载（或重载）professions.yml。 */
    public void load() {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "professions.yml");
        if (!file.exists()) {
            plugin.saveResource("professions.yml", true);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        // 规范 6.x：检测 professions.yml 版本
        dev.bettervillagers.config.Ver.check("professions.yml", cfg, dev.bettervillagers.config.Ver.PROFESSIONS);
        data.clear();
        ConfigurationSection root = cfg.getConfigurationSection("professions");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            Profession prof = Profession.parse(key);
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            ProfessionData pd = parse(prof, s);
            data.put(prof, pd);
        }
    }

    private ProfessionData parse(Profession prof, ConfigurationSection s) {
        ConfigurationSection stats = s.getConfigurationSection("stats");
        double health = stats != null ? stats.getDouble("health", 40) : 40;
        double attack = stats != null ? stats.getDouble("attack", 5) : 5;
        Defense defense = Defense.parse(stats != null ? stats.getString("defense", "LOW") : "LOW");

        Map<String, EquipmentSpec> equipment = new HashMap<>();
        ConfigurationSection equipSec = s.getConfigurationSection("equipment");
        if (equipSec != null) {
            for (String slot : equipSec.getKeys(false)) {
                Object val = equipSec.get(slot);
                EquipmentSpec spec = parseEquipment(val);
                if (spec != null) {
                    equipment.put(slot, spec);
                }
            }
        }

        Map<String, Double> weights = new HashMap<>();
        ConfigurationSection bw = s.getConfigurationSection("behavior-weights");
        if (bw != null) {
            for (String k : bw.getKeys(false)) {
                weights.put(k, bw.getDouble(k));
            }
        }

        double bravery = 0.3, greed = 0.3;
        ConfigurationSection pers = s.getConfigurationSection("personality");
        if (pers != null) {
            bravery = pers.getDouble("bravery", 0.3);
            greed = pers.getDouble("greed", 0.3);
        }

        List<String> whitelist = new ArrayList<>(s.getStringList("block-whitelist"));

        List<TradeOffer> trades = parseTrades(s.getMapList("trades"));

        return new ProfessionData(
                prof,
                s.getBoolean("enabled", true),
                s.getInt("max-per-village", 0),
                s.getInt("spawn-weight", prof == Profession.CIVILIAN ? 40 : 0),
                s.getDouble("inherit-rate", 0.0),
                new ProfessionData.Stats(health, attack, defense),
                Map.copyOf(equipment),
                s.getDouble("durability-loss-rate", 1.0),
                Map.copyOf(weights),
                new ProfessionData.Personality(bravery, greed),
                List.copyOf(whitelist),
                List.copyOf(trades)
        );
    }

    /** 解析 trades 列表（问题6：丰富职业商品，支持附魔）。 */
    private List<TradeOffer> parseTrades(List<Map<?, ?>> rawList) {
        List<TradeOffer> trades = new ArrayList<>();
        if (rawList == null || rawList.isEmpty()) {
            return trades;
        }
        for (Map<?, ?> m : rawList) {
            String material = String.valueOf(m.get("material"));
            Material mat = Material.matchMaterial(material);
            if (mat == null) {
                continue;
            }
            int amount = m.get("amount") instanceof Number n ? n.intValue() : 1;
            int minPrice = m.get("min-price") instanceof Number n2 ? n2.intValue() : 1;
            int maxPrice = m.get("max-price") instanceof Number n3 ? n3.intValue() : minPrice + 4;
            List<TradeOffer.EnchantSpec> enchants = new ArrayList<>();
            Object enchObj = m.get("enchants");
            if (enchObj instanceof List<?> list) {
                for (Object e : list) {
                    if (e instanceof Map<?, ?> em) {
                        String name = String.valueOf(em.get("name"));
                        int level = em.get("level") instanceof Number ln ? ln.intValue() : 1;
                        boolean treasure = em.get("treasure") instanceof Boolean tb && tb;
                        enchants.add(new TradeOffer.EnchantSpec(name, level, treasure));
                    }
                }
            }
            trades.add(new TradeOffer(material, Math.max(1, amount), Math.max(1, minPrice),
                    Math.max(minPrice, maxPrice), List.copyOf(enchants)));
        }
        return trades;
    }

    private EquipmentSpec parseEquipment(Object val) {
        if (val instanceof String str) {
            return EquipmentSpec.parse(str);
        }
        if (val instanceof Map<?, ?> m) {
            String mat = String.valueOf(m.get("material"));
            Object cmd = m.get("custom-model-data");
            Material material = Material.matchMaterial(mat);
            if (material == null) {
                return null;
            }
            Integer cmdVal = (cmd instanceof Number n) ? n.intValue() : null;
            // 皮革染色（规范 2.3）：支持 "0xRRGGBB" / "#RRGGBB" / 十进制
            org.bukkit.Color color = null;
            Object colorObj = m.get("color");
            if (colorObj != null) {
                try {
                    color = org.bukkit.Color.fromRGB(Integer.decode(String.valueOf(colorObj)));
                } catch (Exception ignored) {
                    // 忽略非法颜色值
                }
            }
            return new EquipmentSpec(material, cmdVal, color);
        }
        return null;
    }

    public ProfessionData data(Profession p) {
        ProfessionData d = data.get(p);
        return d != null ? d : data.get(Profession.CIVILIAN);
    }

    /** 是否允许该方块被该职业操作（白名单匹配，支持正则）。 */
    public boolean isWhitelisted(Profession p, Material material) {
        ProfessionData d = data.get(p);
        if (d == null || d.blockWhitelist().isEmpty()) {
            return false;
        }
        String name = material.name();
        return d.blockWhitelist().stream().anyMatch(pattern -> name.equals(pattern) || name.matches(pattern));
    }

    /**
     * 加权随机分配职业（规范 2.2）。
     * 国王约束优先；否则在 spawnWeight>0 的启用职业中加权抽取。
     */
    public Profession allocate(boolean kingPresent, int population, int kingThreshold) {
        return allocate(kingPresent, population, kingThreshold, null);
    }

    /**
     * 加权随机分配职业（规范 2.2 配额降权）。
     * <p>
     * 国王约束优先；否则在 spawnWeight>0 的启用职业中加权抽取。
     * 当传入 villageCounts 时，已达到 max-per-village 的职业被排除，
     * 未满配额的职业按剩余比例线性降权。
     *
     * @param villageCounts 各职业在当前村庄的已有数量，可为 null（忽略配额）
     */
    public Profession allocate(boolean kingPresent, int population, int kingThreshold,
                               java.util.Map<Profession, Integer> villageCounts) {
        // 国王约束
        ProfessionData king = data.get(Profession.KING);
        if (!kingPresent && king != null && king.enabled()
                && population >= kingThreshold && king.maxPerVillage() > 0) {
            return Profession.KING;
        }
        // 构建候选池（配额降权）
        List<Profession> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (Map.Entry<Profession, ProfessionData> e : data.entrySet()) {
            ProfessionData d = e.getValue();
            if (!d.enabled() || d.spawnWeight() <= 0) {
                continue;
            }
            int count = villageCounts != null ? villageCounts.getOrDefault(e.getKey(), 0) : 0;
            int cap = d.maxPerVillage();
            if (cap > 0 && count >= cap) {
                continue; // 配额已满
            }
            double w = d.spawnWeight();
            if (cap > 0) {
                w = w * (1.0 - (double) count / cap); // 线性降权
            }
            pool.add(e.getKey());
            weights.add(w);
        }
        if (pool.isEmpty()) {
            return Profession.CIVILIAN;
        }
        // 加权随机（Double 权重）
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        double r = ThreadLocalRandom.current().nextDouble() * total;
        double cum = 0;
        for (int i = 0; i < pool.size(); i++) {
            cum += weights.get(i);
            if (r <= cum) {
                return pool.get(i);
            }
        }
        return pool.getLast();
    }

    /** 构造指定槽位的物品（含 customModelData 与皮革染色，规范 2.3）。 */
    public ItemStack buildItem(EquipmentSpec spec) {
        ItemStack item = new ItemStack(spec.material());
        if (spec.customModelData() != null || spec.color() != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (spec.customModelData() != null) {
                    CustomModelDataComponent modelData = meta.getCustomModelDataComponent();
                    modelData.setFloats(List.of(spec.customModelData().floatValue()));
                    meta.setCustomModelDataComponent(modelData);
                }
                if (spec.color() != null && meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta lam) {
                    lam.setColor(spec.color());
                }
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    /** 构造交易商品 ItemStack（含附魔，问题6）。 */
    public ItemStack buildTradeItem(TradeOffer offer) {
        Material mat = Material.matchMaterial(offer.material());
        if (mat == null) {
            mat = Material.STICK;
        }
        ItemStack item = new ItemStack(mat, Math.max(1, offer.amount()));
        if (!offer.enchants().isEmpty()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                for (TradeOffer.EnchantSpec es : offer.enchants()) {
                    Enchantment ench = matchEnchantment(es.name());
                    if (ench != null) {
                        int lvl = Math.clamp(es.level(), 1, ench.getMaxLevel());
                        meta.addEnchant(ench, lvl, true);
                    }
                }
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    /** 按名称匹配附魔（Paper 26 使用 Registry + NamespacedKey，兼容旧名/命名空间前缀）。 */
    private Enchantment matchEnchantment(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.toUpperCase(Locale.ROOT).replace('-', '_');
        // 去除命名空间前缀（如 minecraft:sharpness）
        if (n.contains(":")) {
            n = n.substring(n.lastIndexOf(':') + 1);
        }
        // 旧别名 → 现代 minecraft key 映射
        String key = switch (n) {
            case "DAMAGE_ALL", "SHARPNESS" -> "sharpness";
            case "DAMAGE_UNDEAD", "SMITE" -> "smite";
            case "DAMAGE_ARTHROPODS", "BANE_OF_ARTHROPODS" -> "bane_of_arthropods";
            case "PROTECTION", "PROTECTION_ENVIRONMENTAL" -> "protection";
            case "PROTECTION_FIRE", "FIRE_PROTECTION" -> "fire_protection";
            case "PROTECTION_EXPLOSIONS", "BLAST_PROTECTION" -> "blast_protection";
            case "PROTECTION_PROJECTILE", "PROJECTILE_PROTECTION" -> "projectile_protection";
            case "PROTECTION_FALL", "FEATHER_FALLING" -> "feather_falling";
            case "DURABILITY", "UNBREAKING" -> "unbreaking";
            case "DIG_SPEED", "EFFICIENCY" -> "efficiency";
            case "LOOT_BONUS_BLOCKS", "FORTUNE" -> "fortune";
            case "LOOT_BONUS_MOBS", "LOOTING" -> "looting";
            case "ARROW_DAMAGE", "POWER" -> "power";
            case "ARROW_KNOCKBACK", "PUNCH" -> "punch";
            case "ARROW_FIRE", "FLAME" -> "flame";
            case "ARROW_INFINITE", "INFINITY" -> "infinity";
            case "OXYGEN", "RESPIRATION" -> "respiration";
            case "WATER_WORKER", "AQUA_AFFINITY" -> "aqua_affinity";
            case "THORNS" -> "thorns";
            case "SILK_TOUCH" -> "silk_touch";
            case "KNOCKBACK" -> "knockback";
            case "FIRE_ASPECT" -> "fire_aspect";
            case "QUICK_CHARGE" -> "quick_charge";
            case "MULTISHOT" -> "multishot";
            case "PIERCING" -> "piercing";
            case "CHANNELING" -> "channeling";
            case "RIPTIDE" -> "riptide";
            case "LOYALTY" -> "loyalty";
            case "IMPALING" -> "impaling";
            case "MENDING" -> "mending";
            case "VANISHING_CURSE", "CURSE_OF_VANISHING" -> "vanishing_curse";
            case "BINDING_CURSE", "CURSE_OF_BINDING" -> "binding_curse";
            case "FROST_WALKER" -> "frost_walker";
            case "SWEEPING_EDGE" -> "sweeping_edge";
            default -> n.toLowerCase(Locale.ROOT);
        };
        return io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT)
                .get(NamespacedKey.minecraft(key));
    }

    /** 获取某职业的交易商品定义列表（问题6）。 */
    public List<TradeOffer> tradesOf(Profession p) {
        ProfessionData d = data(p);
        return d != null ? d.trades() : List.of();
    }

    /** 槽位名 -> Bukkit 槽位枚举。 */
    public EquipmentSlot slot(String name) {
        return slotMap.get(name);
    }
}
