package dev.bettervillagers.villager;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

/** Executes tasks concurrently across keys while preserving submission order for each key. */
final class KeyedSerialExecutor {

    private volatile ExecutorService pool;
    private volatile int threads;
    private final CopyOnWriteArrayList<ExecutorService> retiredPools = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    KeyedSerialExecutor(int threads) {
        this.threads = Math.max(1, threads);
        pool = newPool(this.threads);
    }

    private static ExecutorService newPool(int threads) {
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "BV-Villager-Storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    void execute(String key, Runnable task) {
        CompletableFuture<Void> next = tails.compute(key, (ignored, previous) -> {
            CompletableFuture<Void> base = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((value, error) -> null);
            return base.thenRunAsync(task, pool);
        });
        next.whenComplete((value, error) -> tails.remove(key, next));
    }

    synchronized void reconfigure(int requestedThreads) {
        int nextThreads = Math.max(1, requestedThreads);
        if (nextThreads == threads) {
            return;
        }
        ExecutorService previous = pool;
        pool = newPool(nextThreads);
        threads = nextThreads;
        retiredPools.add(previous);
        CompletableFuture<Void> pending = CompletableFuture.allOf(
                tails.values().toArray(CompletableFuture[]::new));
        pending.whenComplete((ignored, failure) -> previous.shutdown());
    }

    void shutdownAndAwait(long timeout, TimeUnit unit) {
        try {
            CompletableFuture.allOf(tails.values().toArray(CompletableFuture[]::new))
                    .get(timeout, unit);
        } catch (Exception ignored) {
            // The executor shutdown below bounds any remaining work.
        }
        java.util.List<ExecutorService> pools = new java.util.ArrayList<>(retiredPools);
        pools.add(pool);
        pools.forEach(ExecutorService::shutdown);
        try {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            for (ExecutorService executor : pools) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    executor.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            pools.forEach(ExecutorService::shutdownNow);
            Thread.currentThread().interrupt();
        }
    }
}
