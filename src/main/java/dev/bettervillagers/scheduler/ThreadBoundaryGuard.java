package dev.bettervillagers.scheduler;

import dev.bettervillagers.BV;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * 线程边界守护（规范 4.5：Folia 一致性校验）。
 * <p>
 * 关键写操作前校验当前线程是否为预期区域线程。
 * {@code debug-mode} 下抛出明确异常，防止开发期引入隐式线程违规；
 * 生产环境仅记录警告，避免影响运行。
 */
public final class ThreadBoundaryGuard {

    private final Plugin plugin;
    private final boolean debug;

    public ThreadBoundaryGuard(Plugin plugin, boolean debug) {
        this.plugin = plugin;
        this.debug = debug;
    }

    /** 断言当前线程拥有指定坐标的区域（方块写操作前调用）。 */
    public void assertRegionThread(Location location) {
        boolean ok = PlatformDetector.isFolia()
                ? FoliaThreadChecks.isOwnedByCurrentRegion(location)
                : Bukkit.isPrimaryThread();
        if (!ok) {
            fail(BV.messages().raw("guard.illegal-region").replace("{location}", String.valueOf(location)));
        }
    }

    /** 断言当前为全局 tick 线程（实体/全局世界状态写操作前调用）。 */
    public void assertTickThread() {
        if (!PlatformDetector.isTickThread()) {
            fail(BV.messages().raw("guard.illegal-tick"));
        }
    }

    /** 断言当前为异步线程（禁止在此触碰游戏世界对象）。 */
    public void assertAsyncThread() {
        boolean onTick = PlatformDetector.isFolia() ? FoliaThreadChecks.isGlobalTickThread() : Bukkit.isPrimaryThread();
        if (onTick) {
            fail(BV.messages().raw("guard.illegal-async"));
        }
    }

    private void fail(String message) {
        if (debug) {
            throw new IllegalStateException("[BetterVillagers] " + message);
        }
        plugin.getLogger().warning(message);
    }

    /** Folia 专有线程判断隔离类（利用 JVM 惰性类加载，避免 Paper 上 NoSuchMethodError）。 */
    static final class FoliaThreadChecks {
        static boolean isOwnedByCurrentRegion(Location loc) {
            return Bukkit.isOwnedByCurrentRegion(loc);
        }

        static boolean isGlobalTickThread() {
            return Bukkit.isGlobalTickThread();
        }

        private FoliaThreadChecks() {
        }
    }
}
