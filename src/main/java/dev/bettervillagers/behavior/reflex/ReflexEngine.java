package dev.bettervillagers.behavior.reflex;

import dev.bettervillagers.BV;
import dev.bettervillagers.behavior.MovementHelper;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.villager.BVillager;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 反射层规则引擎（规范 3.1：毫秒级，无 AI，紧急生存反应零延迟）。
 * <p>
 * <b>必须在区域线程调用</b>（事件监听器 / 行为引擎 tick 直接调用，不经过 AI 调度）。
 * 移动通过 {@link MovementHelper} 实现（地形感知，不弹跳/穿墙）。
 * <p>
 * 修复：战斗职业持续追击敌对生物并挥武器攻击；
 * 护卫国王时优先攻击威胁国王的敌人。
 */
public final class ReflexEngine {

    private final dev.bettervillagers.behavior.threat.ThreatDetector threatDetector;

    private final Map<String, Long> attackCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> shootCooldowns = new ConcurrentHashMap<>();
    private static final long MELEE_COOLDOWN = 1000L;
    private static final long RANGED_COOLDOWN = 1500L;

    public ReflexEngine(dev.bettervillagers.behavior.threat.ThreatDetector threatDetector) {
        this.threatDetector = threatDetector;
    }

    /**
     * 快速战斗 tick（每 0.5s 由 BehaviorEngine.tickCombat 调用）。
     * 战斗职业追击攻击；非战斗职业逃离求援。
     */
    /** 非战斗职业仅在此距离内（格）逃跑；远处怪物忽略。 */
    private static final double CIVILIAN_FLEE_RANGE_SQ = 36.0; // 6 格
    /** 战斗职业主动交战距离（格）。 */
    private static final double COMBAT_ENGAGE_RANGE_SQ = 256.0; // 16 格
    /** 战斗移动速度（原硬编码 0.4，规范：魔法值统一为常量）。 */
    private static final double COMBAT_SPEED = 0.4;

    /** 跨世界安全距离平方：不同世界返回 {@link Double#MAX_VALUE}，调用方据此提前 return。 */
    private static double distSqSameWorld(LivingEntity a, LivingEntity b) {
        if (!a.getWorld().equals(b.getWorld())) {
            return Double.MAX_VALUE;
        }
        return a.getLocation().distanceSquared(b.getLocation());
    }

    public void combatTick(BVillager bv) {
        LivingEntity self = bv.entity();
        if (self == null || self.isDead()) {
            return;
        }
        LivingEntity enemy = threatDetector.nearestEnemy(self, bv.villageId());
        if (enemy == null) {
            return;
        }
        double distSq = distSqSameWorld(self, enemy);
        if (isCombatant(bv.profession())) {
            if (distSq > COMBAT_ENGAGE_RANGE_SQ) {
                return; // 太远不追，避免全图奔袭
            }
            bv.state(dev.bettervillagers.behavior.VillagerState.COMBAT);
            engage(self, enemy, bv);
        } else {
            // 修复问题2：非战斗职业仅近距威胁才逃，避免全天逃跑
            if (distSq > CIVILIAN_FLEE_RANGE_SQ) {
                return;
            }
            MovementHelper.flee(self, enemy.getLocation(), COMBAT_SPEED);
            bv.state(dev.bettervillagers.behavior.VillagerState.FLEEING);
        }
    }

    public boolean proactiveCombat(BVillager bv) {
        LivingEntity self = bv.entity();
        if (self == null || self.isDead()) {
            return false;
        }
        // 主动迎击仅限战斗职业
        if (!isCombatant(bv.profession())) {
            return false;
        }
        LivingEntity enemy = threatDetector.nearestEnemy(self, bv.villageId());
        if (enemy == null) {
            return false;
        }
        double distSq = distSqSameWorld(self, enemy);
        if (distSq > COMBAT_ENGAGE_RANGE_SQ) {
            return false;
        }
        bv.state(dev.bettervillagers.behavior.VillagerState.COMBAT);
        engage(self, enemy, bv);
        return true;
    }

