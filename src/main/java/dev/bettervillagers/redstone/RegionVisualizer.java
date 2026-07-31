package dev.bettervillagers.redstone;

import dev.bettervillagers.BV;
import dev.bettervillagers.scheduler.ScheduledHandle;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** 保护区粒子可视化。坐标采样在异步层执行，粒子发送回玩家所属安全线程。 */
public final class RegionVisualizer {

    private static final int PARTICLE_STEP = 3;
    private static final long PREVIEW_PERIOD_TICKS = 20L;

    public void showBoundary(Player player, ProtectedRegion region) {
        World world = org.bukkit.Bukkit.getWorld(region.world());
        if (world == null) {
            return;
        }
        Bounds bounds = Bounds.from(new Location(world, region.minX(), region.minY(), region.minZ()),
                new Location(world, region.maxX(), region.maxY(), region.maxZ()));
        BV.scheduler().runAsync(() -> {
            List<Point> points = boundaryPoints(bounds);
            BV.scheduler().runForEntity(player, () -> sendParticles(player, bounds.world(), points), null);
        });
    }

    public ScheduledHandle preview(Player player, Location first, Location second) {
        Bounds bounds = Bounds.from(first, second);
        return BV.scheduler().runAsyncTimer(() -> {
            List<Point> points = boundaryPoints(bounds);
            BV.scheduler().runForEntity(player, () -> sendParticles(player, bounds.world(), points), null);
        }, 0L, PREVIEW_PERIOD_TICKS);
    }

    private List<Point> boundaryPoints(Bounds bounds) {
        List<Point> points = new ArrayList<>();
        if (!bounds.complete()) {
            points.add(new Point(bounds.minX(), bounds.minY(), bounds.minZ()));
            return points;
        }
        addBoxEdge(points, bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
        return points;
    }

    private void addBoxEdge(List<Point> points, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        addLine(points, minX, minY, minZ, maxX, minY, minZ);
        addLine(points, minX, minY, maxZ, maxX, minY, maxZ);
        addLine(points, minX, maxY, minZ, maxX, maxY, minZ);
        addLine(points, minX, maxY, maxZ, maxX, maxY, maxZ);
        addLine(points, minX, minY, minZ, minX, maxY, minZ);
        addLine(points, minX, minY, maxZ, minX, maxY, maxZ);
        addLine(points, maxX, minY, minZ, maxX, maxY, minZ);
        addLine(points, maxX, minY, maxZ, maxX, maxY, maxZ);
        addLine(points, minX, minY, minZ, minX, minY, maxZ);
        addLine(points, maxX, minY, minZ, maxX, minY, maxZ);
        addLine(points, minX, maxY, minZ, minX, maxY, maxZ);
        addLine(points, maxX, maxY, minZ, maxX, maxY, maxZ);
    }

    private void addLine(List<Point> points, int x1, int y1, int z1, int x2, int y2, int z2) {
        int distance = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
        int steps = Math.max(1, distance / PARTICLE_STEP);
        for (int index = 0; index <= steps; index++) {
            double ratio = (double) index / steps;
            points.add(new Point(
                    (int) Math.round(x1 + (x2 - x1) * ratio),
                    (int) Math.round(y1 + (y2 - y1) * ratio),
                    (int) Math.round(z1 + (z2 - z1) * ratio)));
        }
    }

    private void sendParticles(Player player, World world, List<Point> points) {
        if (!player.isOnline() || player.getWorld() != world) {
            return;
        }
        for (Point point : points) {
            player.spawnParticle(Particle.HAPPY_VILLAGER,
                    point.x() + 0.5, point.y() + 0.5, point.z() + 0.5,
                    1, 0, 0, 0, 0);
        }
    }

    private record Point(int x, int y, int z) {
    }

    private record Bounds(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean complete) {
        private static Bounds from(Location first, Location second) {
            if (second == null) {
                return new Bounds(first.getWorld(), first.getBlockX(), first.getBlockY(), first.getBlockZ(),
                        first.getBlockX(), first.getBlockY(), first.getBlockZ(), false);
            }
            return new Bounds(first.getWorld(),
                    Math.min(first.getBlockX(), second.getBlockX()), Math.min(first.getBlockY(), second.getBlockY()), Math.min(first.getBlockZ(), second.getBlockZ()),
                    Math.max(first.getBlockX(), second.getBlockX()), Math.max(first.getBlockY(), second.getBlockY()), Math.max(first.getBlockZ(), second.getBlockZ()), true);
        }
    }
}
