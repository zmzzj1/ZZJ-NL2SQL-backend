package com.queryforge.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryforge.model.ModelHealthRegistry.CircuitState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("熔断状态机与错误分类")
class CircuitBreakerAndFailureClassTest {

    @Test
    @DisplayName("错误分类：429/5xx 可重试，401/400 不可重试")
    void failureClassRetryable() {
        assertThat(FailureClass.fromHttpStatus(429)).isEqualTo(FailureClass.RATE_LIMITED);
        assertThat(FailureClass.fromHttpStatus(503)).isEqualTo(FailureClass.UPSTREAM_5XX);
        assertThat(FailureClass.fromHttpStatus(401)).isEqualTo(FailureClass.AUTH);
        assertThat(FailureClass.fromHttpStatus(400)).isEqualTo(FailureClass.INPUT);

        assertThat(FailureClass.RATE_LIMITED.retryable()).isTrue();
        assertThat(FailureClass.UPSTREAM_5XX.retryable()).isTrue();
        assertThat(FailureClass.AUTH.retryable()).isFalse();
        assertThat(FailureClass.INPUT.retryable()).isFalse();
    }

    @Test
    @DisplayName("熔断：连续失败达到阈值 OPEN，样本不足不误熔断")
    void circuitTripThreshold() {
        ModelHealthRegistry registry = new ModelHealthRegistry();
        // 4 次失败不触发（阈值 5）
        for (int i = 0; i < 4; i++) {
            registry.recordFailure(1L, 100);
        }
        assertThat(registry.circuitState(1L)).isEqualTo(CircuitState.CLOSED);

        // 第 5 次失败触发 OPEN
        registry.recordFailure(1L, 100);
        assertThat(registry.circuitState(1L)).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("熔断：成功恢复 CLOSED，冷却后进入 HALF_OPEN")
    void circuitRecovery() {
        // 冷却时间 0ms，便于测试 HALF_OPEN → CLOSED 恢复
        ModelHealthRegistry registry = new ModelHealthRegistry(0);
        for (int i = 0; i < 5; i++) {
            registry.recordFailure(1L, 100);
        }
        // 冷却 0ms：OPEN 立即进入 HALF_OPEN，半开探测成功 → CLOSED
        assertThat(registry.circuitState(1L)).isEqualTo(CircuitState.HALF_OPEN);
        registry.recordSuccess(1L, 50);
        assertThat(registry.circuitState(1L)).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("熔断：OPEN 状态在冷却期内不半开（长时间冷却）")
    void circuitStaysOpenDuringCooldown() {
        ModelHealthRegistry registry = new ModelHealthRegistry(60_000);
        for (int i = 0; i < 5; i++) {
            registry.recordFailure(1L, 100);
        }
        assertThat(registry.circuitState(1L)).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("成功率/延迟：样本不足返回 -1")
    void smallSampleReturnsUnknown() {
        ModelHealthRegistry registry = new ModelHealthRegistry();
        registry.recordSuccess(1L, 100);
        assertThat(registry.recentSuccessRate(1L)).isEqualTo(-1);
        assertThat(registry.recentAvgLatency(1L)).isEqualTo(-1);

        for (int i = 0; i < 9; i++) {
            registry.recordSuccess(1L, 100);
        }
        assertThat(registry.recentSuccessRate(1L)).isGreaterThan(0);
        assertThat(registry.recentAvgLatency(1L)).isGreaterThan(0);
    }
}
