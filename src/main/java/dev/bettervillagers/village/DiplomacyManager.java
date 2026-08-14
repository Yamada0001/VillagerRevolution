package dev.bettervillagers.village;

import dev.bettervillagers.BV;
import dev.bettervillagers.ai.AIContext;
import dev.bettervillagers.i18n.MessageService;
import dev.bettervillagers.storage.VillageDiplomacyRecord;
import dev.bettervillagers.villager.BVillager;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Durable village diplomacy, including periodic king decisions. */
public final class DiplomacyManager {

    public enum Relation { ALLY, NEUTRAL, ENEMY }

    private static final long DEFAULT_REVIEW_INTERVAL_MS = 10 * 60_000L;

    private final Map<String, Relation> persistedRelations = new ConcurrentHashMap<>();
    private final Map<String, Long> lastReview = new ConcurrentHashMap<>();
    private final Set<String> reviewing = ConcurrentHashMap.newKeySet();
    private volatile Map<String, Relation> configuredRelations = Map.of();
    private volatile long reviewIntervalMillis = DEFAULT_REVIEW_INTERVAL_MS;
    private volatile boolean enabled = true;
    private volatile boolean shutdown;

    public DiplomacyManager() {
        configure();
    }

    /** Reloads optional defaults and review settings without discarding database state. */
    public void configure() {
        if (BV.config() == null) {
            return;
        }
        ConfigurationSection section = BV.config().raw().getConfigurationSection("diplomacy");
        enabled = section == null || section.getBoolean("enabled", true);
        long seconds = section == null ? DEFAULT_REVIEW_INTERVAL_MS / 1000L
                : section.getLong("review-interval-seconds", DEFAULT_REVIEW_INTERVAL_MS / 1000L);
        reviewIntervalMillis = Math.max(60_000L, seconds * 1000L);

        Map<String, Relation> parsed = new HashMap<>();
        ConfigurationSection relations = section == null ? null : section.getConfigurationSection("relations");
        if (relations != null) {
            for (String relationKey : relations.getKeys(false)) {
                String canonical = canonicalConfiguredKey(relationKey);
                Relation relation = parseRelation(relations.getString(relationKey));
                if (canonical != null && relation != Relation.NEUTRAL) {
                    parsed.put(canonical, relation);
                }
            }
        }
        configuredRelations = Map.copyOf(parsed);
    }

