package dev.bettervillagers.redstone;

import dev.bettervillagers.BV;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 保护区粒子可视化（规范 5.4：粒子效果显示保护区边界）。
 * <p>
 * 沿保护区顶点描绘粒子边框；在世界区域线程执行（粒子属于世界交互）。
 */
public final class RegionVisualizer {

    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.fromRGB(0xFF, 0xA5, 0x00), 1.5f);

    public void showBoundary(Player player, ProtectedRegion region) {
        World world = org.bukkit.Bukkit.getWorld(region.world());
        if (world == null) {
            return;
        }
        Location corner = new Location(world, region.minX(), region.minY(), region.minZ());
        BV.scheduler().runAtRegion(corner, () -> draw(player, world, region));
    }

    private void draw(Player player, World world, ProtectedRegion r) {
        int step = 3;
        for (int x = r.minX(); x <= r.maxX(); x += step) {
            line(player, world, x, r.minY(), r.minZ(), x, r.minY(), r.maxZ());
            line(player, world, x, r.maxY(), r.minZ(), x, r.maxY(), r.maxZ());
        }
        for (int z = r.minZ(); z <= r.maxZ(); z += step) {
            line(player, world, r.minX(), r.minY(), z, r.maxX(), r.minY(), z);
            line(player, world, r.minX(), r.maxY(), z, r.maxX(), r.maxY(), z);
        }
    }

    private void line(Player player, World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int dz = z2 - z1;
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) / 2;
        if (steps <= 0) {
            steps = 1;
        }
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Location loc = new Location(world, x1 + dx * t + 0.5, y1 + dy * t + 0.5, z1 + dz * t + 0.5);
            player.spawnParticle(Particle.DUST, loc, 2, 0, 0, 0, 0, DUST);
        }
    }
}
