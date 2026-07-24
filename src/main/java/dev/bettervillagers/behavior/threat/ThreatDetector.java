package dev.bettervillagers.behavior.threat;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 威胁识别器（规范 3.1 威胁识别）。
 * <p>
 * <b>必须在区域线程调用</b>（读取实体/世界状态）。扫描村民附近的敌对生物与危险玩家。
 * <p>
 * 修复：移除 {@code getTarget()!=null} 的过严条件——怪物在附近即为威胁，
 * 战斗职业村民可主动迎击，无需等待被攻击。
 */
public final class ThreatDetector {

    /** 非法玩家元数据键（规范 3.1，跨模块共享）。 */
    public static final String ILLEGAL_META_KEY = "bv-illegal";

    private final double detectionRange;

    public ThreatDetector(double detectionRange) {
        this.detectionRange = detectionRange;
    }

    public List<Threat> scan(LivingEntity villager) {
        return scan(villager, -1);
    }

    /**
     * 扫描附近威胁（含外交：敌对村庄的村民视为威胁）。
     *
     * @param villager     扫描中心村民
     * @param myVillageId  村民所属村庄 id（-1 表示无村庄）
     */
    public List<Threat> scan(LivingEntity villager, int myVillageId) {
        List<Threat> threats = new ArrayList<>();
        Location loc = villager.getLocation();
        for (Entity nearby : villager.getNearbyEntities(detectionRange, detectionRange, detectionRange)) {
            double dist = nearby.getLocation().distanceSquared(loc);
            // 修复：所有 Monster 都是威胁（不再要求其已锁定 target）
            if (nearby instanceof Monster m && !m.isDead()) {
                threats.add(new Threat(ThreatType.HOSTILE_MOB, m, dist));
            } else if (nearby instanceof Player p && p.hasMetadata(ILLEGAL_META_KEY)
                    && !isCreativeOrSpectator(p)) {
                // 修复问题5：创造/旁观模式玩家不触发仇恨
                threats.add(new Threat(ThreatType.ILLEGAL_PLAYER, p, dist));
            } else if (nearby instanceof org.bukkit.entity.Villager v) {
                // 修复问题5：敌对村庄的村民视为威胁
                Threat enemyVillager = checkEnemyVillager(v, myVillageId, dist);
                if (enemyVillager != null) {
                    threats.add(enemyVillager);
                }
            }
        }
        threats.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        return threats;
    }

    /** 检查一个原版村民是否属于敌对村庄，是则返回威胁，否则返回 null。 */
    private Threat checkEnemyVillager(org.bukkit.entity.Villager v, int myVillageId, double dist) {
        if (myVillageId < 0 || dev.bettervillagers.BV.villagers() == null
                || dev.bettervillagers.BV.diplomacy() == null) {
            return null;
        }
        var bv = dev.bettervillagers.BV.villagers().get(v.getUniqueId().toString());
        if (bv.isEmpty() || bv.get().villageId() == myVillageId) {
            return null; // 同村庄或未注册
        }
        if (dev.bettervillagers.BV.diplomacy().areEnemies(myVillageId, bv.get().villageId())) {
            return new Threat(ThreatType.HOSTILE_MOB, v, dist);
        }
        return null;
    }

    /** 扫描指定位置附近最近的威胁（用于护卫国王：在国王位置扫描）。 */
    @SuppressWarnings("unused")
    private List<Threat> scanAround(Location center) {
        // 说明：原为 public 且与 scan() 逻辑重复（仅少敌对村民检测），全工程无调用方，已降级为 private 死代码保留。
        // 如需"在任意位置扫描威胁"能力，应复用 scan() 并提取公共 classifyEntity 方法（见规范 1.2 冗余逻辑）。
        List<Threat> threats = new ArrayList<>();
        if (center == null || center.getWorld() == null) {
            return threats;
        }
        for (Entity nearby : center.getWorld().getNearbyEntities(center, detectionRange, detectionRange, detectionRange)) {
            double dist = nearby.getLocation().distanceSquared(center);
            if (nearby instanceof Monster m && !m.isDead()) {
                threats.add(new Threat(ThreatType.HOSTILE_MOB, m, dist));
            } else if (nearby instanceof Player p && p.hasMetadata(ILLEGAL_META_KEY)
                    && !isCreativeOrSpectator(p)) {
                threats.add(new Threat(ThreatType.ILLEGAL_PLAYER, p, dist));
            }
        }
        threats.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        return threats;
    }

    /** 创造/旁观模式玩家不作为威胁目标。 */
    private static boolean isCreativeOrSpectator(Player p) {
        try {
            return p.getGameMode() == org.bukkit.GameMode.CREATIVE
                    || p.getGameMode() == org.bukkit.GameMode.SPECTATOR;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取最近的威胁目标（LivingEntity），无则 null。 */
    public LivingEntity nearestEnemy(LivingEntity villager) {
        return nearestEnemy(villager, -1);
    }

    /** 取最近的威胁目标（含外交：敌对村庄村民）。 */
    public LivingEntity nearestEnemy(LivingEntity villager, int myVillageId) {
        List<Threat> threats = scan(villager, myVillageId);
        for (Threat t : threats) {
            if (t.source() instanceof LivingEntity le && !le.isDead()) {
                return le;
            }
        }
        return null;
    }

    /** 环境危险判定（脚下岩浆/火焰/悬崖等），返回是否处于危险。 */
    public boolean environmentDanger(LivingEntity villager) {
        org.bukkit.Material mat = villager.getLocation().getBlock().getType();
        return mat == org.bukkit.Material.LAVA
                || mat == org.bukkit.Material.FIRE
                || mat == org.bukkit.Material.SOUL_FIRE
                || mat == org.bukkit.Material.CAMPFIRE
                || mat == org.bukkit.Material.SOUL_CAMPFIRE
                || cliffDanger(villager);
    }

    /** 悬崖检测：脚下 4 格内无实心方块则视为高空坠落危险。 */
    public boolean cliffDanger(LivingEntity villager) {
        Location loc = villager.getLocation();
        org.bukkit.World world = loc.getWorld();
        if (world == null) {
            return false;
        }
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        for (int dy = 1; dy <= 4; dy++) {
            if (world.getBlockAt(x, y - dy, z).getType().isSolid()) {
                return false;
            }
        }
        return true;
    }
}
