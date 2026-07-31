package dev.bettervillagers.profession;

import dev.bettervillagers.BV;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 装备与属性应用器（规范 2.3：外观与属性解耦）。
 * <p>
 * 所有写操作必须通过调度器在实体所属区域线程执行；本类只负责"如何写"，
 * 由调用方（村民管理器）经 {@code scheduler.runForEntity} 调度后调用 {@link #apply}。
 */
public final class EquipmentApplier {

    private EquipmentApplier() {
    }

    /** 在区域线程内应用：装备外观 + 战斗属性数值层。 */
    public static void apply(LivingEntity entity, ProfessionData data) {
        apply(entity, data, false);
    }

    /**
     * 在区域线程内应用：装备外观 + 战斗属性数值层（规范 2.3）。
     *
     * @param broken 装备是否破损；为 true 时属性减半
     */
    public static void apply(LivingEntity entity, ProfessionData data, boolean broken) {
        if (entity == null || data == null) {
            return;
        }
        // 装备外观层
        EntityEquipment eq = entity.getEquipment();
        if (eq != null) {
            for (var entry : data.equipment().entrySet()) {
                EquipmentSlot slot = BV.professions().slot(entry.getKey());
                if (slot == null) {
                    continue;
                }
                ItemStack item = BV.professions().buildItem(entry.getValue());
                eq.setItem(slot, item, true);
                // 修复问题4：设置掉落概率为 0，确保装备不被丢弃且持续渲染
                eq.setDropChance(slot, 0.0f);
            }
        }
        // 战斗属性数值层（规范 0.2：Paper 26.2 属性 key 不带 generic. 前缀）
        double healthMult = broken ? 0.5 : 1.0;
        double attackMult = broken ? 0.5 : 1.0;
        double defenseMult = broken ? 0.5 : 1.0;
        setAttribute(entity, "max_health", data.stats().health() * healthMult);
        setAttribute(entity, "attack_damage", data.stats().attack() * attackMult);
        setAttribute(entity, "armor", data.stats().defense().damageReduction() * 20.0 * defenseMult);
        try {
            entity.setHealth(Math.min(data.stats().health(), maxHealth(entity, data.stats().health())));
        } catch (Exception ignored) {
            // 容错：设血量失败不影响流程
        }
    }

    /** 通过 registry key 获取属性，避免依赖旧式静态解析。 */
    private static void setAttribute(LivingEntity entity, String key, double value) {
        try {
            Attribute attr = attribute(key);
            if (attr == null) {
                return;
            }
            AttributeInstance ai = entity.getAttribute(attr);
            if (ai != null) {
                ai.setBaseValue(value);
            }
        } catch (Throwable t) {
            BV.plugin().getLogger().warning(BV.messages().raw("log.attr-set-fail").replace("{key}", key).replace("{error}", t.getMessage()));
        }
    }

    private static double maxHealth(LivingEntity entity, double fallback) {
        Attribute attr = attribute("max_health");
        if (attr == null) {
            return fallback;
        }
        AttributeInstance maxHealth = entity.getAttribute(attr);
        return maxHealth == null ? fallback : maxHealth.getValue();
    }

    private static Attribute attribute(String key) {
        return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ATTRIBUTE)
                .get(NamespacedKey.minecraft(key));
    }
}
