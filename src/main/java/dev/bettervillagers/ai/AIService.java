package dev.bettervillagers.ai;

import dev.bettervillagers.BV;
import dev.bettervillagers.ai.cache.DecisionCache;
import dev.bettervillagers.ai.circuit.CircuitBreaker;
import dev.bettervillagers.ai.memory.AIMemory;
import dev.bettervillagers.ai.memory.MemoryStore;
import dev.bettervillagers.ai.providers.ClaudeProvider;
import dev.bettervillagers.ai.providers.OpenAICompatibleProvider;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 服务编排核心（规范 1.3 异步架构 / 4.5 三级降级链与熔断）。
 * <p>
 * 职责：缓存判定 → 熔断判定 → 受控线程池调度 → 指数退避重试 → 失败转备用 provider
 * → 全部失败返回降级结果。同一村民请求串行化，避免并发触发限流。
 * <p>
 * 修复：api-key / api-endpoint / model 统一从 {@code providers.<name>} 读取，
 * 不再有顶层重复字段，彻底消除"填了别的 provider 仍走 OpenAI"的问题。
 */
public final class AIService {

    private ConfigurationSection aiCfg;
    private volatile double temperature;
    private volatile int maxTokens;
    private volatile long timeout;
    private volatile int retryAttempts;

    private volatile AIProvider primary;
    private volatile List<AIProvider> fallbacks;
    private final CircuitBreaker breaker;
    private final DecisionCache cache;
    private final MemoryStore memory;
    private final ExecutorService executor;

    /** 同一村民串行化锁。 */
    private final Map<String, LockRef> villagerLocks = new ConcurrentHashMap<>();
    private final RateLimiter rateLimiter;

    private static final class LockRef {
        private final Object lock = new Object();
        private final AtomicInteger users = new AtomicInteger();
    }

    private static final class RateLimiter {
        private double rate;
        private double tokens;
        private long lastNanos;

        private RateLimiter(double rate) {
            configure(rate);
        }

        synchronized void configure(double newRate) {
            rate = Math.max(0.0, newRate);
            tokens = rate;
            lastNanos = System.nanoTime();
            notifyAll();
        }

