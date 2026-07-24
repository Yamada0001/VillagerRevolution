package dev.bettervillagers.profession;

import java.util.Locale;

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
    CIVILIAN;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Profession parse(String s) {
        if (s == null) {
            return CIVILIAN;
        }
        try {
            return Profession.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CIVILIAN;
        }
    }
}
