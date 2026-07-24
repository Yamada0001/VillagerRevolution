package dev.bettervillagers.scheduler;

import cn.handyplus.lib.adapter.HandySchedulerUtil;
import dev.bettervillagers.BV;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/**
 * 统一调度抽象层实现（规范 0.1 / 4.1）。
 * <p>
 * 运行期通过 {@link PlatformDetector} 探测平台，业务代码只依赖 {@link SchedulerAdapter}。
 * Paper 上世界交互退化为 {@link org.bukkit.scheduler.BukkitScheduler} 主线程；
 * Folia 上使用 {@code AsyncScheduler}/{@code GlobalRegionScheduler}/{@code RegionScheduler}/{@code EntityScheduler}。
 * <p>
 * Folia 专有类仅在 {@code isFolia()} 分支内被引用，配合 JVM 惰性类加载，
 * 保证在 Paper 运行时不会触发 {@code ClassNotFoundException}。
 */
public final class FoliaLibSchedulerAdapter implements SchedulerAdapter {

    private final Plugin plugin;
    private final boolean folia;

    public FoliaLibSchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
        this.folia = PlatformDetector.isFolia();
        // 规范 0.1：初始化 FoliaLib（提供额外工具方法，备用）
        HandySchedulerUtil.init(plugin);
    }

    @Override
    public void runAsync(Runnable task) {
        if (folia) {
            Bukkit.getAsyncScheduler().runNow(plugin, wrap(task));
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    @Override
    public void runAsyncDelayed(Runnable task, long delayTicks) {
        if (folia) {
            // AsyncScheduler 以 TimeUnit 计时，将 tick 换算为毫秒
            Bukkit.getAsyncScheduler().runDelayed(plugin, wrap(task), delayTicks * 50L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
        }
    }

    @Override
    public ScheduledHandle runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        if (folia) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask st =
                    Bukkit.getAsyncScheduler().runAtFixedRate(plugin, wrap(task), delayTicks * 50L, periodTicks * 50L, java.util.concurrent.TimeUnit.MILLISECONDS);
            return st::cancel;
        } else {
            org.bukkit.scheduler.BukkitTask bt =
                    Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
            return bt::cancel;
        }
    }

    @Override
    public void runGlobal(Runnable task) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, wrapRunnable(task));
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public ScheduledHandle runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        if (folia) {
            // GlobalRegionScheduler.runAtFixedRate 直接以 tick 计时（无 TimeUnit 重载）
            io.papermc.paper.threadedregions.scheduler.ScheduledTask st =
                    Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, wrap(task), delayTicks, periodTicks);
            return st::cancel;
        } else {
            org.bukkit.scheduler.BukkitTask bt =
                    Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
            return bt::cancel;
        }
    }

    @Override
    public void runAtRegion(Location location, Runnable task) {
        if (folia) {
            Bukkit.getRegionScheduler().execute(plugin, location, wrapRunnable(task));
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void runAtRegionDelayed(Location location, Runnable task, long delayTicks) {
        if (folia) {
            Bukkit.getRegionScheduler().runDelayed(plugin, location, wrap(task), delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    /** EntityScheduler.execute 默认延迟（tick）。 */
    private static final long ENTITY_SCHEDULER_DEFAULT_DELAY_TICKS = 1L;

    @Override
    public void runForEntity(Entity entity, Runnable task, Runnable retired) {
        if (folia) {
            // EntityScheduler.execute(Plugin, Runnable, Runnable retired, long delayTicks)
            entity.getScheduler().execute(plugin, wrapRunnable(task), retired, ENTITY_SCHEDULER_DEFAULT_DELAY_TICKS);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public boolean isFolia() {
        return folia;
    }

    @Override
    public boolean isTickThread() {
        if (folia) {
            return Bukkit.isGlobalTickThread();
        }
        return Bukkit.isPrimaryThread();
    }

    /** 包裹任务为 Consumer，捕获异常防止跨线程传播（异常隔离）。 */
    private Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> wrap(Runnable task) {
        return t -> {
            try {
                task.run();
            } catch (Throwable err) {
                plugin.getLogger().severe(BV.messages().raw("log.scheduler-task-error").replace("{error}", String.valueOf(err)));
            }
        };
    }

    /** 包裹任务为 Runnable，捕获异常防止跨线程传播（异常隔离）。 */
    private Runnable wrapRunnable(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable err) {
                plugin.getLogger().severe(BV.messages().raw("log.scheduler-task-error").replace("{error}", String.valueOf(err)));
            }
        };
    }
}
