package dev.bettervillagers.scheduler;

import org.bukkit.Bukkit;

/** 运行期平台探测（规范 4.1：通过 RegionizedServer 类存在性判定 Folia）。 */
public final class PlatformDetector {

    private static final boolean FOLIA = probe();

    private PlatformDetector() {
    }

    private static boolean probe() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** 当前服务端是否为 Folia。 */
    public static boolean isFolia() {
        return FOLIA;
    }

    /** 当前线程是否为 tick 线程（Paper 主线程 / Folia 区域线程）。 */
    public static boolean isTickThread() {
        return FOLIA ? Bukkit.isGlobalTickThread() : Bukkit.isPrimaryThread();
    }
}
