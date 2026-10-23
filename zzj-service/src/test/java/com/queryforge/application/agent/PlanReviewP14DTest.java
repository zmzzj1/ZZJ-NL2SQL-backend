package com.queryforge.application.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.agent.AgentPlan;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.PlanReviewStatus;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.model.LlmClient;
import com.queryforge.domain.model.LlmStream;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

/**
 * P14-D：计划级风险审核的定向测试。
 *
 * <p>覆盖三组核心不变量：
 *
 * <ol>
 *   <li>Gate 评估：正常判定、代码围栏解析，以及模型失败/非法返回时的如实降级；
 *   <li>一次性领取：同一审核版本只有一个决定胜出，重复、越权、版本冲突全部拒绝；
 *   <li>向后兼容：autoConfirm 缺省为 true，旧运行不进入等待态。
 * </ol>
 */
@DisplayName("P14-D 计划级风险审核")
class PlanReviewP14DTest {

    private static final Long RUN_ID = 5L;
    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 99L;

    // --------------------------------------------------------- T02 Gate 评估

    @Test
    @DisplayName("Gate 判定需审核时返回 NEEDS_REVIEW 且标记为已评估")
    void gate_shouldReturnNeedsReviewWhenModelFlagsRisk() {
        PlanReviewGate gate = gateReturning("{\"needs_review\": true, \"reason\": \"计划包含跨库聚合\"}");

        PlanReviewDecision decision = gate.evaluate(run(Boolean.FALSE), "", plan());

        assertThat(decision.needsReview()).isTrue();
        assertThat(decision.modelEvaluated()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo("NEEDS_REVIEW");
        assertThat(decision.reason()).isEqualTo("计划包含跨库聚合");
    }

    @Test
    @DisplayName("Gate 判定无需审核时返回 NOT_REQUIRED")
    void gate_shouldReturnNotRequiredWhenModelApproves() {
        PlanReviewGate gate = gateReturning("{\"needs_review\": false, \"reason\": \"只读单表查询\"}");

        PlanReviewDecision decision = gate.evaluate(run(Boolean.FALSE), "", plan());

        assertThat(decision.needsReview()).isFalse();
        assertThat(decision.modelEvaluated()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo("NOT_REQUIRED");
    }

    @Test
    @DisplayName("模型调用失败时如实降级：不审核放行且标记未评估")
    void gate_shouldDegradeWhenLlmUnavailable() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.openChat(anyString(), anyString()))
                .thenThrow(new IllegalStateException("上游不可用"));
        PlanReviewGate gate =
                new PlanReviewGate(llmClient, new ObjectMapper(), properties(), passthroughLimiter());

        PlanReviewDecision decision = gate.evaluate(run(Boolean.FALSE), "", plan());

        assertThat(decision.modelEvaluated()).isFalse();
        assertThat(decision.needsReview()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("GATE_UNAVAILABLE");
    }

    @Test
    @DisplayName("模型返回无法解析的内容时降级为不可审核")
    void gate_shouldDegradeWhenPayloadUnparseable() {
        PlanReviewGate gate = gateReturning("这不是 JSON");

        PlanReviewDecision decision = gate.evaluate(run(Boolean.FALSE), "", plan());

        assertThat(decision.modelEvaluated()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("GATE_UNPARSEABLE");
    }

    @Test
    @DisplayName("模型返回内容被 markdown 代码围栏包裹时仍能解析")
    void gate_shouldStripCodeFence() {
        PlanReviewGate gate =
                gateReturning("```json\n{\"needs_review\": true, \"reason\": \"含写操作风险\"}\n```");

        PlanReviewDecision decision = gate.evaluate(run(Boolean.FALSE), "", plan());

        assertThat(decision.needsReview()).isTrue();
        assertThat(decision.reason()).isEqualTo("含写操作风险");
    }

    // ----------------------------------------------------- T05 一次性领取语义

    @Test
    @DisplayName("同一待审核版本的令牌只能被领取一次")
    void sessionStore_shouldClaimOncePerVersion() {
        PlanReviewSessionStore store = new FakeRedis().store();
        store.register(RUN_ID, USER_ID, 7L, null, 10L, 1, "AGENT_RUN");

        assertThat(store.claim(RUN_ID, USER_ID, 1)).isEqualTo(PlanReviewSessionStore.Claim.CLAIMED);
        assertThat(store.claim(RUN_ID, USER_ID, 1)).isEqualTo(PlanReviewSessionStore.Claim.EXPIRED);
    }

    @Test
    @DisplayName("非所属用户提交决定被拒绝（越权），且不消耗令牌")
    void sessionStore_shouldRejectForeignUser() {
        PlanReviewSessionStore store = new FakeRedis().store();
        store.register(RUN_ID, USER_ID, 7L, null, 10L, 3, "AGENT_RUN");

        assertThat(store.claim(RUN_ID, OTHER_USER_ID, 3)).isEqualTo(PlanReviewSessionStore.Claim.FORBIDDEN);
        assertThat(store.claim(RUN_ID, USER_ID, 3)).isEqualTo(PlanReviewSessionStore.Claim.CLAIMED);
    }

    @Test
    @DisplayName("提交的审核版本与实际登记版本不一致时一律拒绝，不会误批准")
    void sessionStore_shouldRejectMismatchedVersion() {
        PlanReviewSessionStore store = new FakeRedis().store();
        store.register(RUN_ID, USER_ID, 7L, null, 10L, 4, "AGENT_RUN");

        // key 由 runId+version 组成，版本不同的 key 找不到令牌（EXPIRED），绝不放行。
        assertThat(store.claim(RUN_ID, USER_ID, 2)).isEqualTo(PlanReviewSessionStore.Claim.EXPIRED);
        // 原登记版本未受影响，仍可被正常领取一次。
        assertThat(store.claim(RUN_ID, USER_ID, 4)).isEqualTo(PlanReviewSessionStore.Claim.CLAIMED);
    }

    @Test
    @DisplayName("未登记令牌的运行不能凭空提交审核决定")
    void sessionStore_shouldRejectUnregisteredRun() {
        PlanReviewSessionStore store = new FakeRedis().store();

        assertThat(store.claim(RUN_ID, USER_ID, 1)).isEqualTo(PlanReviewSessionStore.Claim.EXPIRED);
    }

    @Test
    @DisplayName("取消运行会清理该运行全部待审核令牌")
    void sessionStore_shouldClearAllVersionsOnCancel() {
        PlanReviewSessionStore store = new FakeRedis().store();
        store.register(RUN_ID, USER_ID, 7L, null, 10L, 5, "AGENT_RUN");
        store.register(RUN_ID, USER_ID, 7L, null, 10L, 6, "AGENT_RUN");

        store.removeRun(RUN_ID);

        assertThat(store.claim(RUN_ID, USER_ID, 5)).isEqualTo(PlanReviewSessionStore.Claim.EXPIRED);
        assertThat(store.claim(RUN_ID, USER_ID, 6)).isEqualTo(PlanReviewSessionStore.Claim.EXPIRED);
    }

    @Test
    @DisplayName("登记后未领取前可显式删除令牌（拒绝/冲突路径）")
    void sessionStore_shouldSupportExplicitRemoval() {
        PlanReviewSessionStore store = new FakeRedis().store();
        store.register(RUN_ID, USER_ID, 7L, null, 10L, 8, "AGENT_RUN");

        store.remove(RUN_ID, 8);

        assertThat(store.claim(RUN_ID, USER_ID, 8)).isEqualTo(PlanReviewSessionStore.Claim.EXPIRED);
    }

    // ------------------------------------------------- T01 审核模式与状态解析

    @Test
    @DisplayName("autoConfirm 缺省时按 true 处理，不进入计划审核等待")
    void autoConfirm_shouldDefaultToTrue() {
        PreparedRun prepared = new PreparedRun(RUN_ID, USER_ID, 7L, 10L, "sql_demo_a", "问题");

        assertThat(prepared.planAutoConfirm()).isTrue();
    }

    @Test
    @DisplayName("显式传入 autoConfirm=false 时随运行固定该快照")
    void autoConfirm_shouldPersistExplicitFalse() {
        PreparedRun prepared = new PreparedRun(RUN_ID, USER_ID, 7L, 10L, "sql_demo_a", "问题",
                null, null, null, Boolean.FALSE);

        assertThat(prepared.planAutoConfirm()).isFalse();
    }

    @Test
    @DisplayName("计划审核状态枚举可解析，未知值回退为 null")
    void planReviewStatus_shouldParseAndFallback() {
        assertThat(PlanReviewStatus.parse("AWAITING")).isEqualTo(PlanReviewStatus.AWAITING);
        assertThat(PlanReviewStatus.parse("REPLAN_REQUIRED")).isEqualTo(PlanReviewStatus.REPLAN_REQUIRED);
        assertThat(PlanReviewStatus.parse("EVAL_UNAVAILABLE")).isEqualTo(PlanReviewStatus.EVAL_UNAVAILABLE);
        assertThat(PlanReviewStatus.parse("NOT_A_STATUS")).isNull();
        assertThat(PlanReviewStatus.parse(null)).isNull();
    }

    @Test
    @DisplayName("降级与跳过决策一律不带审核通过语义")
    void planReviewDecision_shouldNeverFakeApproval() {
        assertThat(PlanReviewDecision.skip().needsReview()).isFalse();
        assertThat(PlanReviewDecision.skip().modelEvaluated()).isFalse();
        assertThat(PlanReviewDecision.notRequired("ok").needsReview()).isFalse();
        assertThat(PlanReviewDecision.notRequired("ok").modelEvaluated()).isTrue();
        assertThat(PlanReviewDecision.unavailable("X").modelEvaluated()).isFalse();
        assertThat(PlanReviewDecision.unavailable("X").needsReview()).isFalse();
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 构造返回指定“模型文本”的 Gate。注意上游是 OpenAI 兼容 SSE，
     * 文本必须放在 {@code choices[0].delta.content} 才会被聚合。
     */
    private static PlanReviewGate gateReturning(String modelText) {
        LlmClient llmClient = mock(LlmClient.class);
        String payload;
        try {
            String content = new ObjectMapper().writeValueAsString(modelText);
            payload = "{\"choices\":[{\"delta\":{\"content\":" + content + "}}]}";
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        when(llmClient.openChat(anyString(), anyString()))
                .thenReturn(new LlmStream(Stream.of("data: " + payload)));
        return new PlanReviewGate(llmClient, new ObjectMapper(), properties(), passthroughLimiter());
    }

    /** 计数限流器：直连真实对象但不对调用次数设限（配额语义由其它测试覆盖）。 */
    private static AgentLlmCallLimiter passthroughLimiter() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        when(repository.tryIncrementLlmCalls(any(), anyInt())).thenReturn(1);
        return new AgentLlmCallLimiter(repository, properties());
    }

    private static AgentProperties properties() {
        return new AgentProperties(3, 2, 6000, 12);
    }

    private static AgentRun run(Boolean planAutoConfirm) {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new AgentRun(RUN_ID, USER_ID, 7L, 10L, "A 商店库", "问题", RunStatus.PLANNING,
                null, null, null, 0, null, null, null, now, now,
                null, null, null, null,
                planAutoConfirm, 0, null, false, null, 0, null);
    }

    private static AgentPlan plan() {
        return new AgentPlan(true, "", List.of(new AgentPlan.Step("统计销售额", "DATA_ANALYST", null)));
    }

    /** 极简内存 Redisson 替身：仅实现待审核令牌所需的 RBucket/RLock/RKeys 行为。 */
    private static final class FakeRedis {
        private final Map<String, String> buckets = new HashMap<>();

        PlanReviewSessionStore store() {
            RedissonClient redisson = mock(RedissonClient.class);
            when(redisson.getBucket(anyString())).thenAnswer((invocation) -> bucket(invocation.getArgument(0)));
            when(redisson.getLock(anyString())).thenAnswer((invocation) -> lock());
            RKeys keys = mock(RKeys.class);
            when(redisson.getKeys()).thenReturn(keys);
            when(keys.deleteByPattern(anyString())).thenAnswer((invocation) -> {
                String pattern = invocation.getArgument(0);
                String prefix = pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : pattern;
                List<String> matched = new ArrayList<>();
                for (String key : buckets.keySet()) {
                    if (key.startsWith(prefix)) {
                        matched.add(key);
                    }
                }
                matched.forEach(buckets::remove);
                return (long) matched.size();
            });
            return new PlanReviewSessionStore(redisson, new ObjectMapper());
        }

        @SuppressWarnings("unchecked")
        private RBucket<String> bucket(String key) {
            RBucket<String> bucket = mock(RBucket.class);
            when(bucket.get()).thenAnswer((invocation) -> buckets.get(key));
            doAnswer((invocation) -> {
                buckets.put(key, invocation.getArgument(0));
                return null;
            }).when(bucket).set(anyString(), any(Duration.class));
            when(bucket.delete()).thenAnswer((invocation) -> buckets.remove(key) != null);
            return bucket;
        }

        private RLock lock() {
            RLock lock = mock(RLock.class);
            when(lock.isHeldByCurrentThread()).thenReturn(true);
            try {
                when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return lock;
        }
    }
}
