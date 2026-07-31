package dev.bettervillagers.behavior.threat;

import dev.bettervillagers.BV;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ThreatDetector {

    private static final Map<UUID, Long> ILLEGAL_PLAYERS = new ConcurrentHashMap<>();

    private final double detectionRange;

    public ThreatDetector(double detectionRange) {
        this.detectionRange = detectionRange;
    }

    public List<Threat> scan(LivingEntity villager, int myVillageId) {
        List<Threat> threats = new ArrayList<>();
        Location loc = villager.getLocation();
        for (Entity nearby : villager.getNearbyEntities(detectionRange, detectionRange, detectionRange)) {
            double dist = nearby.getLocation().distanceSquared(loc);
            switch (nearby) {
                case Monster monster when !monster.isDead() ->
                        threats.add(new Threat(ThreatType.HOSTILE_MOB, monster, dist));
                case Player player when isIllegal(player)
                        && !isCreativeOrSpectator(player) ->
                        threats.add(new Threat(ThreatType.ILLEGAL_PLAYER, player, dist));
                case Villager other -> {
                    Threat enemyVillager = checkEnemyVillager(other, myVillageId, dist);
                    if (enemyVillager != null) {
                        threats.add(enemyVillager);
                    }
                }
                default -> {
                }
            }
        }
        threats.sort(Comparator.comparingDouble(Threat::distance));
        return threats;
    }

    private Threat checkEnemyVillager(Villager villager, int myVillageId, double dist) {
        if (myVillageId < 0 || BV.villagers() == null || BV.diplomacy() == null) {
            return null;
        }
        var bv = BV.villagers().get(villager.getUniqueId().toString());
        if (bv.isEmpty() || bv.get().villageId() == myVillageId) {
            return null;
        }
        return BV.diplomacy().areEnemies(myVillageId, bv.get().villageId())
                ? new Threat(ThreatType.HOSTILE_MOB, villager, dist)
                : null;
    }

    private static boolean isCreativeOrSpectator(Player player) {
        try {
            return player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void markIllegal(Player player) {
        ILLEGAL_PLAYERS.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private static boolean isIllegal(Player player) {
        return ILLEGAL_PLAYERS.containsKey(player.getUniqueId());
    }

    public LivingEntity nearestEnemy(LivingEntity villager, int myVillageId) {
        for (Threat threat : scan(villager, myVillageId)) {
            if (threat.source() instanceof LivingEntity enemy && !enemy.isDead()) {
                return enemy;
            }
        }
        return null;
    }

    public boolean environmentDanger(LivingEntity villager) {
        Material mat = villager.getLocation().getBlock().getType();
        return mat == Material.LAVA
                || mat == Material.FIRE
                || mat == Material.SOUL_FIRE
                || mat == Material.CAMPFIRE
                || mat == Material.SOUL_CAMPFIRE
                || cliffDanger(villager);
    }

    private boolean cliffDanger(LivingEntity villager) {
        Location loc = villager.getLocation();
        World world = loc.getWorld();
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
