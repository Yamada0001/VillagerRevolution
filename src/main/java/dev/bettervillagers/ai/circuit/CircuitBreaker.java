package dev.bettervillagers.ai.circuit;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 熔断器（规范 4.5：错误率超阈值自动熔断，期间降级为规则行为）。
 * <p>
 * 三态：CLOSED（正常）→ OPEN（熔断，拒绝请求）→ HALF_OPEN（试探放行单请求）→ CLOSED/OPEN。
 * 基于滚动时间窗口（默认 60s）统计失败率。
 * <p>
 * 线程安全：所有方法均通过 synchronized(this) 保护状态一致性。
 */
public final class CircuitBreaker {

    public enum State {CLOSED, OPEN, HALF_OPEN}

    private static final int MAX_SAMPLES = 10000;

    private final double failureThresholdPercent;
    private final int minRequests;
    private final long openDurationMs;
    private final long windowMs;

    private final Deque<Sample> window = new ArrayDeque<>();
    private State state = State.CLOSED;
    private long openedAt = 0L;
    private boolean halfOpenProbeInFlight = false;

    public CircuitBreaker(double failureThresholdPercent, int minRequests, long openDurationMs, long windowMs) {
        this.failureThresholdPercent = failureThresholdPercent;
        this.minRequests = minRequests;
        this.openDurationMs = openDurationMs;
        this.windowMs = windowMs;
    }

    /** 是否允许放行新请求（须在调用 provider 前判断）。 */
    public synchronized boolean allowRequest() {
        prune();
        switch (state) {
            case OPEN -> {
                if (System.currentTimeMillis() - openedAt >= openDurationMs) {
                    state = State.HALF_OPEN;
                    halfOpenProbeInFlight = true;
                    return true;
                }
                return false;
            }
            case HALF_OPEN -> {
                // 仅允许一个试探请求在途
                if (halfOpenProbeInFlight) {
                    return false;
                }
                halfOpenProbeInFlight = true;
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    /** 完成仅由 HALF_OPEN 探针占用的缓存请求，不计入 CLOSED 统计窗口。 */
    public synchronized void completeProbeSuccess() {
        if (state == State.HALF_OPEN && halfOpenProbeInFlight) {
            halfOpenProbeInFlight = false;
            state = State.CLOSED;
        }
    }

    /** 只读取可用状态，不申请 HALF_OPEN 探针。 */
    public synchronized boolean isAvailable() {
        prune();
        return state != State.OPEN || System.currentTimeMillis() - openedAt >= openDurationMs;
    }

    public synchronized void recordSuccess() {
        while (window.size() >= MAX_SAMPLES) {
            window.pollFirst();
        }
        window.addLast(new Sample(System.currentTimeMillis(), true));
        halfOpenProbeInFlight = false;
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
        }
    }

    public synchronized void recordFailure() {
        while (window.size() >= MAX_SAMPLES) {
            window.pollFirst();
        }
        window.addLast(new Sample(System.currentTimeMillis(), false));
        halfOpenProbeInFlight = false;
        if (state == State.HALF_OPEN) {
            tripOpen();
            return;
        }
        prune();
        int total = window.size();
        if (total >= minRequests) {
            long fails = window.stream().filter(s -> !s.success).count();
            double rate = fails * 100.0 / total;
            if (rate >= failureThresholdPercent) {
                tripOpen();
            }
        }
    }

    private void tripOpen() {
        state = State.OPEN;
        openedAt = System.currentTimeMillis();
    }

    private void prune() {
        long cutoff = System.currentTimeMillis() - windowMs;
        while (!window.isEmpty() && window.peekFirst().time < cutoff) {
            window.pollFirst();
        }
    }

    public synchronized State state() {
        return state;
    }

    /**
     * 重置熔断器到 CLOSED 状态并清空历史采样（规范 4.5）。
     * <p>
     * 修复问题1：AI 服务不可用后熔断器进入 OPEN 状态，
     * 即使修改配置（正确的 api-key）也因熔断器持续拒绝请求而无法恢复。
     * 在 reconfigure 时调用此方法清除历史失败记录，允许新 provider 立即试探。
     */
    public synchronized void reset() {
        window.clear();
        state = State.CLOSED;
        openedAt = 0L;
        halfOpenProbeInFlight = false;
    }

    private record Sample(long time, boolean success) {
    }
}
