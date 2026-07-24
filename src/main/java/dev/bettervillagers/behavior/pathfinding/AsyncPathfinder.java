package dev.bettervillagers.behavior.pathfinding;

import dev.bettervillagers.BV;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 异步寻路（规范 4.1：A* 在异步线程完成，结果回传区域线程驱动移动）。
 * <p>
 * 关键：方块可达性快照<b>必须在区域线程</b>采集（{@link #capture}），
 * A* 计算（{@link #findPath}）在异步线程执行，仅产出路径坐标，
 * 最终移动由调用方在区域线程执行。
 */
public final class AsyncPathfinder {

    /** A* 最大展开节点数（防止超大快照下耗尽内存/时间）。 */
    private static final int MAX_NODES = 4096;

    /** 有界异步线程池（规范 4.1：避免 ForkJoinPool.commonPool 被其他任务挤占）。 */
    private final java.util.concurrent.ExecutorService pathExecutor;

    public AsyncPathfinder() {
        this(java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "BV-Pathfinder");
            t.setDaemon(true);
            return t;
        }));
    }

    public AsyncPathfinder(java.util.concurrent.ExecutorService executor) {
        this.pathExecutor = executor;
    }

    /** 通行性快照（区域线程采集，之后可异步读取）。 */
    public static final class PathSnapshot {
        /** 仅保存世界名，不持有 {@code World} 引用，避免跨异步线程传递时阻碍世界卸载（规范 4.x）。 */
        public final String worldName;
        public final int originX, originY, originZ, radius;
        private final boolean[][][] passable;

        public PathSnapshot(String worldName, int ox, int oy, int oz, int radius, boolean[][][] passable) {
            this.worldName = worldName;
            this.originX = ox;
            this.originY = oy;
            this.originZ = oz;
            this.radius = radius;
            this.passable = passable;
        }

        /** 按需重建世界（可能为 null：世界已卸载），调用方须判空。 */
        World world() {
            return worldName == null ? null : org.bukkit.Bukkit.getWorld(worldName);
        }

        boolean passable(int dx, int dy, int dz) {
            if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) {
                return false;
            }
            return passable[dx + radius][dy + radius][dz + radius];
        }
    }

    private record Node(int x, int y, int z) {
    }

    /** 在区域线程采集以 center 为中心、半径 radius 的通行性快照。 */
    public PathSnapshot capture(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return null; // 世界未加载，无法采集
        }
        String worldName = world.getName();
        int ox = center.getBlockX();
        int oy = center.getBlockY();
        int oz = center.getBlockZ();
        int size = radius * 2 + 1;
        boolean[][][] passable = new boolean[size][size][size];
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Material m = world.getBlockAt(ox + dx, oy + dy, oz + dz).getType();
                    passable[dx + radius][dy + radius][dz + radius] = m.isAir() || !m.isSolid();
                }
            }
        }
        return new PathSnapshot(worldName, ox, oy, oz, radius, passable);
    }

    /** 门面方法：采集快照 → 异步寻路 → 返回路径（供区域线程消费）。 */
    public CompletableFuture<List<Location>> moveAlong(Location from, Location to, int radius) {
        PathSnapshot snap = capture(from, radius);
        if (snap == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return findPath(snap, from, to);
    }

    /** 异步执行 A*，返回路径坐标列表（含起点与终点；失败返回空）。 */
    public CompletableFuture<List<Location>> findPath(PathSnapshot snap, Location from, Location to) {
        return CompletableFuture.supplyAsync(() -> computeAStar(snap, from, to), pathExecutor)
                .exceptionally(ex -> {
                    BV.plugin().getLogger().warning(
                            BV.messages().raw("log.pathfind-fail").replace("{error}", String.valueOf(ex)));
                    return List.of();
                });
    }

    /** 关闭寻路线程池（插件卸载时调用）。 */
    public void shutdown() {
        pathExecutor.shutdown();
        try {
            // 规范 4.x：等待在途 A* 任务结束，再强制关闭，避免持大数组的任务多跑
            if (!pathExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                pathExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pathExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private List<Location> computeAStar(PathSnapshot snap, Location from, Location to) {
        int sx = from.getBlockX() - snap.originX;
        int sy = from.getBlockY() - snap.originY;
        int sz = from.getBlockZ() - snap.originZ;
        int tx = to.getBlockX() - snap.originX;
        int ty = to.getBlockY() - snap.originY;
        int tz = to.getBlockZ() - snap.originZ;
        int r = snap.radius;

        if (!inRange(sx, r) || !inRange(sy, r) || !inRange(sz, r)
                || !inRange(tx, r) || !inRange(ty, r) || !inRange(tz, r)) {
            return List.of();
        }

        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingInt(n -> (Math.abs(n.x - tx) + Math.abs(n.y - ty) + Math.abs(n.z - tz))
                        + (Math.abs(n.x - sx) + Math.abs(n.y - sy) + Math.abs(n.z - sz))));
        Set<Node> closed = new HashSet<>();
        Map<Node, Node> came = new HashMap<>();
        open.add(new Node(sx, sy, sz));

        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int expanded = 0;
        while (!open.isEmpty()) {
            if (++expanded > MAX_NODES) {
                break;
            }
            Node cur = open.poll();
            if (cur.x == tx && cur.y == ty && cur.z == tz) {
                return reconstruct(came, cur, snap);
            }
            if (!closed.add(cur)) {
                continue;
            }
            for (int[] d : dirs) {
                Node next = new Node(cur.x + d[0], cur.y + d[1], cur.z + d[2]);
                if (snap.passable(next.x, next.y, next.z)) {
                    came.putIfAbsent(next, cur);
                    open.add(next);
                }
            }
        }
        return List.of();
    }

    private List<Location> reconstruct(Map<Node, Node> came, Node end, PathSnapshot snap) {
        World world = snap.world();
        if (world == null) {
            // 世界已卸载，无法重建路径坐标
            return List.of();
        }
        List<Location> path = new ArrayList<>();
        Node cur = end;
        while (cur != null) {
            path.add(new Location(world, snap.originX + cur.x + 0.5, snap.originY + cur.y, snap.originZ + cur.z + 0.5));
            cur = came.get(cur);
        }
        Collections.reverse(path);
        return path;
    }

    private static boolean inRange(int v, int r) {
        return v >= -r && v <= r;
    }
}
