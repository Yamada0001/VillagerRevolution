package dev.bettervillagers.scheduler;

/**
 * Runtime platform detection.
 */
public final class PlatformDetector {

    private static final boolean FOLIA = probe();

    private PlatformDetector() {
    }

    private static boolean probe() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    public static boolean isFolia() {
        return FOLIA;
    }
}
