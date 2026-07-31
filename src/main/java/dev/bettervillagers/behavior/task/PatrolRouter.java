package dev.bettervillagers.behavior.task;

import dev.bettervillagers.BV;
import dev.bettervillagers.village.Village;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 村庄边境巡逻路线生成器（规范 3.3：编组化、有组织的循环巡逻路线）。
 * <p>
 * 以村庄中心为圆心、半径的 0.85 倍为巡逻圈，沿圆周等分生成 {@link #WAYPOINT_COUNT}
 * 个航点，形成一条闭合的循环巡逻路线。军事职业村民按编组（squad）分配起始航点，
 * 使其沿同一方向推进，呈现有组织的巡逻队形。
 * <p>
 * 路线按村庄 id 缓存，村庄中心/半径变更时自动失效重建。所有坐标计算为纯算术，
 * 不触碰游戏世界对象，可在任意线程调用；最终移动由调用方在实体区域线程执行。
 */
public final class PatrolRouter {

    /** 每条巡逻路线的航点数量（原版村庄规模下的合理密度）。 */
    public static final int WAYPOINT_COUNT = 8;
    /** 巡逻圈占村庄半径的比例（贴近边境线但留出缓冲）。 */
    private static final double BORDER_RATIO = 0.85;

    /** 村庄 id -> 巡逻航点列表（缓存）。 */
    private final Map<Integer, List<Location>> routes = new ConcurrentHashMap<>();

    /** 取村庄边境巡逻路线（缓存命中则直接返回；空结果不缓存，下次重新尝试构建）。 */
    public List<Location> routeFor(int villageId) {
        List<Location> cached = routes.get(villageId);
        if (cached != null) {
            return cached;
        }
        // D3 修复：村庄数据尚未加载时 buildRoute 返回空列表，不缓存以免永久失效。
        List<Location> built = buildRoute(villageId);
        if (!built.isEmpty()) {
            routes.put(villageId, built);
            return built;
        }
        return List.of();
    }

    /** 重建指定村庄的巡逻路线（村庄数据变更时调用）。 */
    public void rebuild(int villageId) {
        List<Location> built = buildRoute(villageId);
        if (built.isEmpty()) {
            routes.remove(villageId);
            // D3 修复：空结果不写入缓存，保留旧有效路线或移除空占位
            routes.remove(villageId);
            return;
        }
        routes.put(villageId, built);
    }

    /** D4 修复：村庄删除/卸载时移除其巡逻缓存，避免废弃 Location（含 World 强引用）堆积泄漏。 */
    public void remove(int villageId) {
        routes.remove(villageId);
    }

    private List<Location> buildRoute(int villageId) {
        if (BV.villages() == null) {
            return List.of();
        }
        Village v = BV.villages().get(villageId).orElse(null);
        if (v == null) {
            return List.of();
        }
        World world = Bukkit.getWorld(v.world());
        if (world == null) {
            return List.of();
        }
        double radius = Math.max(8.0, v.radius() * BORDER_RATIO);
        List<Location> waypoints = new ArrayList<>(WAYPOINT_COUNT);
        for (int i = 0; i < WAYPOINT_COUNT; i++) {
            // 顺时针闭合圆周：从 0 度开始等分
            double angle = (Math.PI * 2 * i) / WAYPOINT_COUNT;
            double x = v.centerX() + Math.cos(angle) * radius;
            double z = v.centerZ() + Math.sin(angle) * radius;
            // Y 取村庄中心高度，避免地形下沉导致航点悬空
            waypoints.add(new Location(world, x, v.centerY(), z));
        }
        return List.copyOf(waypoints);
    }

    /** 清理全部缓存（停服时调用）。 */
    public void clear() {
        routes.clear();
    }
}
