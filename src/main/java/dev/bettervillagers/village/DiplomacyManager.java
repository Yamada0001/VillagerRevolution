package dev.bettervillagers.village;

import dev.bettervillagers.BV;

/**
 * Village diplomacy state. Explicit relations can be declared in config under
 * diplomacy.relations.<minId>-<maxId>.
 */
public final class DiplomacyManager {

    public enum Relation { ALLY, NEUTRAL, ENEMY }

    public Relation getRelation(int villageA, int villageB) {
        if (villageA == villageB) {
            return Relation.ALLY;
        }
        String value = BV.config() == null ? null
                : BV.config().raw().getString("diplomacy.relations." + key(villageA, villageB));
        return parseRelation(value);
    }

    public boolean areEnemies(int villageA, int villageB) {
        return getRelation(villageA, villageB) == Relation.ENEMY;
    }

    private static Relation parseRelation(String value) {
        if (value == null || value.isBlank()) {
            return Relation.NEUTRAL;
        }
        try {
            return Relation.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Relation.NEUTRAL;
        }
    }

    private static String key(int villageA, int villageB) {
        return Math.min(villageA, villageB) + "-" + Math.max(villageA, villageB);
    }
}