    /** 战斗职业与敌人交战。 */
    private void engage(LivingEntity self, LivingEntity enemy, BVillager bv) {
        double distSq = distSqSameWorld(self, enemy);
        if (distSq == Double.MAX_VALUE) {
            return; // 跨世界，安全退出
        }
        double attack = bv.professionData() != null ? bv.professionData().stats().attack() : 5.0;

        if (bv.profession() == Profession.ARCHER) {
            // 弓箭手：保持距离
            if (distSq < 64.0) {
                MovementHelper.flee(self, enemy.getLocation(), COMBAT_SPEED);
            } else if (distSq > COMBAT_ENGAGE_RANGE_SQ) {
                MovementHelper.moveToward(self, enemy.getLocation(), COMBAT_SPEED);
            }
            if (distSq <= 400.0 && canShoot(bv.uuid())) {
                shootArrow(self, enemy);
                recordShoot(bv.uuid());
            }
            return;
        }
        // 近战：追击
        MovementHelper.moveToward(self, enemy.getLocation(), COMBAT_SPEED);
        if (distSq < 4.0 && canAttack(bv.uuid())) {
            self.swingMainHand();
            enemy.damage(attack, self);
            recordAttack(bv.uuid());
            // 击退
            Vector knockback = enemy.getLocation().toVector().subtract(self.getLocation().toVector());
            if (knockback.lengthSquared() > 0.01) {
                knockback.setY(0).normalize().multiply(0.4);
                enemy.setVelocity(knockback);
            }
        }
    }

    private void shootArrow(LivingEntity shooter, LivingEntity target) {
        Location eye = shooter.getEyeLocation();
        Vector dir = target.getEyeLocation().toVector().subtract(eye.toVector());
        if (dir.lengthSquared() < 0.01) {
            return;
        }
        dir.normalize().multiply(2.5);
        Projectile arrow = shooter.launchProjectile(org.bukkit.entity.Arrow.class, dir);
        arrow.setShooter(shooter);
    }

    public void reactToDamage(BVillager bv, Entity source) {
        LivingEntity self = bv.entity();
        if (self == null || source == null) {
            return;
        }
        if (isCombatant(bv.profession()) && source instanceof LivingEntity enemy) {
            bv.state(dev.bettervillagers.behavior.VillagerState.COMBAT);
            engage(self, enemy, bv);
        } else {
            // 修复问题2：受击非战斗职业仅逃离，不呼叫支援
            MovementHelper.flee(self, source.getLocation(), COMBAT_SPEED);
        }
    }

    public void reactToEnvironmentDanger(BVillager bv) {
        LivingEntity self = bv.entity();
        if (self == null) {
            return;
        }
        Location away = self.getLocation().add(
                ThreadLocalRandom.current().nextDouble(-4, 4),
                0,
                ThreadLocalRandom.current().nextDouble(-4, 4));
        MovementHelper.moveToward(self, away, COMBAT_SPEED);
        bv.state(dev.bettervillagers.behavior.VillagerState.FLEEING);
    }

    /** 护卫国王：赶赴威胁位置并攻击。 */
    public void defendKing(BVillager guard, Location kingLoc, LivingEntity threat) {
        LivingEntity self = guard.entity();
        if (self == null || threat == null || !isCombatant(guard.profession())) {
            return;
        }
        // 跨世界 distanceSquared 会抛 IllegalArgumentException，先校验（threat 由调用方传入）
        if (!self.getWorld().equals(threat.getWorld())) {
            return;
        }
        guard.state(dev.bettervillagers.behavior.VillagerState.COMBAT);
        double distSq = self.getLocation().distanceSquared(threat.getLocation());
        if (distSq < 64.0) {
            engage(self, threat, guard);
        } else {
            MovementHelper.moveToward(self, threat.getLocation(), COMBAT_SPEED);
        }
    }

    public void clear(String uuid) {
        attackCooldowns.remove(uuid);
        shootCooldowns.remove(uuid);
    }

    public static boolean isCombatant(Profession prof) {
        return switch (prof) {
            case KNIGHT, SOLDIER, ARCHER, KING, BUTCHER -> true;
            default -> false;
        };
    }

    private boolean canAttack(String uuid) {
        Long last = attackCooldowns.get(uuid);
        return last == null || System.currentTimeMillis() - last >= MELEE_COOLDOWN;
    }

    private void recordAttack(String uuid) {
        attackCooldowns.put(uuid, System.currentTimeMillis());
    }

    private boolean canShoot(String uuid) {
        Long last = shootCooldowns.get(uuid);
        return last == null || System.currentTimeMillis() - last >= RANGED_COOLDOWN;
    }

    private void recordShoot(String uuid) {
        shootCooldowns.put(uuid, System.currentTimeMillis());
    }
}
