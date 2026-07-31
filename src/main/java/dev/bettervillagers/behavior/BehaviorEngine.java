package dev.bettervillagers.behavior;

import dev.bettervillagers.BV;
import dev.bettervillagers.behavior.block.BlockInteractionEngine;
import dev.bettervillagers.behavior.reflex.ReflexEngine;
import dev.bettervillagers.behavior.strategic.StrategicAI;
import dev.bettervillagers.behavior.tactical.TacticalAI;
import dev.bettervillagers.behavior.threat.ThreatDetector;
import dev.bettervillagers.profession.EquipmentApplier;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.villager.BVillager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public final class BehaviorEngine {

    private static final double FLEE_CLEAR_RANGE_SQ = 36.0;
    private static final double GUARD_DISPATCH_RANGE_SQ = 2500.0;

    private final ThreatDetector threatDetector;
    private final ReflexEngine reflex;
    private final TacticalAI tactical;
    private final StrategicAI strategic;
    private final BlockInteractionEngine blocks;
    private volatile boolean shutdown;

    public BehaviorEngine(int detectionRange, long tacticalIntervalSec, long strategicIntervalSec, long blockCooldownSec) {
        this.threatDetector = new ThreatDetector(detectionRange);
        this.reflex = new ReflexEngine(threatDetector);
        this.tactical = new TacticalAI(threatDetector, tacticalIntervalSec * 1000L);
        this.strategic = new StrategicAI(strategicIntervalSec * 1000L);
        this.blocks = new BlockInteractionEngine(blockCooldownSec);
    }

    public void tickTactical(BVillager bv) {
        if (shutdown || bv.state() == VillagerState.SOCIALIZING || bv.state() == VillagerState.TRADING) {
            return;
        }
        LivingEntity entity = bv.entity();
        if (entity != null && threatDetector.environmentDanger(entity)) {
            reflex.reactToEnvironmentDanger(bv);
            return;
        }
        if (BV.config().feature("self-defense")
                && ReflexEngine.isCombatant(bv.profession())
                && reflex.proactiveCombat(bv)) {
            return;
        }
        if (!ReflexEngine.isCombatant(bv.profession())
                && bv.state() == VillagerState.FLEEING
                && entity != null
                && clearToResumeDaily(entity, bv.villageId())) {
            bv.state(VillagerState.IDLE);
        }
        tactical.decide(bv);
    }

    private boolean clearToResumeDaily(LivingEntity self, int villageId) {
        LivingEntity near = threatDetector.nearestEnemy(self, villageId);
        return near == null
                || !near.getWorld().equals(self.getWorld())
                || near.getLocation().distanceSquared(self.getLocation()) > FLEE_CLEAR_RANGE_SQ;
    }

    public void tickCombat(BVillager bv) {
        LivingEntity self = bv.entity();
        if (self == null || self.isDead()) {
            return;
        }
        if (ReflexEngine.isCombatant(bv.profession()) && bv.professionData() != null) {
            ensureEquipped(self, bv);
        }
        if (ReflexEngine.isCombatant(bv.profession())
                && bv.state() == VillagerState.COMBAT
                && threatDetector.nearestEnemy(self, bv.villageId()) == null) {
            bv.state(VillagerState.IDLE);
        }

        reflex.combatTick(bv);

        if (bv.profession() != Profession.KING) {
            return;
        }
        LivingEntity enemy = threatDetector.nearestEnemy(self, bv.villageId());
        if (enemy != null && !enemy.isDead()) {
            dispatchGuards(self.getLocation(), enemy);
        }
    }

    private void ensureEquipped(LivingEntity self, BVillager bv) {
        try {
            var equipment = self.getEquipment();
            if (equipment == null || !equipment.getItemInMainHand().getType().isAir()) {
                return;
            }
            EquipmentApplier.apply(self, bv.professionData());
        } catch (Throwable t) {
            BV.plugin().getLogger().warning(
                    BV.messages().raw("log.tactical-tick-error")
                            .replace("{uuid}", bv.uuid())
                            .replace("{error}", "equip: " + t));
        }
    }

    private void dispatchGuards(Location threatenedLoc, LivingEntity enemy) {
        for (BVillager guard : BV.villagers().all()) {
            LivingEntity guardEntity = guard.entity();
            if (guardEntity == null || guardEntity.isDead() || !ReflexEngine.isCombatant(guard.profession())) {
                continue;
            }
            if (!guardEntity.getWorld().equals(threatenedLoc.getWorld())) {
                continue;
            }
            if (guardEntity.getLocation().distanceSquared(threatenedLoc) < GUARD_DISPATCH_RANGE_SQ) {
                reflex.defendKing(guard, enemy);
            }
        }
    }

    public void tickStrategic(BVillager king) {
        strategic.decide(king);
    }

    public void onDamaged(BVillager bv, Entity source) {
        reflex.reactToDamage(bv, source);
        if (source instanceof Player player && !isCreativeOrSpectator(player)) {
            markIllegalPlayer(player);
        }
    }

    private static boolean isCreativeOrSpectator(Player player) {
        try {
            return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
        } catch (Throwable t) {
            BV.plugin().getLogger().warning(
                    BV.messages().raw("log.tactical-tick-error")
                            .replace("{uuid}", player.getName())
                            .replace("{error}", "gamemode: " + t));
            return false;
        }
    }

    public ThreatDetector threatDetector() {
        return threatDetector;
    }

    public BlockInteractionEngine blocks() {
        return blocks;
    }

    public void clearVillagerState(String uuid) {
        reflex.clear(uuid);
        MovementHelper.clear(uuid);
    }

    public void shutdown() {
        shutdown = true;
        tactical.shutdown();
        strategic.shutdown();
    }

    private static void markIllegalPlayer(Player player) {
        ThreatDetector.markIllegal(player);
    }
}
