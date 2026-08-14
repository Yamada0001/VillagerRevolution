package dev.bettervillagers.profession;

import java.util.Locale;
import java.util.Optional;

/** 村民职业（规范 2.1，共 11 种）。 */
public enum Profession {
    KING,
    KNIGHT,
    SOLDIER,
    ARCHER,
    BUTCHER,
    CHEF,
    FARMER,
    MINER,
    BUILDER,
    MERCHANT,
    DOCTOR,
    FISHERMAN,
    ENCHANTER,
    BLACKSMITH,
    CIVILIAN;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Profession parse(String s) {
        return find(s).orElse(CIVILIAN);
    }

    /** Exact lookup for user input; unlike {@link #parse(String)}, invalid values are not silently coerced. */
    public static Optional<Profession> find(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Profession.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
