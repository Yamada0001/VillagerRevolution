package dev.bettervillagers.ai.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 决策结果缓存（规范 1.3：结果缓存降低 API 调用频次与成本 / 规范 4.4）。
 * 键为 villagerUuid + 场景 + prompt 摘要哈希，TTL 默认 300s。
 */
public final class DecisionCache {

    private final Cache<String, String> cache;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public DecisionCache(long ttlSeconds, int maxSize) {
        final long ttlMs = Math.max(1, ttlSeconds) * 1000L;
        final long jitterMs = ttlMs / 10;
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, String>() {
                    @Override
                    public long expireAfterCreate(String key, String value, long currentTime) {
                        long jitter = ThreadLocalRandom.current().nextLong(-jitterMs, jitterMs);
                        return Math.max(1, ttlMs + jitter) * 1_000_000L;
                    }

                    @Override
                    public long expireAfterUpdate(String key, String value, long currentTime, long currentDuration) {
                        long jitter = ThreadLocalRandom.current().nextLong(-jitterMs, jitterMs);
                        return Math.max(1, ttlMs + jitter) * 1_000_000L;
                    }

                    @Override
                    public long expireAfterRead(String key, String value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .maximumSize(Math.max(16, maxSize))
                .recordStats()
                .build();
    }

    public String get(String key) {
        String v = cache.getIfPresent(key);
        if (v != null) {
            hits.incrementAndGet();
        } else {
            misses.incrementAndGet();
        }
        return v;
    }

    /** 仅查询不更新命中/未命中计数器（用于 double-check 等内部场景）。 */
    public String peek(String key) {
        return cache.getIfPresent(key);
    }

    public void put(String key, String value) {
        if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
            cache.put(key, value);
        }
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public long size() {
        return cache.estimatedSize();
    }

    public CacheStats stats() {
        return cache.stats();
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
