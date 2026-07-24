package dev.bettervillagers.behavior;

import dev.bettervillagers.BV;
import dev.bettervillagers.behavior.block.BlockInteractionEngine;
import dev.bettervillagers.behavior.pathfinding.AsyncPathfinder;
import dev.bettervillagers.behavior.reflex.ReflexEngine;
import dev.bettervillagers.behavior.strategic.StrategicAI;
import dev.bettervillagers.behavior.tactical.TacticalAI;
import dev.bettervillagers.behavior.threat.ThreatDetector;
import dev.bettervillagers.villager.BVillager;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.List;

/**
 * 行为引擎门面（规范 3.1：反射层/战术层/战略层三层决策混合架构）。
 * <p>
 * 修复：快速战斗 tick 中，非战斗职业求援时立即派附近战斗职业前往保护；
 * 国王受威胁时，附近骑士/士兵 0.5s 内赶到护卫（不再等 60s strategic tick）。
 */
public final class BehaviorEngine {

    /** 非战斗职业恢复日常的威胁判定距离平方（6 格，与 ReflexEngine.CIVILIAN_FLEE_RANGE_SQ 一致）。 */
    private static final double FLEE_CLEAR_RANGE_SQ = 36.0;
    /** 护卫派遣距离平方（50 格）。 */
    private static final double GUARD_DISPATCH_RANGE_SQ = 2500.0;

    private final ThreatDetector threatDetector;
    private final ReflexEngine reflex;
    private final TacticalAI tactical;
    private final StrategicAI strategic;
    private final BlockInteractionEngine blocks;
    private final AsyncPathfinder pathfinder = new AsyncPathfinder();

    public BehaviorEngine(int detectionRange, long tacticalIntervalSec, long strategicIntervalSec, long blockCooldownSec) {
        this.threatDetector = new ThreatDetector(detectionRange);
        this.reflex = new ReflexEngine(threatDetector);
        this.tactical = new TacticalAI(threatDetector, tacticalIntervalSec * 1000L);
        this.strategic = new StrategicAI(strategicIntervalSec * 1000L);
        this.blocks = new BlockInteractionEngine(blockCooldownSec);
    }

    /** 战术层 tick。 */
    public void tickTactical(BVillager bv) {
        if (bv.entity() != null && threatDetector.environmentDanger(bv.entity())) {
            reflex.reactToEnvironmentDanger(bv);
            return;
        }
        // 修复问题2：战术层仅战斗职业做主动迎击；非战斗职业不因远处怪物反复逃跑
        if (BV.config().feature("self-defense")
                && ReflexEngine.isCombatant(bv.profession())
                && reflex.proactiveCombat(bv)) {
            return;
        }
        // 非战斗职业若处于逃跑但附近已无近距威胁，恢复日常
        if (!ReflexEngine.isCombatant(bv.profession())
                && bv.state() == VillagerState.FLEEING
                && bv.entity() != null) {
            LivingEntity near = threatDetector.nearestEnemy(bv.entity(), bv.villageId());
            if (near == null
                    || !near.getWorld().equals(bv.entity().getWorld())
                    || near.getLocation().distanceSquared(bv.entity().getLocation()) > FLEE_CLEAR_RANGE_SQ) {
                bv.state(VillagerState.IDLE);
            }
        }
        tactical.decide(bv);
    }

    /**
     * 快速战斗 tick（每 0.5s）。
     * <p>
     * 护卫仅在国王受威胁时赶赴保护，解决威胁后自动撤离返回岗位。
     * 非战斗职业遭遇威胁时自行逃离（不再召唤护卫到自身位置）。
     */
    public void tickCombat(BVillager bv) {
        LivingEntity self = bv.entity();
        if (self == null || self.isDead()) {
            return;
        }
        // 修复问题4：战斗职业每 tick 检查主手武器是否缺失，缺失则重新装备
        if (ReflexEngine.isCombatant(bv.profession()) && bv.professionData() != null) {
            ensureEquipped(self, bv);
        }

        // 修复护卫行为：战斗职业若处于 COMBAT 但附近无敌人，则撤离返回岗位
        if (ReflexEngine.isCombatant(bv.profession()) && bv.state() == VillagerState.COMBAT) {
            LivingEntity nearbyEnemy = threatDetector.nearestEnemy(self);
            if (nearbyEnemy == null) {
                bv.state(VillagerState.IDLE);
            }
        }

        // 执行自身战斗/逃离
        reflex.combatTick(bv);

        // 仅国王受威胁时派遣护卫（不再为普通村民派遣，避免护卫四处乱跑）
        if (bv.profession() == dev.bettervillagers.profession.Profession.KING) {
            LivingEntity enemy = threatDetector.nearestEnemy(self, bv.villageId());
            if (enemy != null && !enemy.isDead()) {
                dispatchGuards(self.getLocation(), enemy, bv.villageId());
            }
        }
    }

