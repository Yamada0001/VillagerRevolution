package dev.bettervillagers.village;

import dev.bettervillagers.BV;
<<<<<<< Updated upstream
import dev.bettervillagers.i18n.MessageService;
import dev.bettervillagers.profession.Profession;

import java.util.Locale;
=======
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.villager.BVillager;

>>>>>>> Stashed changes
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Periodic village-wide activities driven by the existing strategic tick. */
public final class VillageActivityManager {

    public enum Activity {
        TRADE_FAIR,
        DEFENSE_DRILL,
        HARVEST_FESTIVAL,
<<<<<<< Updated upstream
        BUILDING_CONTEST
=======
        BUILDING_CONTEST;

        String translationKey() {
            return "activity." + name().toLowerCase(java.util.Locale.ROOT);
        }
>>>>>>> Stashed changes
    }

    private final Map<Integer, ActivityState> states = new ConcurrentHashMap<>();
    private final Map<Integer, Long> notifiedStarts = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    private volatile long intervalMillis = 30 * 60_000L;
    private volatile long durationMillis = 3 * 60_000L;
    private volatile boolean shutdown;

    public VillageActivityManager() {
        configure();
    }

    public void configure() {
        if (BV.config() == null) {
            return;
        }
        enabled = BV.config().raw().getBoolean("activities.enabled", true);
        intervalMillis = Math.max(5 * 60_000L, BV.config().raw().getLong(
                "activities.interval-seconds", 1800L) * 1000L);
        durationMillis = Math.clamp(BV.config().raw().getLong(
                "activities.duration-seconds", 180L) * 1000L, 30_000L, intervalMillis);
    }

    public void tick(Village village) {
<<<<<<< Updated upstream
=======
        tick(village, null);
    }

    public void tick(Village village, BVillager host) {
>>>>>>> Stashed changes
        if (!enabled || shutdown || village == null) {
            return;
        }
        long now = System.currentTimeMillis();
        ActivityState updated = states.compute(village.id(), (id, previous) -> {
            if (previous != null && now < previous.endsAt()) {
                return previous;
            }
            long lastStarted = previous == null ? 0L : previous.startedAt();
            int lastSequence = previous == null ? -1 : previous.sequence();
            if (lastStarted > 0L && now - lastStarted < intervalMillis) {
                return new ActivityState(null, lastStarted, 0L, lastSequence);
            }
            int sequence = lastSequence + 1;
            Activity activity = nextActivity(id, sequence);
            return new ActivityState(activity, now, now + durationMillis, sequence);
        });
        Long priorNotification = updated.activity() == null ? null
                : notifiedStarts.put(village.id(), updated.startedAt());
        if (updated.activity() != null && !Long.valueOf(updated.startedAt()).equals(priorNotification)) {
<<<<<<< Updated upstream
            notifyStarted(village, updated.activity());
=======
            notifyStarted(village, updated.activity(), host);
>>>>>>> Stashed changes
        }
    }

    public Activity activeFor(int villageId, Profession profession) {
        if (!enabled || shutdown) {
            return null;
        }
        ActivityState state = states.get(villageId);
        if (state == null || state.activity() == null || System.currentTimeMillis() >= state.endsAt()) {
            return null;
        }
        return participates(state.activity(), profession) ? state.activity() : null;
    }

    public void clearVillage(int villageId) {
        states.remove(villageId);
        notifiedStarts.remove(villageId);
    }

    public void shutdown() {
        shutdown = true;
        states.clear();
        notifiedStarts.clear();
    }

    static Activity nextActivity(int villageId, int sequence) {
        Activity[] activities = Activity.values();
        return activities[Math.floorMod(villageId + sequence, activities.length)];
    }

    static boolean participates(Activity activity, Profession profession) {
        if (activity == null || profession == null) {
            return false;
        }
        return switch (activity) {
            case TRADE_FAIR -> profession == Profession.MERCHANT || profession == Profession.FARMER
                    || profession == Profession.CHEF || profession == Profession.BUTCHER;
            case DEFENSE_DRILL -> profession == Profession.KNIGHT || profession == Profession.SOLDIER
                    || profession == Profession.ARCHER;
            case HARVEST_FESTIVAL -> profession == Profession.FARMER || profession == Profession.CHEF;
            case BUILDING_CONTEST -> profession == Profession.BUILDER || profession == Profession.CIVILIAN;
        };
    }

<<<<<<< Updated upstream
    private static void notifyStarted(Village village, Activity activity) {
        String activityName = BV.messages().raw(
                "structure." + activity.name().toLowerCase(Locale.ROOT));
        String villageName = village.name() == null || village.name().isBlank()
                ? String.valueOf(village.id()) : village.name();
        BV.scheduler().runGlobal(() -> {
            var message = BV.messages().get("village-activity-started",
                    "{village}", MessageService.escapeUntrusted(villageName),
                    "{activity}", MessageService.escapeUntrusted(activityName));
=======
    private static void notifyStarted(Village village, Activity activity, BVillager host) {
        String activityName = BV.messages().raw(activity.translationKey());
        String villageName = village.name() == null || village.name().isBlank()
                ? String.valueOf(village.id()) : village.name();
        String hostName = host == null || host.name() == null || host.name().isBlank()
                ? BV.messages().raw("village-entry-unknown") : host.name();
        BV.scheduler().runGlobal(() -> {
            var message = BV.messages().get("village-activity-started",
                    "village", villageName,
                    "activity", activityName,
                    "host", hostName);
>>>>>>> Stashed changes
            double radius = village.radius() + BV.config().villageEntryRangeExtra();
            double radiusSquared = radius * radius;
            for (org.bukkit.entity.Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                BV.scheduler().runForEntity(player, () -> {
                    if (!player.getWorld().getName().equals(village.world())) {
                        return;
                    }
                    org.bukkit.Location location = player.getLocation();
                    double dx = location.getX() - village.centerX();
                    double dy = location.getY() - village.centerY();
                    double dz = location.getZ() - village.centerZ();
                    if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                        player.sendMessage(message);
                    }
                }, null);
            }
        });
    }

    private record ActivityState(Activity activity, long startedAt, long endsAt, int sequence) {
    }
}
