package dev.bettervillagers.building;

import org.bukkit.Material;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** 读取原版结构方块导出的 NBT，仅保留可逐块施工的方块状态。 */
final class StructureNbtReader {

    StructureTemplate read(InputStream source, String id) throws IOException {
        InputStream input = source.markSupported() ? source : new java.io.BufferedInputStream(source);
        input.mark(2);
        int first = input.read();
        int second = input.read();
        input.reset();
        if (first == 0x1f && second == 0x8b) {
            input = new GZIPInputStream(input);
        }
        try (DataInputStream data = new DataInputStream(input)) {
            if (data.readUnsignedByte() != 10) {
                throw new IOException("根标签不是 Compound");
            }
            data.readUTF();
            Map<String, Object> root = compound(data);
            List<?> size = list(root.get("size"));
            List<?> palette = list(root.get("palette"));
            List<?> blocks = list(root.get("blocks"));
            if (size.size() < 3 || palette.isEmpty() || blocks.isEmpty()) {
                throw new IOException("缺少 size、palette 或 blocks");
            }
            List<StructureTemplate.Block> placed = new ArrayList<>();
            for (Object raw : blocks) {
                if (!(raw instanceof Map<?, ?> block)) {
                    continue;
                }
                List<?> pos = list(block.get("pos"));
                Object stateValue = block.get("state");
                if (pos.size() < 3 || !(stateValue instanceof Number state)) {
                    continue;
                }
                int paletteIndex = state.intValue();
                if (paletteIndex < 0 || paletteIndex >= palette.size() || !(palette.get(paletteIndex) instanceof Map<?, ?> entry)) {
                    continue;
                }
                String stateText = blockState(entry);
                Object nameValue = entry.get("Name");
                Material material = nameValue instanceof String name ? Material.matchMaterial(name) : null;
                if (material == null || material.isAir() || material == Material.STRUCTURE_VOID || isUnsafe(material)) {
                    continue;
                }
                BlockEntityPolicy policy = blockEntityPolicy(material, block.get("nbt"));
                placed.add(new StructureTemplate.Block(number(pos.get(0)), number(pos.get(1)), number(pos.get(2)),
                        material, stateText, policy));
            }
            return new StructureTemplate(id, number(size.get(0)), number(size.get(1)), number(size.get(2)), placed);
        }
    }

    private static boolean isUnsafe(Material material) {
        String name = material.name();
        return material == Material.SPAWNER || name.contains("COMMAND_BLOCK")
                || material == Material.JIGSAW || material == Material.STRUCTURE_BLOCK
                || name.contains("END_PORTAL") || name.contains("NETHER_PORTAL");
    }

    private static BlockEntityPolicy blockEntityPolicy(Material material, Object nbt) {
        if (!(nbt instanceof Map<?, ?>)) {
            return BlockEntityPolicy.NONE;
        }
        String name = material.name();
        if (name.endsWith("_SIGN") || name.endsWith("_HANGING_SIGN")) {
            return BlockEntityPolicy.CLEAR_SIGN;
        }
        if (name.contains("CHEST") || name.contains("BARREL") || name.contains("SHULKER_BOX")
                || name.contains("FURNACE") || name.equals("HOPPER") || name.equals("DROPPER")
                || name.equals("DISPENSER") || name.equals("BREWING_STAND")) {
            return BlockEntityPolicy.CLEAR_INVENTORY;
        }
        return BlockEntityPolicy.NONE;
    }

    private static String blockState(Map<?, ?> entry) {
        Object nameValue = entry.get("Name");
        if (!(nameValue instanceof String name)) {
            throw new IllegalArgumentException("palette 缺少 Name");
        }
        Object propertiesValue = entry.get("Properties");
        if (!(propertiesValue instanceof Map<?, ?> properties) || properties.isEmpty()) {
            return name;
        }
        StringBuilder state = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<?, ?> property : properties.entrySet()) {
            if (!first) {
                state.append(',');
            }
            state.append(property.getKey()).append('=').append(property.getValue());
            first = false;
        }
        return state.append(']').toString();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Map<String, Object> compound(DataInput data) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        int type;
        while ((type = data.readUnsignedByte()) != 0) {
            values.put(data.readUTF(), readPayload(data, type));
        }
        return values;
    }

    private static Object readPayload(DataInput data, int type) throws IOException {
        return switch (type) {
            case 1 -> data.readByte();
            case 2 -> data.readShort();
            case 3 -> data.readInt();
            case 4 -> data.readLong();
            case 5 -> data.readFloat();
            case 6 -> data.readDouble();
            case 7 -> {
                byte[] values = new byte[data.readInt()];
                data.readFully(values);
                yield values;
            }
            case 8 -> data.readUTF();
            case 9 -> {
                int elementType = data.readUnsignedByte();
                int length = data.readInt();
                List<Object> values = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    values.add(readPayload(data, elementType));
                }
                yield values;
            }
            case 10 -> compound(data);
            case 11 -> {
                int[] values = new int[data.readInt()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = data.readInt();
                }
                yield values;
            }
            case 12 -> {
                long[] values = new long[data.readInt()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = data.readLong();
                }
                yield values;
            }
            default -> throw new IOException("未知 NBT 标签类型: " + type);
        };
    }
}