    /** Loads database state before events and AI ticking are enabled. */
    public CompletableFuture<Void> load() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        BV.scheduler().runAsync(() -> {
            try {
                Map<String, Relation> loaded = new HashMap<>();
                for (VillageDiplomacyRecord record : BV.storage().diplomacy().findAll()) {
                    if (record.villageA() < 0 || record.villageB() < 0
                            || record.villageA() == record.villageB()) {
                        continue;
                    }
                    loaded.put(key(record.villageA(), record.villageB()), parseRelation(record.relation()));
                }
                persistedRelations.clear();
                persistedRelations.putAll(loaded);
                future.complete(null);
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    public Relation getRelation(int villageA, int villageB) {
        if (villageA == villageB) {
            return Relation.ALLY;
        }
        String pair = key(villageA, villageB);
        return persistedRelations.getOrDefault(pair,
                configuredRelations.getOrDefault(pair, Relation.NEUTRAL));
    }

    public boolean areEnemies(int villageA, int villageB) {
        return getRelation(villageA, villageB) == Relation.ENEMY;
    }

    /** Starts at most one review for a pair in each configured interval. */
    public void review(BVillager king, Village home) {
        if (!enabled || shutdown || king == null || home == null || BV.ai() == null
                || BV.villages() == null) {
            return;
        }
        Village other = nearestVillage(home);
        if (other == null) {
            return;
        }
        String pair = key(home.id(), other.id());
        long now = System.currentTimeMillis();
        Long previous = lastReview.get(pair);
        if ((previous != null && now - previous < reviewIntervalMillis) || !reviewing.add(pair)) {
            return;
        }
        lastReview.put(pair, now);

        Relation current = getRelation(home.id(), other.id());
        String system = BV.messages().raw("ai-prompt.diplomacy-system");
        String user = BV.messages().raw("ai-prompt.diplomacy-user")
                .replace("{first}", displayName(home))
                .replace("{first-pop}", String.valueOf(home.population()))
                .replace("{second}", displayName(other))
                .replace("{second-pop}", String.valueOf(other.population()))
                .replace("{relation}", current.name());
        AIContext context = new AIContext(
                king.uuid(), king.name(), "king", "diplomacy", system, user);
        BV.ai().decide(context).thenCompose(result -> {
            Relation decision = result != null && result.isUsable()
                    ? parseDecision(result.text()) : null;
            if (decision == null || decision == current || shutdown) {
                return CompletableFuture.completedFuture(false);
            }
            return setRelation(home.id(), other.id(), decision);
        }).thenAccept(changed -> {
            if (changed && !shutdown) {
                notifyChange(home, other, getRelation(home.id(), other.id()));
            }
        }).exceptionally(error -> {
            if (!shutdown && BV.plugin() != null) {
                BV.plugin().getLogger().warning(BV.messages().raw("log.diplomacy-update-error")
                        .replace("{first}", String.valueOf(home.id()))
                        .replace("{second}", String.valueOf(other.id()))
                        .replace("{error}", String.valueOf(error.getMessage())));
            }
            return null;
        }).whenComplete((ignored, error) -> reviewing.remove(pair));
    }

    /** Persists first and publishes to the runtime cache only after the commit succeeds. */
    public CompletableFuture<Boolean> setRelation(int villageA, int villageB, Relation relation) {
        if (relation == null || villageA == villageB || shutdown) {
            return CompletableFuture.completedFuture(false);
        }
        String pair = key(villageA, villageB);
        if (getRelation(villageA, villageB) == relation) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        BV.scheduler().runAsync(() -> {
            try {
                BV.storage().diplomacy().upsert(villageA, villageB, relation.name(),
                        System.currentTimeMillis());
                if (!shutdown) {
                    persistedRelations.put(pair, relation);
                    future.complete(true);
                } else {
                    future.complete(false);
                }
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    public void clearVillage(int villageId) {
        String marker = String.valueOf(villageId);
        persistedRelations.keySet().removeIf(pair -> pair.startsWith(marker + "-")
                || pair.endsWith("-" + marker));
        lastReview.keySet().removeIf(pair -> pair.startsWith(marker + "-")
                || pair.endsWith("-" + marker));
        reviewing.removeIf(pair -> pair.startsWith(marker + "-") || pair.endsWith("-" + marker));
    }

    public void shutdown() {
        shutdown = true;
        reviewing.clear();
        lastReview.clear();
        persistedRelations.clear();
    }

    static Relation parseDecision(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ALLY" -> Relation.ALLY;
            case "NEUTRAL" -> Relation.NEUTRAL;
            case "ENEMY" -> Relation.ENEMY;
            default -> null;
        };
    }

    private static Relation parseRelation(String value) {
        Relation parsed = parseDecision(value);
        return parsed == null ? Relation.NEUTRAL : parsed;
    }

    private static String canonicalConfiguredKey(String value) {
        if (value == null || !value.matches("\\d+-\\d+")) {
            return null;
        }
        String[] ids = value.split("-", 2);
        try {
            int first = Integer.parseInt(ids[0]);
            int second = Integer.parseInt(ids[1]);
            return first == second ? null : key(first, second);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String key(int villageA, int villageB) {
        return Math.min(villageA, villageB) + "-" + Math.max(villageA, villageB);
    }

    private static String displayName(Village village) {
        return village.name() == null || village.name().isBlank()
                ? String.valueOf(village.id()) : village.name();
    }

    private static Village nearestVillage(Village home) {
        Village best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Village candidate : BV.villages().all()) {
            if (candidate.id() == home.id() || !candidate.world().equalsIgnoreCase(home.world())) {
                continue;
            }
            long dx = (long) candidate.centerX() - home.centerX();
            long dz = (long) candidate.centerZ() - home.centerZ();
            long distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static void notifyChange(Village first, Village second, Relation relation) {
        String relationName = BV.messages().raw("diplomacy." + relation.name().toLowerCase(Locale.ROOT));
        BV.scheduler().runGlobal(() -> BV.messages().broadcastPlayers("diplomacy-changed",
                "{first}", MessageService.escapeUntrusted(displayName(first)),
                "{second}", MessageService.escapeUntrusted(displayName(second)),
                "{rel}", MessageService.escapeUntrusted(relationName)));
    }
}
