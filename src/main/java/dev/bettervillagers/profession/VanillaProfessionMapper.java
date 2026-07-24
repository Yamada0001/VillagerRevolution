package dev.bettervillagers.profession;

import org.bukkit.entity.Villager;

/**
 * 职业映射：BetterVillagers 自定义职业 → 原版 {@link Villager.Profession}。
 * <p>
 * 原版 {@code Villager.Profession.NONE} 的村民不会通过交易获得经验、无法升级。
 * 将自定义职业映射到最接近的原版职业，使原版升级机制正常工作；
 * 外观装备仍由 {@link EquipmentApplier} 覆盖（规范 2.3 外观与属性分离）。
 */
public final class VanillaProfessionMapper {

    private VanillaProfessionMapper() {
    }

    public static Villager.Profession toVanilla(Profession prof) {
        return switch (prof) {
            case KING, KNIGHT, SOLDIER -> Villager.Profession.WEAPONSMITH;
            case ARCHER -> Villager.Profession.FLETCHER;
            case BUTCHER -> Villager.Profession.BUTCHER;
            case CHEF -> Villager.Profession.BUTCHER;
            case FARMER -> Villager.Profession.FARMER;
            case MINER -> Villager.Profession.MASON;
            case BUILDER -> Villager.Profession.CLERIC;
            case MERCHANT -> Villager.Profession.CLERIC;
            default -> Villager.Profession.NITWIT;
        };
    }
}
