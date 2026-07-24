package dev.bettervillagers.behavior;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 安全移动辅助（规范 3.1：地形感知移动，避免穿墙/弹跳/卡方块）。
 * <p>
 * 使用 Paper {@link Mob#getPathfinder()} 原版寻路系统驱动移动，
 * 彻底消除 {@code setVelocity} 被原版 AI 每 tick 覆盖导致的"拉回"与"卡顿"问题。
 * <p>
 * 关键：引入<b>寻路节流</b>——避免在路径进行中频繁重新下发 moveTo 导致绕圈/卡顿。
 */
public final class MovementHelper {

    private MovementHelper() {
    }

    /** 寻路重计算冷却（毫秒）：同一路径至少走 2 秒才允许重新规划。 */
    private static final long REPATH_COOLDOWN_MS = 2000L;
    /** 每个实体上一次下发寻路的时间戳，用于节流。 */
    private static final Map<String, Long> lastPathTime = new ConcurrentHashMap<>();
    /**
     * 每个实体当前寻路的目标（用于判断目标是否变化）。
     * <p>仅保存世界名与坐标，避免持有 {@code Location}→{@code World} 强引用阻碍世界卸载（规范 4.x）。
     */
    private static final Map<String, Target> lastPathTarget = new ConcurrentHashMap<>();

    /** 轻量寻路目标记录（不持有 World 对象）。 */
    private record Target(String worldName, double x, double y, double z) {
        boolean samePlace(Location o) {
            return o != null && o.getWorld() != null && o.getWorld().getName().equals(worldName)
                    && new Vector(x, y, z).distanceSquared(o.toVector()) < 9.0;
        }
    }

    /**
     * 向目标移动。
     * <p>
     * 使用 {@link Mob#getPathfinder()}#moveTo 实现平滑寻路（不拉回、不穿墙）。
     * <b>节流</b>：若实体正在寻路（hasPath）或距上次规划不足 2 秒且目标未明显变化，
     * 则跳过本次下发，让当前路径走完，避免反复重算导致绕圈。
     *
     * @param self   移动实体
     * @param target 目标位置
     * @param speed  原版速度倍率（村民建议 0.3~0.5，1.0=原版步行）
     */
    public static void moveToward(LivingEntity self, Location target, double speed) {
        if (self == null || target == null || self.isDead()) {
            return;
        }
        Location loc = self.getLocation();
        double distSq = target.toVector().distanceSquared(loc.toVector());
        if (distSq < 1.0) { // 1 格内视为到达，停止下发
            return;
        }
        if (self instanceof Mob mob) {
            String key = self.getUniqueId().toString();
            long now = System.currentTimeMillis();
            // 节流：正在寻路时不要打断，除非目标显著变化或冷却已过
            if (shouldSkipRepath(mob, key, target, now, distSq)) {
                return;
            }
            try {
                // 原版村民基础速度约 0.5，直接用 speed 作为倍率，不再放大
                double pfSpeed = Math.max(0.3, Math.min(0.6, speed));
                mob.getPathfinder().moveTo(target, pfSpeed);
                lastPathTime.put(key, now);
                lastPathTarget.put(key, new Target(
                        target.getWorld() == null ? null : target.getWorld().getName(),
                        target.getX(), target.getY(), target.getZ()));
            } catch (Throwable ignored) {
                velocityMoveToward(self, target, speed);
            }
            return;
        }
        velocityMoveToward(self, target, speed);
    }

    /**
     * 判断是否应跳过本次寻路下发（节流逻辑）。
     * <p>
     * 满足以下任一条件才允许重新规划：
     * - 实体当前没有在寻路（hasPath=false），且
     * - 距上次规划已超过冷却时间，或目标位置变化超过 3 格。
     */
    private static boolean shouldSkipRepath(Mob mob, String key, Location target, long now, double distSq) {
        try {
            // 仍在走当前路径 → 不打断
            if (mob.getPathfinder().hasPath()) {
                Long last = lastPathTime.get(key);
                if (last != null && now - last < REPATH_COOLDOWN_MS) {
                    return true; // 跳过
                }
            }
        } catch (Throwable ignored) {
            // hasPath 不可用时退化为时间节流
        }
        // 目标未显著变化（<3格）且冷却未过 → 跳过
        Target prev = lastPathTarget.get(key);
        Long last = lastPathTime.get(key);
        if (prev != null && last != null && now - last < REPATH_COOLDOWN_MS) {
            if (prev.samePlace(target)) {
                return true;
            }
        }
        return false;
    }

    /** 清理实体的寻路节流状态。 */
    public static void clear(String uuid) {
        lastPathTime.remove(uuid);
        lastPathTarget.remove(uuid);
    }

    /**
     * 远离威胁位置逃离。
     */
    public static void flee(LivingEntity self, Location source, double speed) {
        if (self == null || source == null || self.isDead()) {
            return;
        }
        Location loc = self.getLocation();
        Vector away = loc.toVector().subtract(source.toVector());
        if (away.lengthSquared() < 0.01) {
            away = new Vector(
                    ThreadLocalRandom.current().nextDouble(-1, 1),
                    0,
                    ThreadLocalRandom.current().nextDouble(-1, 1));
        }
        away.setY(0).normalize().multiply(8.0);
        Location fleeTarget = loc.clone().add(away);
        moveToward(self, fleeTarget, speed);
    }

    /**
     * 前往固定的巡逻锚点（基于世界坐标，不随村民移动漂移）。
     * <p>
     * 修复绕圈：巡逻目标使用固定坐标而非相对当前位置，避免目标随移动漂移导致绕回原点。
     *
     * @param self    移动实体
     * @param anchor  固定锚点（世界坐标）
     * @param speed   原版速度倍率
     */
    public static void patrolTo(LivingEntity self, Location anchor, double speed) {
        moveToward(self, anchor, speed);
    }

    /** setVelocity 回退方案（仅在 pathfinder 不可用时使用）。 */
    private static void velocityMoveToward(LivingEntity self, Location target, double speed) {
        Vector dir = target.toVector().subtract(self.getLocation().toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 0.01) {
            return;
        }
        dir.normalize().multiply(Math.max(0.2, Math.min(0.4, speed)));
        self.setVelocity(dir);
    }
}
