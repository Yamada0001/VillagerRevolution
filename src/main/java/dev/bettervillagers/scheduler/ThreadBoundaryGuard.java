package dev.bettervillagers.scheduler;

import dev.bettervillagers.BV;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * Runtime guard for world access that must happen on the owning region thread.
 */
public final class ThreadBoundaryGuard {

    private final Plugin plugin;
    private final boolean debug;

    public ThreadBoundaryGuard(Plugin plugin, boolean debug) {
        this.plugin = plugin;
        this.debug = debug;
    }

    public void assertRegionThread(Location location) {
        boolean ok = PlatformDetector.isFolia()
                ? FoliaThreadChecks.isOwnedByCurrentRegion(location)
                : Bukkit.isPrimaryThread();
        if (!ok) {
            fail(BV.messages().raw("guard.illegal-region").replace("{location}", String.valueOf(location)));
        }
    }

    private void fail(String message) {
        if (debug) {
            throw new IllegalStateException("[BetterVillagers] " + message);
        }
        plugin.getLogger().warning(message);
    }

    static final class FoliaThreadChecks {
        static boolean isOwnedByCurrentRegion(Location loc) {
            return Bukkit.isOwnedByCurrentRegion(loc);
        }

        private FoliaThreadChecks() {
        }
    }
}
