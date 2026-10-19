package com.queryforge.model;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 单实例内存模型健康与统计窗口（P9 O3/O4 的基础）。
 * 记录每个 profileId 的近期调用结果、延迟、熔断状态，供路由策略与熔断使用。
 * 单实例内存限流不声称全局配额；跨实例一致性见 O6。
 */
@Component
public class ModelHealthRegistry {

    public enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }

    private static final int DEFAULT_WINDOW_SIZE = 50;

    private final ConcurrentHashMap<Long, ProfileStats> stats = new ConcurrentHashMap<>();
    private final long openCooldownMs;

    public ModelHealthRegistry() {
        this(10_000);
    }

    public ModelHealthRegistry(long openCooldownMs) {
        this.openCooldownMs = openCooldownMs;
    }

    public void recordSuccess(Long profileId, long latencyMs) {
        stats(profileId).record(true, latencyMs);
    }

    public void recordFailure(Long profileId, long latencyMs) {
        stats(profileId).record(false, latencyMs);
    }

    public CircuitState circuitState(Long profileId) {
        return stats(profileId).circuitState();
    }

    /** 最近窗口成功率（0..1）；样本不足返回 -1 表示未知。 */
    public double recentSuccessRate(Long profileId) {
        return stats(profileId).recentSuccessRate();
    }

    /** 最近窗口平均延迟；样本不足返回 -1。 */
    public double recentAvgLatency(Long profileId) {
        return stats(profileId).recentAvgLatency();
    }

    /** 熔断半开探测许可：只有 CLOSED→OPEN 后进入 HALF_OPEN 时才允许一个探测请求。 */
    public boolean allowProbe(Long profileId) {
        return stats(profileId).allowProbe();
    }

    /** 记录熔断触发（外部调用，如连续失败达到阈值）。 */
    public void trip(Long profileId) {
        stats(profileId).trip();
    }

    private ProfileStats stats(Long profileId) {
        return stats.computeIfAbsent(profileId, key -> new ProfileStats(openCooldownMs));
    }

    private static final class ProfileStats {
        private final Deque<Boolean> window = new ConcurrentLinkedDeque<>();
        private final Deque<Long> latencies = new ConcurrentLinkedDeque<>();
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicReference<CircuitState> circuit = new AtomicReference<>(CircuitState.CLOSED);
        private final AtomicLong halfOpenSince = new AtomicLong(0);
        private final long openCooldownMs;

        private static final int FAILURE_THRESHOLD = 5;

        ProfileStats(long openCooldownMs) {
            this.openCooldownMs = openCooldownMs;
        }

        void record(boolean success, long latencyMs) {
            window.addLast(success);
            latencies.addLast(latencyMs);
            while (window.size() > DEFAULT_WINDOW_SIZE) {
                window.removeFirst();
            }
            while (latencies.size() > DEFAULT_WINDOW_SIZE) {
                latencies.removeFirst();
            }
            if (success) {
                consecutiveFailures.set(0);
                if (circuit.get() == CircuitState.HALF_OPEN) {
                    circuit.set(CircuitState.CLOSED);
                }
            } else {
                int failures = consecutiveFailures.incrementAndGet();
                if (failures >= FAILURE_THRESHOLD && circuit.get() == CircuitState.CLOSED) {
                    circuit.set(CircuitState.OPEN);
                    halfOpenSince.set(Instant.now().toEpochMilli());
                }
            }
        }

        CircuitState circuitState() {
            CircuitState state = circuit.get();
            if (state == CircuitState.OPEN && halfOpenSince.get() > 0
                    && Instant.now().toEpochMilli() - halfOpenSince.get() >= openCooldownMs) {
                circuit.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN);
                return CircuitState.HALF_OPEN;
            }
            return circuit.get();
        }

        boolean allowProbe() {
            return circuitState() == CircuitState.HALF_OPEN;
        }

        void trip() {
            circuit.set(CircuitState.OPEN);
            halfOpenSince.set(Instant.now().toEpochMilli());
        }

        double recentSuccessRate() {
            if (window.size() < 5) {
                return -1;
            }
            long success = window.stream().filter(Boolean::booleanValue).count();
            return (double) success / window.size();
        }

        double recentAvgLatency() {
            if (latencies.size() < 5) {
                return -1;
            }
            return latencies.stream().mapToLong(Long::longValue).average().orElse(-1);
        }
    }
}
