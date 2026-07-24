package dev.bettervillagers.behavior.block;

import dev.bettervillagers.BV;
import dev.bettervillagers.villager.BVillager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 方块交互引擎（规范 3.2：职业白名单、行动点数冷却、区域线程提交、变更日志可回滚）。
 * <p>
 * 所有破坏/放置的最终 setType 均经 {@code scheduler.runAtRegion} 提交到坐标所属区域线程；
 * 寻路与地形分析在外部异步完成，仅最终写操作回到区域线程（规范 4.1）。
 * <p>
 * 规范 4.x：{@link BlockChange} 仅保存世界名与整型坐标，避免长期持有 {@code Location}→{@code World}
 * 强引用导致世界无法卸载；变更日志有上限，超出自动丢弃最旧条目。
 */
public final class BlockInteractionEngine {

    private static final double BREAK_COST = 10.0;
    private static final double PLACE_COST = 8.0;
    private static final double INTERACT_COST = 2.0;
    /** 变更日志上限：超出自动丢弃最旧条目，防止村民自治建造下无界增长（规范 4.x）。 */
    private static final int MAX_CHANGES = 2000;

    /** 变更日志（规范 3.2：可按村庄/时间段批量回滚）。 */
    private final ConcurrentLinkedDeque<BlockChange> changeLog = new ConcurrentLinkedDeque<>();
    private final long cooldownMillis;

    public BlockInteractionEngine(long cooldownSeconds) {
        this.cooldownMillis = cooldownSeconds * 1000L;
    }

    /** 在指定坐标放置方块（受白名单与冷却约束）。 */
    public void placeAt(BVillager bv, Location location, Material material) {
        if (disallowed(bv, location, material, BVillager.OP_PLACE)) {
            return;
        }
        if (!bv.consumeActionPoints(PLACE_COST)) {
            return;
        }
        bv.lastBlockOp(BVillager.OP_PLACE, System.currentTimeMillis());
        // 在区域线程提交前，先提取纯值用于日志记录（避免日志持有 Location→World 强引用）
        final String worldName = location.getWorld() == null ? null : location.getWorld().getName();
        final int x = location.getBlockX();
        final int y = location.getBlockY();
        final int z = location.getBlockZ();
        BV.scheduler().runAtRegion(location, () -> {
            Block block = location.getBlock();
            Material before = block.getType();
            // applyPhysics=false：职业操作为单块交互，无需触发邻居物理级联（规范 5.2）
            block.setType(material, false);
            recordChange(new BlockChange(worldName, x, y, z, before, material, System.currentTimeMillis()));
        });
    }

    /** 破坏指定坐标方块（受白名单约束，模拟掉落由原版处理）。 */
    public void breakAt(BVillager bv, Location location) {
        Material target = location.getBlock().getType();
        if (disallowed(bv, location, target, BVillager.OP_BREAK)) {
            return;
        }
        if (!bv.consumeActionPoints(BREAK_COST)) {
            return;
        }
        bv.lastBlockOp(BVillager.OP_BREAK, System.currentTimeMillis());
        final String worldName = location.getWorld() == null ? null : location.getWorld().getName();
        final int x = location.getBlockX();
        final int y = location.getBlockY();
        final int z = location.getBlockZ();
        BV.scheduler().runAtRegion(location, () -> {
            Block block = location.getBlock();
            Material before = block.getType();
            // applyPhysics=false：避免破坏触发邻居物理级联（规范 5.2）
            block.setType(Material.AIR, false);
            recordChange(new BlockChange(worldName, x, y, z, before, Material.AIR, System.currentTimeMillis()));
        });
    }

    /** 与指定坐标方块交互（受白名单与冷却约束，无方块变更）。 */
    public void interactAt(BVillager bv, Location location, Material material) {
        if (disallowed(bv, location, material, BVillager.OP_INTERACT)) {
            return;
        }
        if (!bv.consumeActionPoints(INTERACT_COST)) {
            return;
        }
        bv.lastBlockOp(BVillager.OP_INTERACT, System.currentTimeMillis());
    }

    /** 权限/白名单/冷却/保护区综合校验（四类操作独立冷却，规范 3.2）。 */
    private boolean disallowed(BVillager bv, Location location, Material material, int opKind) {
        // 生电保护区内禁止（规范 5.2）
        if (BV.regions() != null && BV.regions().isProtected(location)) {
            return true;
        }
        // 白名单校验（规范 3.2）
        if (!BV.professions().isWhitelisted(bv.profession(), material)) {
            return true;
        }
        // 四类操作独立冷却（规范 3.2）
        long now = System.currentTimeMillis();
        return now - bv.lastBlockOp(opKind) < cooldownMillis;
    }

    /** 读取方块类型（区域线程内调用）。 */
    public Material readAt(Location location) {
        return location.getBlock().getType();
    }

    /** 记录变更并保持有界（丢弃最旧条目，规范 4.x）。 */
    private void recordChange(BlockChange c) {
        changeLog.addLast(c);
        while (changeLog.size() > MAX_CHANGES) {
            if (changeLog.pollFirst() == null) {
                break;
            }
        }
    }

    /** 回滚最近 N 次变更（规范 3.2 回滚机制）。 */
    public void rollback(int count) {
        int n = Math.min(count, changeLog.size());
        for (int i = 0; i < n; i++) {
            BlockChange c = changeLog.pollLast();
            if (c == null) {
                break;
            }
            org.bukkit.Location loc = c.toLocation();
            if (loc == null) {
                continue; // 世界已卸载，跳过该条回滚
            }
            // 回滚同样不触发物理级联（规范 5.2）
            BV.scheduler().runAtRegion(loc, () -> loc.getBlock().setType(c.before(), false));
        }
    }

    public int pendingChanges() {
        return changeLog.size();
    }

    /**
     * 方块变更记录（仅保存世界名与整型坐标，不持有 {@code World}/{@code Location} 对象引用，
     * 避免阻碍世界卸载——规范 4.x 内存管理）。
     */
    private record BlockChange(String worldName, int x, int y, int z, Material before, Material after, long time) {
        org.bukkit.Location toLocation() {
            World w = Bukkit.getWorld(worldName);
            if (w == null) {
                return null;
            }
            return new org.bukkit.Location(w, x, y, z);
        }
    }
}
