package dev.bettervillagers.behavior.task;

import dev.bettervillagers.BV;
import dev.bettervillagers.behavior.MovementHelper;
import dev.bettervillagers.behavior.VillagerState;
import dev.bettervillagers.behavior.threat.ThreatDetector;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.villager.BVillager;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.List;

/**
 * 军事职业任务（规范 3.3：骑士/士兵/弓箭手）。
 * <p>
 * 职责：
 * <ol>
 *   <li><b>编组化边境巡逻</b>：沿 {@link PatrolRouter} 生成的村庄边境闭合路线循环推进，
 *       起始航点按 UUID 哈希分配，使同村军事村民沿同一方向均匀分布于边境线，形成有组织的巡逻队形。</li>
 *   <li><b>协同防卫</b>：任一军事村民感知到敌对威胁时，附近编组同伴立即向威胁位置集结协防；
 *       威胁解除后自动返回巡逻路线。</li>
 * </ol>
 * 战斗交战（攻击/射箭/伤害）由反射层 {@code ReflexEngine.combatTick} 负责（毫秒级），
 * 本任务仅负责巡逻推进与协防集结的移动决策，二者通过 FSM 状态无缝衔接。
 * <p>
 * 必须在实体所在区域线程调用（读取实体/世界状态）。
 */
public final class MilitaryTask {

    /** 到达航点的判定半径（格，平方）。 */
    private static final double ARRIVE_RADIUS_SQ = 9.0;
    /** 巡逻移动速度（原版村民步行倍率）。 */
    private static final double PATROL_SPEED = 0.4;
    /** 协防集结感知半径（格，平方）。 */
    private static final double RALLY_RANGE_SQ = 1600.0; // 40 格

    private final PatrolRouter patrolRouter;
    private final ThreatDetector threatDetector;

    public MilitaryTask(PatrolRouter patrolRouter, ThreatDetector threatDetector) {
        this.patrolRouter = patrolRouter;
        this.threatDetector = threatDetector;
    }

    /**
     * 执行一次军事任务决策（巡逻 / 协防集结）。
     *
     * @param bv 军事职业村民
     */
    public void execute(BVillager bv) {
        LivingEntity self = bv.entity();
        if (self == null || self.isDead()) {
            return;
        }
        // 战斗中或逃离中交由反射层处理，不打断
        VillagerState st = bv.state();
        if (st == VillagerState.COMBAT || st == VillagerState.FLEEING) {
            return;
        }
        // 社交攀谈中不打断
        if (st == VillagerState.SOCIALIZING) {
            return;
        }

        // 1. 优先协防：若感知到威胁，集结附近同伴前往威胁位置
        if (rallyToThreat(bv, self)) {
            return;
        }

        // 2. 常态：编组化边境巡逻
        patrol(bv, self);
    }

    /**
     * 协防集结：感知到敌对威胁时，召唤附近编组同伴并自身前往威胁位置。
     * 仅做移动决策（接近敌人），实际攻击由反射层负责。
     */
    private boolean rallyToThreat(BVillager bv, LivingEntity self) {
        LivingEntity enemy = threatDetector.nearestEnemy(self, bv.villageId());
        if (enemy == null || enemy.isDead()) {
            return false;
        }
        // D5 修复：跨世界 distanceSquared 会抛 IllegalArgumentException，先校验世界一致性
        if (self.getWorld() != enemy.getWorld()) {
            return false;
        }
        double distSq = self.getLocation().distanceSquared(enemy.getLocation());
        // 进入交战距离范围则交给反射层（设置 COMBAT 状态）
        if (distSq <= RALLY_RANGE_SQ) {
            bv.state(VillagerState.COMBAT);
            MovementHelper.moveToward(self, enemy.getLocation(), PATROL_SPEED);
            // 协防召唤：附近同村军事村民向威胁集结（编组协同防卫）
            rallyNearby(bv, enemy.getLocation());
            return true;
        }
        return false;
    }

    /** 召唤附近同村军事职业村民向威胁位置集结。 */
    private void rallyNearby(BVillager caller, Location threatLoc) {
        if (BV.villagers() == null) {
            return;
        }
        for (BVillager ally : BV.villagers().all()) {
            if (ally.uuid().equals(caller.uuid())) {
                continue;
            }
            if (!isMilitary(ally.profession())) {
                continue;
            }
            if (ally.villageId() != caller.villageId()) {
                continue;
            }
            LivingEntity allyEnt = ally.entity();
            if (allyEnt == null || allyEnt.isDead()) {
                continue;
            }
            VillagerState allySt = ally.state();
            // 仅召回非战斗/非逃跑中的同伴协防
            if (allySt == VillagerState.COMBAT || allySt == VillagerState.FLEEING) {
                continue;
            }
            // D5 修复：跨世界 distanceSquared 会抛 IllegalArgumentException，先校验世界一致性
            if (allyEnt.getWorld() != threatLoc.getWorld()) {
                continue;
            }
            double d = allyEnt.getLocation().distanceSquared(threatLoc);
            if (d <= RALLY_RANGE_SQ) {
                // D1 修复：Folia 多区域线程下，实体操控必须在实体所属区域线程执行。
                // 此处仅做只读距离判断，写状态/移动操作 dispatch 到 ally 自身区域线程。
                BV.scheduler().runForEntity(allyEnt, () -> {
                    ally.state(VillagerState.COMBAT);
                    MovementHelper.moveToward(allyEnt, threatLoc, PATROL_SPEED);
                }, null);
            }
        }
    }

    /** 编组化边境巡逻：沿村庄边境闭合路线循环推进。 */
    private void patrol(BVillager bv, LivingEntity self) {
        List<Location> route = patrolRouter.routeFor(bv.villageId());
        if (route.isEmpty()) {
            // 无村庄路线时回退到固定巡逻锚点（避免卡死）
            bv.state(VillagerState.PATROLING);
            Location anchor = bv.patrolAnchor();
            if (anchor != null) {
                MovementHelper.patrolTo(self, anchor, PATROL_SPEED);
            }
            return;
        }
        bv.state(VillagerState.PATROLING);
        int idx = bv.patrolIndex();
        if (idx < 0 || idx >= route.size()) {
            idx = 0;
        }
        Location target = route.get(idx);
        if (target.getWorld() == null || !target.getWorld().equals(self.getWorld())) {
            return;
        }
        double distSq = self.getLocation().distanceSquared(target);
        if (distSq <= ARRIVE_RADIUS_SQ) {
            // 到达当前航点，推进到下一个（循环闭合）
            idx = (idx + 1) % route.size();
            bv.patrolIndex(idx);
            target = route.get(idx);
        }
        MovementHelper.patrolTo(self, target, PATROL_SPEED);
    }

    /** 判定是否为军事职业（参与编组巡逻与协防）。 */
    public static boolean isMilitary(Profession prof) {
        return prof == Profession.KNIGHT || prof == Profession.SOLDIER || prof == Profession.ARCHER;
    }
}
