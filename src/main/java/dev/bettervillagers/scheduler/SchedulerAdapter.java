package dev.bettervillagers.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Scheduling boundary that hides Paper and Folia execution differences.
 */
public interface SchedulerAdapter {

    void runAsync(Runnable task);

    void runAsyncDelayed(Runnable task, long delayTicks);

    ScheduledHandle runAsyncTimer(Runnable task, long delayTicks, long periodTicks);

    void runGlobal(Runnable task);

    ScheduledHandle runGlobalTimer(Runnable task, long delayTicks, long periodTicks);

    void runAtRegion(Location location, Runnable task);

    void runAtRegionDelayed(Location location, Runnable task, long delayTicks);

    void runForEntity(Entity entity, Runnable task, Runnable retired);
}
