package dev.bettervillagers.profession;

import org.bukkit.Color;
import org.bukkit.Material;

/** 装备槽位规格（规范 2.3）：材料 + 可选自定义模型数据 + 可选皮革染色。 */
public record EquipmentSpec(Material material, Integer customModelData, Color color) {

    public static EquipmentSpec of(Material material) {
        return new EquipmentSpec(material, null, null);
    }

    public static EquipmentSpec of(Material material, int customModelData) {
        return new EquipmentSpec(material, customModelData, null);
    }

    /** 从配置项 {@code {material: X, custom-model-data: Y}} 解析。 */
    public static EquipmentSpec parse(String raw) {
        if (raw == null) {
            return null;
        }
        // 支持 "MATERIAL" 或 "MATERIAL:cmd"
        String[] parts = raw.split(":");
        Material mat = Material.matchMaterial(parts[0].trim());
        if (mat == null) {
            return null;
        }
        Integer cmd = null;
        if (parts.length > 1) {
            try {
                cmd = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
                // 忽略非法 cmd
            }
        }
        return new EquipmentSpec(mat, cmd, null);
    }
}
