package dev.bettervillagers.building;

import dev.bettervillagers.storage.BuildLayoutRecord;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime occupancy cache for completed and in-progress buildings.
 */
public final class BuildCache {

    private final Set<String> gridOccupied = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> typeCounts = new ConcurrentHashMap<>();
    private final Set<String> preciseSites = ConcurrentHashMap.newKeySet();
    private final Set<Footprint> footprints = ConcurrentHashMap.newKeySet();
    private final Map<String, BuildLayoutRecord> completed = new ConcurrentHashMap<>();

    public int count(int villageId, BuildType type) {
        AtomicInteger count = typeCounts.get(countKey(villageId, type));
        return count == null ? 0 : count.get();
    }

    void clear(int villageId) {
        String prefix = villageId + ":";
        typeCounts.keySet().removeIf(key -> key.startsWith(prefix));
        preciseSites.removeIf(key -> key.startsWith(prefix));
        footprints.removeIf(footprint -> footprint.villageId == villageId);
        gridOccupied.removeIf(key -> key.startsWith(prefix));
        completed.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Re-keys completed occupancy after a database-level village merge. */
    void mergeVillage(int fromId, int toId) {
        List<BuildLayoutRecord> records = completed.values().stream()
                .filter(record -> record.villageId() == fromId || record.villageId() == toId)
                .map(record -> record.villageId() == fromId ? withVillage(record, toId) : record)
                .toList();
        clear(fromId);
        clear(toId);
        records.forEach(this::restore);
    }

    private static BuildLayoutRecord withVillage(BuildLayoutRecord record, int villageId) {
        return new BuildLayoutRecord(villageId, record.world(), record.buildType(), record.templateId(),
                record.centerX(), record.centerY(), record.centerZ(), record.minX(), record.maxX(),
                record.minZ(), record.maxZ(), record.rotation(), record.mirror(), record.clusterId());
    }

    boolean tryOccupy(int villageId, BuildType type, String world, int x, int z,
                      int minX, int maxX, int minZ, int maxZ) {
        if (type == null || !type.physical()) {
            return true;
        }
        if (!canPlaceFootprint(villageId, type, world, x, z, minX, maxX, minZ, maxZ)) {
            return false;
        }
        String grid = gridKey(villageId, type, world, x, z);
        if (!gridOccupied.add(grid)) {
            return false;
        }
        preciseSites.add(preciseKey(villageId, type, world, x, z));
        typeCounts.computeIfAbsent(countKey(villageId, type), ignored -> new AtomicInteger()).incrementAndGet();
        footprints.add(new Footprint(villageId, world, minX, maxX, minZ, maxZ));
        return true;
    }

    void releaseOnCancel(int villageId, BuildType type, String world, int x, int z) {
        if (type == null || !type.physical()) {
            return;
        }
        gridOccupied.remove(gridKey(villageId, type, world, x, z));
        preciseSites.remove(preciseKey(villageId, type, world, x, z));
        footprints.removeIf(footprint -> footprint.villageId == villageId
                && footprint.world.equals(world)
                && x >= footprint.minX && x <= footprint.maxX
                && z >= footprint.minZ && z <= footprint.maxZ);
        AtomicInteger count = typeCounts.get(countKey(villageId, type));
        if (count != null) {
            count.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    private boolean canPlaceAt(int villageId, BuildType type, org.bukkit.Location loc) {
        if (loc == null || loc.getWorld() == null || type == null) {
            return false;
        }
        int halfSize = defaultHalfSize(type);
        return canPlaceFootprint(villageId, type, loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockZ(),
                loc.getBlockX() - halfSize, loc.getBlockX() + halfSize,
                loc.getBlockZ() - halfSize, loc.getBlockZ() + halfSize);
    }

    boolean canPlaceFootprint(int villageId, BuildType type, String world, int x, int z,
                              int minX, int maxX, int minZ, int maxZ) {
        if (count(villageId, type) >= type.maxPerVillage()
                || gridOccupied.contains(gridKey(villageId, type, world, x, z))
                || tooClose(villageId, type, world, x, z)) {
            return false;
        }
        Footprint candidate = new Footprint(villageId, world, minX, maxX, minZ, maxZ);
        return footprints.stream().noneMatch(footprint -> footprint.overlaps(candidate));
    }

    void rememberCompleted(BuildLayoutRecord record) {
        completed.put(record.villageId() + ":" + record.world() + ":" + record.centerX() + ":" + record.centerZ(), record);
    }

    void forgetCompleted(BuildLayoutRecord record) {
        completed.remove(record.villageId() + ":" + record.world() + ":"
                + record.centerX() + ":" + record.centerZ(), record);
    }

    List<BuildLayoutRecord> exportVillage(int villageId) {
        return completed.values().stream().filter(record -> record.villageId() == villageId).toList();
    }

    Set<String> completedClusters(int villageId) {
        return completed.values().stream()
                .filter(record -> record.villageId() == villageId
                        && record.clusterId() != null
                        && !record.clusterId().isBlank())
                .map(BuildLayoutRecord::clusterId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    void restore(BuildLayoutRecord record) {
        BuildType type = BuildType.fromCommand(record.buildType());
        if (type == null) {
            return;
        }
        tryOccupy(record.villageId(), type, record.world(), record.centerX(), record.centerZ(),
                record.minX(), record.maxX(), record.minZ(), record.maxZ());
        rememberCompleted(record);
    }

    org.bukkit.Location findFreeLocation(int villageId, BuildType type, org.bukkit.Location preferred) {
        if (preferred == null || preferred.getWorld() == null || count(villageId, type) >= type.maxPerVillage()) {
            return null;
        }
        if (canPlaceAt(villageId, type, preferred)) {
            return preferred.clone();
        }
        int step = Math.max(type.minSpacing(), type.gridSize() / 2);
        for (int radius = 1; radius <= 6; radius++) {
            for (int angle = 0; angle < 360; angle += 30) {
                int x = preferred.getBlockX() + (int) Math.round(Math.cos(Math.toRadians(angle)) * radius * step);
                int z = preferred.getBlockZ() + (int) Math.round(Math.sin(Math.toRadians(angle)) * radius * step);
                var candidate = new org.bukkit.Location(preferred.getWorld(), x, preferred.getBlockY(), z);
                if (canPlaceAt(villageId, type, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean tooClose(int villageId, BuildType type, String world, int x, int z) {
        int minDistanceSq = type.minSpacing() * type.minSpacing();
        String prefix = villageId + ":" + type.name() + ":" + world + ":";
        for (String site : preciseSites) {
            if (!site.startsWith(prefix)) {
                continue;
            }
            String[] parts = site.split(":");
            int dx = Integer.parseInt(parts[3]) - x;
            int dz = Integer.parseInt(parts[4]) - z;
            if (dx * dx + dz * dz < minDistanceSq) {
                return true;
            }
        }
        return false;
    }

    private static int defaultHalfSize(BuildType type) {
        return Math.max(3, type.minSpacing() / 2 + 2);
    }

    private static String countKey(int id, BuildType type) {
        return id + ":" + type.name();
    }

    private static String gridKey(int id, BuildType type, String world, int x, int z) {
        return id + ":" + type.name() + ":" + world + ":"
                + Math.floorDiv(x, Math.max(1, type.gridSize())) + ":"
                + Math.floorDiv(z, Math.max(1, type.gridSize()));
    }

    private static String preciseKey(int id, BuildType type, String world, int x, int z) {
        return id + ":" + type.name() + ":" + world + ":" + x + ":" + z;
    }

    boolean roadsComplete(int id, int needed) {
        return count(id, BuildType.ROAD) >= needed;
    }

    boolean streetscapeComplete(int id, int needed) {
        return count(id, BuildType.STREETSCAPE) >= needed;
    }

    boolean housingComplete(int id, int housesNeeded, int upgradesNeeded) {
        return count(id, BuildType.HOUSE) + count(id, BuildType.UPGRADE_HOUSE) >= housesNeeded
                && count(id, BuildType.UPGRADE_HOUSE) >= upgradesNeeded;
    }

    private record Footprint(int villageId, String world, int minX, int maxX, int minZ, int maxZ) {
        boolean overlaps(Footprint other) {
            return villageId == other.villageId
                    && world.equals(other.world)
                    && minX <= other.maxX && maxX >= other.minX
                    && minZ <= other.maxZ && maxZ >= other.minZ;
        }
    }
}