        synchronized void acquire() throws AIException {
            if (rate <= 0.0) {
                return;
            }
            for (;;) {
                long now = System.nanoTime();
                tokens = Math.min(rate, tokens + (now - lastNanos) / 1_000_000_000.0 * rate);
                lastNanos = now;
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }
                long waitMs = Math.min(1000L, Math.max(1L,
                        (long) Math.ceil((1.0 - tokens) / rate * 1000.0)));
                try {
                    wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AIException(BV.messages().raw("errors.ai-rate-limiter-interrupted"), e, false);
                }
            }
        }
    }

    public AIService(ConfigurationSection aiCfg, ConfigurationSection cbCfg) {
        this.aiCfg = aiCfg;
        this.temperature = aiCfg.getDouble("temperature", 0.7);
        this.maxTokens = aiCfg.getInt("max-tokens", 500);
        this.timeout = aiCfg.getLong("timeout", 90);
        this.retryAttempts = aiCfg.getInt("retry-attempts", 3);
        this.rateLimiter = new RateLimiter(aiCfg.getDouble("requests-per-second", 5.0));

        int concurrency = aiCfg.getInt("max-concurrent-requests", 8);
        this.executor = Executors.newFixedThreadPool(Math.max(1, concurrency),
                r -> {
                    Thread t = new Thread(r, "BV-AI-Worker");
                    t.setDaemon(true);
                    return t;
                });

        String providerName = aiCfg.getString("provider", "openai");
        this.primary = buildProvider(providerName);
        // 启动日志：输出实际选择的 provider，便于调试
        BV.plugin().getLogger().info(
                BV.messages().raw("log.ai-provider-init").replace("{provider}", providerName));
        List<AIProvider> fb = new ArrayList<>();
        for (String name : aiCfg.getStringList("fallback-providers")) {
            if (!name.isBlank()) {
                fb.add(buildProvider(name.trim()));
            }
        }
        this.fallbacks = List.copyOf(fb);

        this.cache = new DecisionCache(
                aiCfg.getInt("cache.decision-ttl", 300),
                aiCfg.getInt("cache.max-size", 2000));
        this.memory = new MemoryStore(aiCfg.getInt("memory.max-history", 50));

        boolean cbEnabled = cbCfg.getBoolean("enabled", true);
        this.breaker = cbEnabled
                ? new CircuitBreaker(
                cbCfg.getDouble("failure-threshold-percent", 50),
                cbCfg.getInt("min-requests", 10),
                TimeUnit.SECONDS.toMillis(cbCfg.getLong("open-duration", 60)),
                TimeUnit.SECONDS.toMillis(cbCfg.getLong("window-seconds", 60)))
                : null;
    }

    /**
     * 构建指定 provider，凭证从 {@code providers.<name>} 节读取（不再从顶层 fallback）。
     * <p>
     * 彻底修复"填了 deepseek 仍走 openai"的问题：
     * 所有 api-key / api-endpoint / model 均从对应 provider 子节获取。
     */
    private AIProvider buildProvider(String name) {
        var providersSection = aiCfg.getConfigurationSection("providers");
        String key = "";
        String endpoint = "";
        String model = "";
        if (providersSection != null) {
            var pc = providersSection.getConfigurationSection(name);
            if (pc != null) {
                key = pc.getString("api-key", "");
                endpoint = pc.getString("api-endpoint", "");
                model = pc.getString("model", "");
            }
        }
        if ("claude".equalsIgnoreCase(name)) {
            return new ClaudeProvider(endpoint, key, model);
        }
        return new OpenAICompatibleProvider(name.toLowerCase(), endpoint, key, model);
    }

    /**
     * 热重载：从新配置重建 provider 链与参数（规范 0.2）。
     * <p>
     * 修复问题8：reload 后立即应用新的 provider / api-key / endpoint，
     * 无需重启服务器。保留缓存、记忆与熔断器（仅重建 provider 实例）。
     */
    public void reconfigure(ConfigurationSection newAiCfg, ConfigurationSection newCbCfg) {
        this.aiCfg = newAiCfg;
        this.temperature = newAiCfg.getDouble("temperature", 0.7);
        this.maxTokens = newAiCfg.getInt("max-tokens", 500);
        this.timeout = newAiCfg.getLong("timeout", 90);
        this.retryAttempts = newAiCfg.getInt("retry-attempts", 3);
        this.rateLimiter.configure(newAiCfg.getDouble("requests-per-second", 5.0));

        // 修复问题1：重置熔断器状态，清除旧 provider 的失败历史
        if (breaker != null) {
            breaker.reset();
        }
        // 清空决策缓存，避免旧缓存影响新 provider 的行为
        if (cache != null) {
            cache.invalidateAll();
        }

        String providerName = newAiCfg.getString("provider", "openai");
        this.primary = buildProvider(providerName);
        BV.plugin().getLogger().info(
                BV.messages().raw("log.ai-provider-init").replace("{provider}", providerName));
        List<AIProvider> fb = new ArrayList<>();
        for (String name : newAiCfg.getStringList("fallback-providers")) {
            if (!name.isBlank()) {
                fb.add(buildProvider(name.trim()));
            }
        }
        this.fallbacks = List.copyOf(fb);
    }

    /**
     * 异步决策（规范 1.3）。返回 future；调用方在回调中通过调度器切回区域线程执行副作用。
     */
    public CompletableFuture<AIResult> decide(AIContext ctx) {
        // 1. 熔断判定；缓存命中也必须完成 HALF_OPEN 探针生命周期
        if (breaker != null && !breaker.allowRequest()) {
            return CompletableFuture.completedFuture(AIResult.degraded(""));
        }
        String cacheKey = ctx.villagerUuid() + "|" + ctx.profession() + "|" + ctx.scenario() + "|" + sha256Short(ctx.userPrompt());
        String cached = cache.get(cacheKey);
        if (cached != null) {
            if (breaker != null) {
                breaker.completeProbeSuccess();
            }
            return CompletableFuture.completedFuture(AIResult.ok(cached));
        }

        return CompletableFuture.supplyAsync(() -> {
            LockRef ref = villagerLocks.compute(ctx.villagerUuid(), (key, existing) -> {
                LockRef value = existing == null ? new LockRef() : existing;
                value.users.incrementAndGet();
                return value;
            });
            try {
                synchronized (ref.lock) {
                    return runWithFallback(ctx, cacheKey);
                }
            } finally {
                if (ref.users.decrementAndGet() == 0) {
                    villagerLocks.remove(ctx.villagerUuid(), ref);
                }
            }
        }, executor).exceptionally(ex -> {
            BV.plugin().getLogger().warning(BV.messages().raw("log.ai-decide-exception").replace("{error}", String.valueOf(ex)));
            if (breaker != null) {
                breaker.recordFailure();
            }
            return AIResult.failed();
        });
    }

    private AIResult runWithFallback(AIContext ctx, String cacheKey) {
        // double-check：串行化后再次确认缓存，避免缓存击穿
        String dc = cache.peek(cacheKey);
        if (dc != null) {
            if (breaker != null) {
                breaker.completeProbeSuccess();
            }
            return AIResult.ok(dc);
        }

        List<AIProvider> chain = new ArrayList<>();
        chain.add(primary);
        chain.addAll(fallbacks);

        // 组装请求（system + memory + user）
        AIMemory mem = memory.get(ctx.villagerUuid());
        mem.prune(aiCfg.getInt("memory.context-keep-recent", 12));
        List<AIRequest.Message> msgs = new ArrayList<>();
        msgs.add(AIRequest.Message.system(ctx.systemPrompt()));
        msgs.addAll(mem.messages());
        msgs.add(AIRequest.Message.user(ctx.userPrompt()));
        // model 留空，由 provider 使用自身配置的 defaultModel
        AIRequest request = new AIRequest(msgs, "", temperature, maxTokens, timeout);

        AIException lastError = null;
        for (AIProvider p : chain) {
            try {
                String text = retry(p, request);
                // 成功：记录、缓存、入记忆
                if (breaker != null) {
                    breaker.recordSuccess();
                }
                cache.put(cacheKey, text);
                mem.append(ctx.userPrompt(), text);
                return AIResult.ok(text);
            } catch (AIException e) {
                lastError = e;
                BV.plugin().getLogger().warning(BV.messages().raw("log.provider-failed")
                        .replace("{id}", p.id()).replace("{error}", String.valueOf(e.getMessage())));
            } catch (Throwable t) {
                lastError = new AIException(BV.messages().raw("log.provider-error")
                        .replace("{id}", p.id()).replace("{error}", String.valueOf(t.getMessage())), t, false);
                BV.plugin().getLogger().warning(BV.messages().raw("log.provider-error")
                        .replace("{id}", p.id()).replace("{error}", String.valueOf(t)));
            }
        }
        // 全部失败：记录熔断失败
        if (breaker != null) {
            breaker.recordFailure();
        }
        String fallbackCache = cache.peek(cacheKey);
        // 规范 4.5：三级降级链末级——规则引擎兜底
        return fallbackCache != null ? AIResult.degraded(fallbackCache) : AIResult.degraded(ruleBasedDecision(ctx));
    }

    /** 规则引擎降级决策（规范 4.5 末级）。 */
    private String ruleBasedDecision(AIContext ctx) {
        String scenario = ctx.scenario();
        if (scenario == null) return "HOLD";
        return switch (scenario.toLowerCase()) {
            case "combat", "threat" -> "FLEE";
            case "trade" -> "ACCEPT";
            case "strategic" -> "HOLD";
            // chat / test 等对话场景无规则兜底，返回空标记让调用方识别为降级
            default -> "";
        };
    }

    /** 指数退避重试（规范 1.3：初始 1s，上限 8s）。 */
    private String retry(AIProvider provider, AIRequest req) throws AIException {
        AIException last = null;
        for (int attempt = 0; attempt <= retryAttempts; attempt++) {
            try {
                rateLimiter.acquire();
                return provider.completeBlocking(req);
            } catch (AIException e) {
                last = e;
                if (!e.isRetriable() || attempt == retryAttempts) {
                    throw e;
                }
                sleepBackoff(attempt);
            }
        }
        throw last;
    }

    private void sleepBackoff(int attempt) {
        long delay = Math.min(8000L, 1000L * (1L << attempt));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** SHA-256 摘要前 8 字节（16 位十六进制），避免 hashCode 碰撞。 */
    private static String sha256Short(String input) {
        String normalized = input == null ? "" : input;
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    public MemoryStore memory() {
        return memory;
    }

    public DecisionCache cache() {
        return cache;
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    public boolean isAvailable() {
        return breaker == null || breaker.isAvailable();
    }

    public String primaryProviderId() {
        return primary.id();
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 清理已移除村民的锁（规范 1.3 资源管理）。 */
    public void evictLock(String uuid) {
        villagerLocks.computeIfPresent(uuid, (key, ref) -> ref.users.get() == 0 ? null : ref);
    }
}
