package dev.bettervillagers.profession;

import dev.bettervillagers.BV;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;

/** Persistent equipment wear that keeps visual equipment separate from combat attributes. */
public final class EquipmentDurability {

    private static final double MAX_DURABILITY = 100.0;

    private EquipmentDurability() {
    }

    public static void applyCurrent(LivingEntity entity, ProfessionData data) {
        double durability = current(entity);
        EquipmentApplier.apply(entity, data, durability <= 0.0);
        updateVisualDamage(entity, durability);
    }

    public static void reset(LivingEntity entity, ProfessionData data) {
        set(entity, MAX_DURABILITY);
        EquipmentApplier.apply(entity, data, false);
        updateVisualDamage(entity, MAX_DURABILITY);
    }

    public static void damage(LivingEntity entity, ProfessionData data, double actionScale) {
        if (entity == null || data == null) {
            return;
        }
        double previous = current(entity);
        double next = nextDurability(previous, data.durabilityLossRate() * Math.max(0.0, actionScale));
        if (next == previous) {
            return;
        }
        set(entity, next);
        if (previous > 0.0 && next <= 0.0) {
            EquipmentApplier.apply(entity, data, true);
        }
        updateVisualDamage(entity, next);
    }

    public static void repair(LivingEntity entity, ProfessionData data, double amount) {
        if (entity == null || data == null || amount <= 0.0) {
            return;
        }
        double previous = current(entity);
        double next = Math.min(MAX_DURABILITY, previous + amount);
        set(entity, next);
        if (previous <= 0.0 && next > 0.0) {
            EquipmentApplier.apply(entity, data, false);
        }
        updateVisualDamage(entity, next);
    }

    public static double currentValue(LivingEntity entity) {
        return current(entity);
    }

    static double nextDurability(double current, double loss) {
        return Math.max(0.0, Math.min(MAX_DURABILITY, current) - Math.max(0.0, loss));
    }

    private static double current(LivingEntity entity) {
        if (entity == null) {
            return MAX_DURABILITY;
        }
        Double value = entity.getPersistentDataContainer().get(key(), PersistentDataType.DOUBLE);
        if (value == null) {
            set(entity, MAX_DURABILITY);
            return MAX_DURABILITY;
        }
        return Math.clamp(value, 0.0, MAX_DURABILITY);
    }

    private static void set(LivingEntity entity, double value) {
        entity.getPersistentDataContainer().set(key(), PersistentDataType.DOUBLE,
                Math.clamp(value, 0.0, MAX_DURABILITY));
    }

    private static NamespacedKey key() {
        return new NamespacedKey(BV.plugin(), "equipment_durability");
    }

    private static void updateVisualDamage(LivingEntity entity, double durability) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return;
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack item = equipment.getItem(slot);
            if (item.getType().isAir() || !(item.getItemMeta() instanceof Damageable damageable)) {
                continue;
            }
            int maximum = item.getType().getMaxDurability();
            if (maximum <= 1) {
                continue;
            }
            int visualDamage = (int) Math.round((1.0 - durability / MAX_DURABILITY) * (maximum - 1));
            damageable.setDamage(Math.clamp(visualDamage, 0, maximum - 1));
            item.setItemMeta(damageable);
            equipment.setItem(slot, item, true);
        }
    }
}
