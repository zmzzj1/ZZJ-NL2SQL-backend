package com.queryforge.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryforge.testutil.RedissonFixtures;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

/**
 * P12 S4 场景三：模型会话亲和性绑定/读取/解除的真实 Redis 验证（P1219）。
 * Redis 不可用时跳过，不冒充成功。
 */
class ModelSessionAffinityTest {

    private RedissonClient redisson;
    private ModelSessionAffinity affinity;

    @BeforeEach
    void setUp() {
        redisson = RedissonFixtures.liveRedissonOrSkip();
        affinity = new ModelSessionAffinity(redisson);
    }

    @AfterEach
    void tearDown() {
        if (redisson != null) {
            redisson.getKeys().deleteByPattern("model:affinity:*");
            redisson.shutdown();
        }
    }

    @Test
    @DisplayName("绑定后可读回，解绑后为空；跨用户/跨会话隔离")
    void bindResolveUnbind() {
        affinity.bind(1L, 100L, 42L);
        affinity.bind(2L, 100L, 43L);
        affinity.bind(1L, 101L, 44L);

        assertThat(affinity.resolve(1L, 100L)).contains(42L);
        assertThat(affinity.resolve(2L, 100L)).contains(43L);
        assertThat(affinity.resolve(1L, 101L)).contains(44L);

        affinity.unbind(1L, 100L);
        assertThat(affinity.resolve(1L, 100L)).isEmpty();
        // 解除不影响其他用户的绑定
        assertThat(affinity.resolve(2L, 100L)).contains(43L);
    }

    @Test
    @DisplayName("未绑定的会话解析为空；空参数安全")
    void resolveMissingAndNullSafe() {
        assertThat(affinity.resolve(1L, 999L)).isEmpty();
        affinity.bind(null, 1L, 1L);
        affinity.bind(1L, null, 1L);
        assertThat(affinity.resolve(null, 1L)).isEmpty();
        assertThat(affinity.resolve(1L, null)).isEmpty();
        affinity.unbind(null, 1L);
    }

    @Test
    @DisplayName("绑定键带 TTL（短期状态，非永久）")
    void bindingHasTtl() {
        affinity.bind(3L, 300L, 7L);
        long ttl = redisson.getBucket("model:affinity:3:300").remainTimeToLive();
        assertThat(ttl).isPositive();
        Optional<Long> resolved = affinity.resolve(3L, 300L);
        assertThat(resolved).contains(7L);
    }
}
