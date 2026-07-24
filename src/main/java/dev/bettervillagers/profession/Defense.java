package dev.bettervillagers.profession;

/** 防御等级（规范 2.1）。 */
public enum Defense {
    LOW(0.0),
    MEDIUM(0.3),
    HIGH(0.6);

    private final double damageReduction;

    Defense(double damageReduction) {
        this.damageReduction = damageReduction;
    }

    /** 伤害减免比例（0~1）。 */
    public double damageReduction() {
        return damageReduction;
    }

    public static Defense parse(String s) {
        if (s == null) {
            return LOW;
        }
        return switch (s.toUpperCase()) {
            case "HIGH" -> HIGH;
            case "MEDIUM", "MED" -> MEDIUM;
            default -> LOW;
        };
    }
}
