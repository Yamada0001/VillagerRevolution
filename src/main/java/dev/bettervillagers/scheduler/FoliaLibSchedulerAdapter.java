package dev.bettervillagers.scheduler;

import cn.handyplus.lib.adapter.HandySchedulerUtil;
import dev.bettervillagers.BV;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Scheduler adapter backed by Paper/Folia scheduler APIs.
 */
public final class FoliaLibSchedulerAdapter implements SchedulerAdapter {

    private static final long TICK_MILLIS = 50L;
    private static final long ENTITY_SCHEDULER_DEFAULT_DELAY_TICKS = 1L;
    private static final ScheduledHandle NO_OP_HANDLE = () -> { };

    private final Plugin plugin;

    public FoliaLibSchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
        HandySchedulerUtil.init(plugin);
    }

    @Override
    public void runAsync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, wrap(task));
    }

    @Override
    public void runAsyncDelayed(Runnable task, long delayTicks) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getAsyncScheduler().runDelayed(plugin, wrap(task), toMillis(delayTicks), TimeUnit.MILLISECONDS);
    }

    @Override
    public ScheduledHandle runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        if (!plugin.isEnabled()) {
            return NO_OP_HANDLE;
        }
        io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTask =
                Bukkit.getAsyncScheduler().runAtFixedRate(plugin, wrap(task), toMillis(delayTicks),
                        toMillis(periodTicks), TimeUnit.MILLISECONDS);
        return scheduledTask::cancel;
    }

    @Override
    public void runGlobal(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, wrapRunnable(task));
    }

    @Override
    public ScheduledHandle runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        if (!plugin.isEnabled()) {
            return NO_OP_HANDLE;
        }
        io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTask =
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, wrap(task), delayTicks, periodTicks);
        return scheduledTask::cancel;
    }

    @Override
    public void runAtRegion(Location location, Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getRegionScheduler().execute(plugin, location, wrapRunnable(task));
    }

    @Override
    public void runAtRegionDelayed(Location location, Runnable task, long delayTicks) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getRegionScheduler().runDelayed(plugin, location, wrap(task), delayTicks);
    }

    @Override
    public void runForEntity(Entity entity, Runnable task, Runnable retired) {
        if (!plugin.isEnabled()) {
            return;
        }
        entity.getScheduler().execute(plugin, wrapRunnable(task), retired, ENTITY_SCHEDULER_DEFAULT_DELAY_TICKS);
    }

    private static long toMillis(long ticks) {
        return ticks * TICK_MILLIS;
    }

    private Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> wrap(Runnable task) {
        return ignored -> runSafely(task);
    }

    private Runnable wrapRunnable(Runnable task) {
        return () -> runSafely(task);
    }

    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable err) {
            plugin.getLogger().severe(BV.messages().raw("log.scheduler-task-error")
                    .replace("{error}", String.valueOf(err)));
        }
    }
}
