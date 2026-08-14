package dev.bettervillagers.behavior.threat;

import dev.bettervillagers.BV;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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

    private final Map<UUID, Long> illegalPlayers = new ConcurrentHashMap<>();
    private static final long ILLEGAL_PLAYER_TTL_MS = 5 * 60_000L;
    private static final long SCAN_CACHE_TTL_MS = 250L;

    private final double detectionRange;
    private final Map<UUID, CachedScan> scanCache = new ConcurrentHashMap<>();

    public ThreatDetector(double detectionRange) {
        this.detectionRange = detectionRange;
    }

    public List<Threat> scan(LivingEntity villager, int myVillageId) {
        long now = System.currentTimeMillis();
        UUID uuid = villager.getUniqueId();
        CachedScan cached = scanCache.get(uuid);
        if (cached != null && cached.villageId() == myVillageId
                && now - cached.createdAt() < SCAN_CACHE_TTL_MS) {
            return cached.threats();
        }
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
        List<Threat> result = List.copyOf(threats);
        scanCache.put(uuid, new CachedScan(now, myVillageId, result));
        return result;
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

    public void markIllegal(Player player) {
        illegalPlayers.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private boolean isIllegal(Player player) {
        UUID uuid = player.getUniqueId();
        Long markedAt = illegalPlayers.get(uuid);
        if (markedAt == null) {
            return false;
        }
        if (System.currentTimeMillis() - markedAt >= ILLEGAL_PLAYER_TTL_MS) {
            illegalPlayers.remove(uuid, markedAt);
            return false;
        }
        return true;
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
                || mat == Material.SOUL_CAMPFIRE;
    }

    public void clear() {
        illegalPlayers.clear();
        scanCache.clear();
    }

    public void clear(String uuid) {
        try {
            scanCache.remove(UUID.fromString(uuid));
        } catch (IllegalArgumentException ignored) {
            // 非标准 UUID 不会有实体扫描缓存。
        }
    }

    private record CachedScan(long createdAt, int villageId, List<Threat> threats) {
    }
}
