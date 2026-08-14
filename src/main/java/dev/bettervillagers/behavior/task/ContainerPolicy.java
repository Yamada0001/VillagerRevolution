package dev.bettervillagers.behavior.task;

import dev.bettervillagers.profession.Profession;
import org.bukkit.Material;

import java.util.EnumSet;

/** Role-specific inputs and outputs used by autonomous container transfers. */
final class ContainerPolicy {

    private static final EnumSet<Profession> SUPPORTED = EnumSet.of(
            Profession.FARMER, Profession.MINER, Profession.FISHERMAN,
            Profession.CHEF, Profession.BUTCHER, Profession.BUILDER);

    private ContainerPolicy() {
    }

    static boolean supports(Profession profession) {
        return profession != null && SUPPORTED.contains(profession);
    }

    static boolean containerMaterial(Material material) {
        if (material == null) {
            return false;
        }
        return material.name().endsWith("SHULKER_BOX") || switch (material) {
            case CHEST, TRAPPED_CHEST, BARREL, FURNACE, BLAST_FURNACE, SMOKER,
                 HOPPER, DISPENSER, DROPPER, BREWING_STAND -> true;
            default -> false;
        };
    }

    static boolean input(Profession profession, Material material) {
        if (profession == null || material == null) {
            return false;
        }
        return switch (profession) {
            case FARMER -> isSeed(material);
            case CHEF -> switch (material) {
                case BEEF, PORKCHOP, CHICKEN, MUTTON, COD, SALMON, POTATO -> true;
                default -> false;
            };
            default -> false;
        };
    }

    static boolean output(Profession profession, Material material) {
        if (profession == null || material == null || material == Material.AIR
                || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
            return false;
        }
        String name = material.name();
        return switch (profession) {
            case FARMER -> switch (material) {
                case WHEAT, CARROT, POTATO, BEETROOT, PUMPKIN, MELON_SLICE -> true;
                default -> false;
            };
            case MINER -> name.endsWith("_ORE") || name.startsWith("RAW_")
                    || switch (material) {
                        case COAL, CHARCOAL, LAPIS_LAZULI, REDSTONE, DIAMOND,
                             COPPER_INGOT, IRON_INGOT, GOLD_INGOT, AMETHYST_SHARD -> true;
                        default -> false;
                    };
            case FISHERMAN -> switch (material) {
                case COD, SALMON, PUFFERFISH, TROPICAL_FISH -> true;
                default -> false;
            };
            case CHEF -> name.startsWith("COOKED_")
                    || material == Material.BREAD || material == Material.BAKED_POTATO;
            case BUTCHER -> switch (material) {
                case BEEF, PORKCHOP, CHICKEN, MUTTON, RABBIT, LEATHER, FEATHER -> true;
                default -> false;
            };
            case BUILDER -> name.endsWith("_LOG") || name.endsWith("_PLANKS")
                    || material == Material.COBBLESTONE || material == Material.STONE_BRICKS;
            default -> false;
        };
    }

    private static boolean isSeed(Material material) {
        return switch (material) {
            case WHEAT_SEEDS, BEETROOT_SEEDS, CARROT, POTATO -> true;
            default -> false;
        };
    }
}
