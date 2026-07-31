package dev.bettervillagers.building;

import dev.bettervillagers.storage.RoadPortRecord;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 基于模板真实边界连接点维护道路拓扑。 */
final class RoadLayout {

    private final Map<Integer, ArrayDeque<Connector>> open = new ConcurrentHashMap<>();
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();

    synchronized Location nextSite(int villageId, Location center, BuildCache cache, StructureTemplateLibrary templates) {
        if (center == null || center.getWorld() == null) {
            return null;
        }
        ArrayDeque<Connector> connectors = open.computeIfAbsent(villageId, ignored -> initial(center));
        while (!connectors.isEmpty()) {
            Connector incoming = connectors.removeFirst();
            StructureTemplateLibrary.RoadPiece piece = selectPiece(villageId, incoming);
            StructureTemplate template = templates.chooseRoad(center.getWorld().getSeed(), incoming.x(), incoming.z(), piece).orElse(null);
            if (template == null) {
                continue;
            }
            StructureTemplate.Placement placement = placementFor(incoming.direction());
            Location candidate = centerForIncoming(incoming, center.getWorld(), template, placement);
            if (!cache.canPlaceFootprint(villageId, BuildType.ROAD, candidate.getWorld().getName(),
                    candidate.getBlockX(), candidate.getBlockZ(),
                    candidate.getBlockX() - placement.anchorX(),
                    candidate.getBlockX() - placement.anchorX() + template.transformedWidth(placement) - 1,
                    candidate.getBlockZ() - placement.anchorZ(),
                    candidate.getBlockZ() - placement.anchorZ() + template.transformedDepth(placement) - 1)) {
                continue;
            }
            reservations.put(key(villageId, candidate), new Reservation(incoming, piece, template, placement));
            return candidate;
        }
        connectors.addAll(initial(center));
        return null;
    }

    synchronized Reservation takeReservation(int villageId, Location site) {
        return site == null || site.getWorld() == null ? null : reservations.remove(key(villageId, site));
    }

    synchronized void commit(int villageId, Location site, Reservation reservation) {
        if (site == null || reservation == null) {
            return;
        }
        ArrayDeque<Connector> connectors = open.computeIfAbsent(villageId, ignored -> new ArrayDeque<>());
        for (Direction direction : outgoing(reservation.incoming.direction(), reservation.piece)) {
            connectors.addLast(new Connector(site.getWorld().getName(), site.getBlockX(), site.getBlockY(), site.getBlockZ(), direction));
        }
    }

    synchronized void rollback(int villageId, Reservation reservation) {
        if (reservation != null) {
            open.computeIfAbsent(villageId, ignored -> new ArrayDeque<>()).addFirst(reservation.incoming);
        }
    }

    synchronized List<RoadPortRecord> exportVillage(int villageId) {
        List<RoadPortRecord> result = new ArrayList<>();
        for (Connector connector : open.getOrDefault(villageId, new ArrayDeque<>())) {
            result.add(new RoadPortRecord(villageId, connector.world, connector.x, connector.y, connector.z, connector.direction.name()));
        }
        return result;
    }

    synchronized void restore(List<RoadPortRecord> records) {
        for (RoadPortRecord record : records) {
            try {
                Direction direction = Direction.valueOf(record.direction());
                open.computeIfAbsent(record.villageId(), ignored -> new ArrayDeque<>())
                        .addLast(new Connector(record.world(), record.x(), record.y(), record.z(), direction));
            } catch (IllegalArgumentException ignored) {
                // 跳过旧库中的无效方向。
            }
        }
    }

    synchronized void clear(int villageId) {
        open.remove(villageId);
        reservations.keySet().removeIf(key -> key.startsWith(villageId + ":"));
    }

    private static ArrayDeque<Connector> initial(Location center) {
        ArrayDeque<Connector> connectors = new ArrayDeque<>();
        for (Direction direction : Direction.values()) {
            connectors.add(new Connector(center.getWorld().getName(), center.getBlockX(), center.getBlockY(), center.getBlockZ(), direction));
        }
        return connectors;
    }

    private static Location centerForIncoming(Connector port, World world, StructureTemplate template,
                                               StructureTemplate.Placement placement) {
        int half = Math.max(0, template.transformedDepth(placement) / 2);
        return new Location(world, port.x + port.direction.dx * half, port.y, port.z + port.direction.dz * half);
    }

    private static StructureTemplate.Placement placementFor(Direction direction) {
        return new StructureTemplate.Placement(switch (direction) {
            case NORTH -> StructureTemplate.Rotation.NONE;
            case EAST -> StructureTemplate.Rotation.CLOCKWISE_90;
            case SOUTH -> StructureTemplate.Rotation.CLOCKWISE_180;
            case WEST -> StructureTemplate.Rotation.COUNTERCLOCKWISE_90;
        }, StructureTemplate.Mirror.NONE, 0, 0);
    }

    private static List<Direction> outgoing(Direction incoming, StructureTemplateLibrary.RoadPiece piece) {
        return switch (piece) {
            case END -> List.of();
            case STRAIGHT, BRIDGE -> List.of(incoming);
            case CORNER -> List.of(incoming.right());
            case JUNCTION -> List.of(incoming, incoming.left(), incoming.right());
        };
    }

    private static StructureTemplateLibrary.RoadPiece selectPiece(int villageId, Connector connector) {
        int roll = Math.floorMod(villageId * 31 + connector.x * 17 + connector.z * 13, 12);
        return switch (roll) {
            case 0 -> StructureTemplateLibrary.RoadPiece.JUNCTION;
            case 1, 2 -> StructureTemplateLibrary.RoadPiece.CORNER;
            case 3 -> StructureTemplateLibrary.RoadPiece.BRIDGE;
            case 4 -> StructureTemplateLibrary.RoadPiece.END;
            default -> StructureTemplateLibrary.RoadPiece.STRAIGHT;
        };
    }

    private static String key(int villageId, Location location) {
        return villageId + ":" + location.getWorld().getName() + ":" + location.getBlockX() + ":" + location.getBlockZ();
    }

    record Reservation(Connector incoming, StructureTemplateLibrary.RoadPiece piece, StructureTemplate template,
                       StructureTemplate.Placement placement) {
    }

    private record Connector(String world, int x, int y, int z, Direction direction) {
    }

    private enum Direction {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);
        private final int dx;
        private final int dz;
        Direction(int dx, int dz) { this.dx = dx; this.dz = dz; }
        Direction left() { return values()[(ordinal() + 3) % values().length]; }
        Direction right() { return values()[(ordinal() + 1) % values().length]; }
    }
}
