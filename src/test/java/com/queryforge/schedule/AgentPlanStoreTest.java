package com.queryforge.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.schedule.AgentPlanStore.PlanSnapshot;
import com.queryforge.schedule.AgentPlanStore.PlanStep;
import com.queryforge.testutil.RedissonFixtures;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

/**
 * P12 S4 场景一：Agent 计划短期记忆的真实 Redis 验证（P1216）。
 * 验证保存/读取、RLock 保护下的进度推进、TTL 与删除；Redis 不可用时跳过，不冒充成功。
 */
class AgentPlanStoreTest {

    private RedissonClient redisson;
    private AgentPlanStore store;

    @BeforeEach
    void setUp() {
        redisson = RedissonFixtures.liveRedissonOrSkip();
        store = new AgentPlanStore(redisson, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (redisson != null) {
            redisson.getKeys().deleteByPattern("agent:plan:*");
            redisson.shutdown();
        }
    }

    private PlanSnapshot snapshot(long runId) {
        return PlanSnapshot.of(runId, List.of(
                new PlanStep(1, "统计订单", false),
                new PlanStep(2, "按渠道拆分", false)));
    }

    @Test
    @DisplayName("保存后可读回，且进度推进只影响指定步骤")
    void saveThenLoad_roundTripsSnapshot() {
        store.save("5", snapshot(5L));

        Optional<PlanSnapshot> loaded = store.load("5");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().runId()).isEqualTo(5L);
        assertThat(loaded.get().steps()).hasSize(2);
        assertThat(loaded.get().steps().get(0).completed()).isFalse();

        store.withLock("5", () -> store.load("5")
                .ifPresent(s -> store.save("5", s.withCompleted(1))));

        PlanSnapshot progressed = store.load("5").orElseThrow();
        assertThat(progressed.steps().get(0).completed()).isTrue();
        assertThat(progressed.steps().get(1).completed()).isFalse();
    }

    @Test
    @DisplayName("删除后读取为空；未保存的键读取为空")
    void removeAndMissingBehaveAsExpected() {
        store.save("9", snapshot(9L));
        store.remove("9");
        assertThat(store.load("9")).isEmpty();
        assertThat(store.load("never-saved")).isEmpty();
    }

    @Test
    @DisplayName("TTL 生效：到期后快照自动失效（Redis 键过期）")
    void ttlExpiresEntry() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                java.util.Map.of("runId", 7, "steps", List.of()));
        redisson.<String>getBucket("agent:plan:7").set(json, Duration.ofSeconds(1));
        assertThat(store.load("7")).isPresent();

        Thread.sleep(1500);
        assertThat(store.load("7")).isEmpty();
    }

    @Test
    @DisplayName("保存的键带 TTL（非永久键）")
    void savedKeyHasTtl() {
        store.save("8", snapshot(8L));
        long ttl = redisson.getBucket("agent:plan:8").remainTimeToLive();
        assertThat(ttl).isPositive();
    }
}
