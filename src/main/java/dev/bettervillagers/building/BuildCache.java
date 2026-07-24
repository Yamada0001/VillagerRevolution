package dev.bettervillagers.building;

import org.bukkit.Location;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 建筑缓存（修复重复堆叠）。
 * <p>
 * 规则：
 * <ul>
 *   <li>触发：仅在施工任务<strong>真正启动</strong>时写入，失败/取消不占位</li>
 *   <li>存储：村庄 + 类型 + 网格坐标；另记精确坐标做间距检测</li>
 *   <li>清理：可选按村庄重置；完工保留防重复，不无限增长同类型</li>
 *   <li>上限：同类型 maxPerVillage；同网格不可重复；最小间距 minSpacing</li>
 * </ul>
 */
public final class BuildCache {

    /** gridKey -> occupied */
    private final Set<String> gridOccupied = ConcurrentHashMap.newKeySet();
    /** villageId:type -> count */
    private final Map<String, AtomicInteger> typeCounts = new ConcurrentHashMap<>();
    /** 精确坐标记录，用于间距：village:type:x:z */
    private final Set<String> preciseSites = ConcurrentHashMap.newKeySet();
    /** 村庄当前发展阶段完成度（阶段内已完成任务数）。 */
    private final Map<Integer, Map<BuildType.DevPhase, AtomicInteger>> phaseProgress = new ConcurrentHashMap<>();

    public int count(int villageId, BuildType type) {
        AtomicInteger c = typeCounts.get(countKey(villageId, type));
        return c == null ? 0 : c.get();
    }

    /**
     * 清理指定村庄的全部缓存（村庄合并/删除时调用，规范 4.x：避免 stale 条目残留）。
     */
    public void clear(int villageId) {
        phaseProgress.remove(villageId);
        String prefix = villageId + ":";
        typeCounts.keySet().removeIf(k -> k.startsWith(prefix));
        preciseSites.removeIf(k -> k.startsWith(prefix));
        gridOccupied.removeIf(k -> k.startsWith(prefix));
    }

    public int phaseDone(int villageId, BuildType.DevPhase phase) {
        Map<BuildType.DevPhase, AtomicInteger> m = phaseProgress.get(villageId);
        if (m == null) {
            return 0;
        }
        AtomicInteger c = m.get(phase);
        return c == null ? 0 : c.get();
    }

    /**
     * 尝试占用建造位。成功返回 true 并写入缓存；失败返回 false（不应开工）。
     */
    public boolean tryOccupy(int villageId, BuildType type, String world, int x, int y, int z) {
        if (type == null || !type.physical()) {
            return true;
        }
        if (count(villageId, type) >= type.maxPerVillage()) {
            return false;
        }
        String grid = gridKey(villageId, type, world, x, z);
        if (gridOccupied.contains(grid)) {
            return false;
        }
        if (tooClose(villageId, type, world, x, z)) {
            return false;
        }
        // 原子占位
        if (!gridOccupied.add(grid)) {
            return false;
        }
        preciseSites.add(preciseKey(villageId, type, world, x, z));
        typeCounts.computeIfAbsent(countKey(villageId, type), k -> new AtomicInteger(0)).incrementAndGet();
        return true;
    }

    /** 任务取消时释放（完工不释放，防止原地再建同类型）。 */
    public void releaseOnCancel(int villageId, BuildType type, String world, int x, int y, int z) {
        if (type == null || !type.physical()) {
            return;
        }
        gridOccupied.remove(gridKey(villageId, type, world, x, z));
        preciseSites.remove(preciseKey(villageId, type, world, x, z));
        AtomicInteger c = typeCounts.get(countKey(villageId, type));
        if (c != null) {
            c.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    /** 完工：标记阶段进度。 */
    public void markCompleted(int villageId, BuildType type) {
        if (type == null) {
            return;
        }
        phaseProgress
                .computeIfAbsent(villageId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(type.phase(), k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /**
     * 寻找合法建造点：螺旋搜索，满足网格+间距+上限。
     * 找不到返回 null（禁止回退到原点硬建）。
     */
    public Location findFreeLocation(int villageId, BuildType type, Location preferred) {
        if (preferred == null || preferred.getWorld() == null || type == null) {
            return null;
        }
        if (count(villageId, type) >= type.maxPerVillage()) {
            return null;
        }
        if (canPlaceAt(villageId, type, preferred)) {
            return preferred.clone();
        }
        String world = preferred.getWorld().getName();
        int baseX = preferred.getBlockX();
        int baseY = preferred.getBlockY();
        int baseZ = preferred.getBlockZ();
        int step = Math.max(type.minSpacing(), type.gridSize() / 2);
        for (int ring = 1; ring <= 6; ring++) {
            int dist = ring * step;
            for (int angle = 0; angle < 360; angle += 30) {
                double rad = Math.toRadians(angle);
                int x = baseX + (int) Math.round(Math.cos(rad) * dist);
                int z = baseZ + (int) Math.round(Math.sin(rad) * dist);
                Location cand = new Location(preferred.getWorld(), x, baseY, z);
                if (canPlaceAt(villageId, type, cand)) {
                    return cand;
                }
            }
        }
        return null;
    }

    public boolean canPlaceAt(int villageId, BuildType type, Location loc) {
        if (loc == null || loc.getWorld() == null || type == null || !type.physical()) {
            return type != null && !type.physical();
        }
        if (count(villageId, type) >= type.maxPerVillage()) {
            return false;
        }
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        if (gridOccupied.contains(gridKey(villageId, type, world, x, z))) {
            return false;
        }
        return !tooClose(villageId, type, world, x, z);
    }

    private boolean tooClose(int villageId, BuildType type, String world, int x, int z) {
        int minSq = type.minSpacing() * type.minSpacing();
        String prefix = villageId + ":" + type.name() + ":" + world + ":";
        for (String site : preciseSites) {
            if (!site.startsWith(prefix)) {
                continue;
            }
            String[] p = site.split(":");
            if (p.length < 5) {
                continue;
            }
            try {
                int sx = Integer.parseInt(p[3]);
                int sz = Integer.parseInt(p[4]);
                int dx = sx - x;
                int dz = sz - z;
                if (dx * dx + dz * dz < minSq) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    private static String countKey(int villageId, BuildType type) {
        return villageId + ":" + type.name();
    }

    private static String gridKey(int villageId, BuildType type, String world, int x, int z) {
        int g = Math.max(1, type.gridSize());
        int gx = Math.floorDiv(x, g);
        int gz = Math.floorDiv(z, g);
        return villageId + ":" + type.name() + ":" + world + ":" + gx + ":" + gz;
    }

    private static String preciseKey(int villageId, BuildType type, String world, int x, int z) {
        return villageId + ":" + type.name() + ":" + world + ":" + x + ":" + z;
    }

    /** 道路阶段是否达标（至少 N 段路）。 */
    public boolean roadsComplete(int villageId, int required) {
        return count(villageId, BuildType.ROAD) >= required;
    }

    public boolean streetscapeComplete(int villageId, int required) {
        return count(villageId, BuildType.STREETSCAPE) >= required;
    }

    public boolean housingComplete(int villageId, int houseReq, int upgradeReq) {
        int houses = count(villageId, BuildType.HOUSE);
        int upgrades = count(villageId, BuildType.UPGRADE_HOUSE);
        // 须同时满足：存量房屋达标 + 升级量达标（避免过早进入城墙阶段）
        return houses + upgrades >= houseReq && upgrades >= upgradeReq;
    }
}