    /** 确保战斗职业村民主手持有武器（修复问题4：装备可能被原版逻辑重置）。 */
    private void ensureEquipped(LivingEntity self, BVillager bv) {
        try {
            var eq = self.getEquipment();
            if (eq == null) {
                return;
            }
            var mainHand = eq.getItemInMainHand();
            if (mainHand == null || mainHand.getType().isAir()) {
                // 主手为空，重新装备
                dev.bettervillagers.profession.EquipmentApplier.apply(self, bv.professionData());
            }
        } catch (Throwable t) {
            // 装备重置失败仅记录，不影响战斗 tick 主流程（规范 3.3：禁止静默吞异常）
            BV.plugin().getLogger().warning(
                    BV.messages().raw("log.tactical-tick-error")
                            .replace("{uuid}", bv.uuid()).replace("{error}", "equip: " + t));
        }
    }

    /**
     * 派遣附近的战斗职业村民前往保护受威胁位置。
     *
     * @param threatenedLoc 受威胁位置
     * @param enemy         威胁来源
     * @param villageId     村庄 id（同村庄优先）
     */
    private void dispatchGuards(Location threatenedLoc, LivingEntity enemy, int villageId) {
        for (BVillager guard : BV.villagers().all()) {
            if (guard.entity() == null || guard.entity().isDead()) {
                continue;
            }
            if (!ReflexEngine.isCombatant(guard.profession())) {
                continue;
            }
            // 跨世界 distanceSquared 会抛 IllegalArgumentException，先校验世界一致（规范 5.x）
            if (!guard.entity().getWorld().equals(threatenedLoc.getWorld())) {
                continue;
            }
            // 同村庄优先，不同村庄也可（50 格内）
            double d = guard.entity().getLocation().distanceSquared(threatenedLoc);
            if (d < GUARD_DISPATCH_RANGE_SQ) {
                reflex.defendKing(guard, threatenedLoc, enemy);
            }
        }
    }

    /** 战略层 tick（仅国王）。 */
    public void tickStrategic(BVillager king) {
        strategic.decide(king);
    }

    /** 反射层：受击瞬间。 */
    public void onDamaged(BVillager bv, Entity source) {
        reflex.reactToDamage(bv, source);
        if (source != null && source instanceof org.bukkit.entity.Player p) {
            // 修复问题5：创造/旁观模式玩家不标记为非法、不触发仇恨
            try {
                if (p.getGameMode() == org.bukkit.GameMode.CREATIVE
                        || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                    return;
                }
            } catch (Throwable t) {
                BV.plugin().getLogger().warning(
                        BV.messages().raw("log.tactical-tick-error")
                                .replace("{uuid}", p.getName()).replace("{error}", "gamemode: " + t));
            }
            markIllegalPlayer(p);
        }
    }

    public ThreatDetector threatDetector() {
        return threatDetector;
    }

    public BlockInteractionEngine blocks() {
        return blocks;
    }

    public AsyncPathfinder pathfinder() {
        return pathfinder;
    }

    public void clearVillagerState(String uuid) {
        reflex.clear(uuid);
        MovementHelper.clear(uuid);
    }

    public void shutdown() {
        pathfinder.shutdown();
    }

    private static void markIllegalPlayer(org.bukkit.entity.Player p) {
        p.setMetadata(ThreatDetector.ILLEGAL_META_KEY, new org.bukkit.metadata.FixedMetadataValue(BV.plugin(), System.currentTimeMillis()));
    }
}
