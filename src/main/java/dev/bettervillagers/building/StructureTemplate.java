package dev.bettervillagers.building;

import org.bukkit.Material;

import java.util.List;

/** 已解析的原版结构模板。坐标均相对模板锚点。 */
record StructureTemplate(String id, int width, int height, int depth, List<Block> blocks) {

    enum Rotation {
        NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90;

        int transformedWidth(int width, int depth) {
            return this == CLOCKWISE_90 || this == COUNTERCLOCKWISE_90 ? depth : width;
        }

        int transformedDepth(int width, int depth) {
            return this == CLOCKWISE_90 || this == COUNTERCLOCKWISE_90 ? width : depth;
        }
    }

    enum Mirror { NONE, LEFT_RIGHT, FRONT_BACK }

    record Placement(Rotation rotation, Mirror mirror, int anchorX, int anchorZ) {
        static Placement centered(StructureTemplate template) {
            return new Placement(Rotation.NONE, Mirror.NONE,
                    template.width() / 2, template.depth() / 2);
        }
    }

    record Position(int x, int z) {
    }

    Position transform(int x, int z, Placement placement) {
        int tx = x;
        int tz = z;
        if (placement.mirror() == Mirror.LEFT_RIGHT) {
            tx = width - 1 - tx;
        } else if (placement.mirror() == Mirror.FRONT_BACK) {
            tz = depth - 1 - tz;
        }
        return switch (placement.rotation()) {
            case NONE -> new Position(tx, tz);
            case CLOCKWISE_90 -> new Position(depth - 1 - tz, tx);
            case CLOCKWISE_180 -> new Position(width - 1 - tx, depth - 1 - tz);
            case COUNTERCLOCKWISE_90 -> new Position(tz, width - 1 - tx);
        };
    }

    int transformedWidth(Placement placement) {
        return placement.rotation().transformedWidth(width, depth);
    }

    int transformedDepth(Placement placement) {
        return placement.rotation().transformedDepth(width, depth);
    }

    record Block(int x, int y, int z, Material material, String blockData,
                 BlockEntityPolicy blockEntityPolicy) {
    }
}
