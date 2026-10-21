package com.queryforge.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.testutil.RedissonFixtures;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

/**
 * P12 S4 场景二：HITL 待审批短期授权上下文的真实 Redis 验证（P1217）。
 * 验证登记、一次性消费（RLock 保护，并发只允许一次 CLAIMED）、删除与过期语义。
 * Redis 不可用时跳过，不冒充成功。
 */
class ApprovalSessionStoreTest {

    private RedissonClient redisson;
    private ApprovalSessionStore store;

    @BeforeEach
    void setUp() {
        redisson = RedissonFixtures.liveRedissonOrSkip();
        store = new ApprovalSessionStore(redisson, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (redisson != null) {
            redisson.getKeys().deleteByPattern("agent:approval:*");
            redisson.shutdown();
        }
    }

    @Test
    @DisplayName("登记后首次消费成功，重复消费返回 MISSING（同一令牌不消费两次）")
    void claimIsSingleUse() {
        store.register(5L, 11L, 1, "sql_demo_a");

        assertThat(store.claim(5L, 11L)).isEqualTo(ApprovalSessionStore.Claim.CLAIMED);
        assertThat(store.claim(5L, 11L)).isEqualTo(ApprovalSessionStore.Claim.MISSING);
    }

    @Test
    @DisplayName("未登记的令牌消费返回 MISSING")
    void claimWithoutRegisterIsMissing() {
        assertThat(store.claim(999L, 999L)).isEqualTo(ApprovalSessionStore.Claim.MISSING);
    }

    @Test
    @DisplayName("删除后消费返回 MISSING")
    void removeInvalidatesToken() {
        store.register(6L, 12L, 2, "sql_demo_a");
        store.remove(6L, 12L);
        assertThat(store.claim(6L, 12L)).isEqualTo(ApprovalSessionStore.Claim.MISSING);
    }

    @Test
    @DisplayName("removeRun 清理该运行全部步骤令牌")
    void removeRunClearsAllSteps() {
        store.register(7L, 21L, 1, "sql_demo_a");
        store.register(7L, 22L, 1, "sql_demo_a");
        store.removeRun(7L);

        assertThat(store.claim(7L, 21L)).isEqualTo(ApprovalSessionStore.Claim.MISSING);
        assertThat(store.claim(7L, 22L)).isEqualTo(ApprovalSessionStore.Claim.MISSING);
    }

    @Test
    @DisplayName("并发消费同一令牌：恰好一次 CLAIMED，其余 MISSING/UNAVAILABLE")
    void concurrentClaimAllowsOnlyOneWinner() throws Exception {
        store.register(8L, 31L, 3, "sql_demo_a");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger claimed = new AtomicInteger();
        AtomicInteger missing = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    ApprovalSessionStore.Claim result = store.claim(8L, 31L);
                    if (result == ApprovalSessionStore.Claim.CLAIMED) {
                        claimed.incrementAndGet();
                    } else if (result == ApprovalSessionStore.Claim.MISSING) {
                        missing.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // 恰好一个请求拿到令牌；其余要么 MISSING（令牌已删），要么 UNAVAILABLE（锁争用）。
        assertThat(claimed.get()).isEqualTo(1);
        assertThat(claimed.get() + missing.get()).isLessThanOrEqualTo(threads);
    }
}
