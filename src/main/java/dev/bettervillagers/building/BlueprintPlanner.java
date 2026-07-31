package dev.bettervillagers.building;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.Rotatable;

import java.util.ArrayList;
import java.util.List;

final class BlueprintPlanner {

    private BlueprintPlanner() {
    }

    static List<ConstructionStep> plan(BuildType type, SiteAssessment site, StructureTemplate template,
                                       StructureTemplate.Placement placement) {
        if (type == null || site == null) {
            return List.of();
        }
        if (template == null || template.blocks().isEmpty()) {
            return fallback(type, site);
        }
        StructureTemplate.Placement effective = placement == null
                ? StructureTemplate.Placement.centered(template)
                : placement;
        return planAt(template, effective, site.centerX(), site.targetLevelY(), site.centerZ());
    }

    static List<ConstructionStep> planAt(StructureTemplate template, StructureTemplate.Placement placement,
                                         int centerX, int baseY, int centerZ) {
        List<ConstructionStep> steps = new ArrayList<>();
        int anchorX = centerX - placement.anchorX();
        int anchorZ = centerZ - placement.anchorZ();
        for (StructureTemplate.Block block : template.blocks()) {
            StructureTemplate.Position pos = template.transform(block.x(), block.z(), placement);
            String phase = block.y() == 0
                    ? "FOUNDATION"
                    : block.y() >= template.height() - 2 ? "ROOF" : "STRUCTURE";
            steps.add(ConstructionStep.place(anchorX + pos.x(), baseY + block.y(), anchorZ + pos.z(),
                    block.material(), transformBlockData(block.blockData(), placement),
                    block.blockEntityPolicy(), phase));
        }
        return steps;
    }

    private static List<ConstructionStep> fallback(BuildType type, SiteAssessment site) {
        if (!type.physical()) {
            return List.of();
        }
        List<ConstructionStep> steps = new ArrayList<>();
        int radius = Math.clamp(type.minSpacing() / 3, 1, 4);
        Material foundation = type == BuildType.ROAD ? Material.GRAVEL : Material.COBBLESTONE;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                steps.add(ConstructionStep.place(site.centerX() + x, site.targetLevelY(),
                        site.centerZ() + z, foundation, "FOUNDATION"));
            }
        }
        if (type == BuildType.ROAD || type == BuildType.STREETSCAPE) {
            return steps;
        }
        for (int y = 1; y <= 3; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) == radius || Math.abs(z) == radius) {
                        steps.add(ConstructionStep.place(site.centerX() + x, site.targetLevelY() + y,
                                site.centerZ() + z, Material.OAK_PLANKS, "STRUCTURE"));
                    }
                }
            }
        }
        return steps;
    }

    private static String transformBlockData(String value, StructureTemplate.Placement placement) {
        if (value == null || noTransform(placement)) {
            return value;
        }
        try {
            BlockData data = Bukkit.createBlockData(value);
            if (data instanceof Directional directional) {
                directional.setFacing(transformFace(directional.getFacing(), placement));
            }
            if (data instanceof Rotatable rotatable) {
                rotatable.setRotation(transformFace(rotatable.getRotation(), placement));
            }
            if (data instanceof Orientable orientable) {
                orientable.setAxis(transformAxis(orientable.getAxis(), placement));
            }
            if (data instanceof Rail rail) {
                rail.setShape(transformRailShape(rail.getShape(), placement));
            }
            return data.getAsString();
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private static boolean noTransform(StructureTemplate.Placement placement) {
        return placement.rotation() == StructureTemplate.Rotation.NONE
                && placement.mirror() == StructureTemplate.Mirror.NONE;
    }

    private static BlockFace transformFace(BlockFace face, StructureTemplate.Placement placement) {
        BlockFace result = face;
        if (placement.mirror() == StructureTemplate.Mirror.LEFT_RIGHT) {
            result = flip(result, BlockFace.EAST, BlockFace.WEST);
        }
        if (placement.mirror() == StructureTemplate.Mirror.FRONT_BACK) {
            result = flip(result, BlockFace.NORTH, BlockFace.SOUTH);
        }
        return rotateFace(result, placement.rotation());
    }

    private static BlockFace flip(BlockFace face, BlockFace a, BlockFace b) {
        if (face == a) {
            return b;
        }
        return face == b ? a : face;
    }

    private static BlockFace rotateFace(BlockFace face, StructureTemplate.Rotation rotation) {
        int turns = switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
        };
        BlockFace result = face;
        for (int i = 0; i < turns; i++) {
            result = switch (result) {
                case NORTH -> BlockFace.EAST;
                case EAST -> BlockFace.SOUTH;
                case SOUTH -> BlockFace.WEST;
                case WEST -> BlockFace.NORTH;
                default -> result;
            };
        }
        return result;
    }

    private static Axis transformAxis(Axis axis, StructureTemplate.Placement placement) {
        if (axis == Axis.Y
                || placement.rotation() == StructureTemplate.Rotation.NONE
                || placement.rotation() == StructureTemplate.Rotation.CLOCKWISE_180) {
            return axis;
        }
        return axis == Axis.X ? Axis.Z : Axis.X;
    }

    private static Rail.Shape transformRailShape(Rail.Shape shape, StructureTemplate.Placement placement) {
        BlockFace a = switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH, NORTH_EAST, SOUTH_EAST -> BlockFace.NORTH;
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> BlockFace.EAST;
            case SOUTH_WEST, NORTH_WEST -> BlockFace.SOUTH;
        };
        BlockFace b = switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> BlockFace.SOUTH;
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> BlockFace.WEST;
            case NORTH_EAST, NORTH_WEST -> BlockFace.EAST;
            case SOUTH_EAST, SOUTH_WEST -> BlockFace.WEST;
        };
        a = transformFace(a, placement);
        b = transformFace(b, placement);
        if (shape.name().startsWith("ASCENDING_")) {
            return switch (a) {
                case NORTH -> Rail.Shape.ASCENDING_NORTH;
                case EAST -> Rail.Shape.ASCENDING_EAST;
                case SOUTH -> Rail.Shape.ASCENDING_SOUTH;
                default -> Rail.Shape.ASCENDING_WEST;
            };
        }
        if ((a == BlockFace.NORTH && b == BlockFace.SOUTH) || (a == BlockFace.SOUTH && b == BlockFace.NORTH)) {
            return Rail.Shape.NORTH_SOUTH;
        }
        if ((a == BlockFace.EAST && b == BlockFace.WEST) || (a == BlockFace.WEST && b == BlockFace.EAST)) {
            return Rail.Shape.EAST_WEST;
        }
        if ((a == BlockFace.NORTH && b == BlockFace.EAST) || (a == BlockFace.EAST && b == BlockFace.NORTH)) {
            return Rail.Shape.NORTH_EAST;
        }
        if ((a == BlockFace.SOUTH && b == BlockFace.EAST) || (a == BlockFace.EAST && b == BlockFace.SOUTH)) {
            return Rail.Shape.SOUTH_EAST;
        }
        if ((a == BlockFace.SOUTH && b == BlockFace.WEST) || (a == BlockFace.WEST && b == BlockFace.SOUTH)) {
            return Rail.Shape.SOUTH_WEST;
        }
        return Rail.Shape.NORTH_WEST;
    }
}
