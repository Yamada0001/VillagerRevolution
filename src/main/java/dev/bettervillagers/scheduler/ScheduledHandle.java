package dev.bettervillagers.scheduler;

/** 可取消的调度句柄。 */
@FunctionalInterface
public interface ScheduledHandle {

    /** 取消该调度任务。 */
    void cancel();
}
