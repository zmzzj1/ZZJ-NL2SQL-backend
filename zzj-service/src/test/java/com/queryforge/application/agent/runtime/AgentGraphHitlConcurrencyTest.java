package com.queryforge.application.agent.runtime;

import com.queryforge.application.agent.PreparedPlanDecision;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.PlanReviewStatus;
import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P14-ARCH-01 R3 边界：一次性领取、跨用户/错误版本、进程重启续跑、checkpoint 不可用的降级（G10-G13）。
 *
 * <p>全部使用真实 CompiledGraph + 真实 Redis checkpoint + 真实 Redis 审核信封；
 * 只有 MySQL Repository 与 JDBC/Report 是受控替身。
 */
@DisplayName("P14-ARCH-01 R3 领取/重启/降级（G10-G13）")
class AgentGraphHitlConcurrencyTest {

    private static final long OTHER_USER_ID = 99L;

    private final java.util.List<AutoCloseable> closables = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable closable : closables) {
            closable.close();
        }
    }

    // ------------------------------------------------------------------ G10

    @Test
    @DisplayName("G10：并发/重复审核提交 → 至多一个领取成功，业务动作只执行一次")
    void concurrentDecisions_shouldClaimAtMostOnce() throws Exception {
        AgentGraphHitlFixture fixture = register(AgentGraphHitlFixture.create(AgentGraphHitlFixture.GATE_NEEDS_REVIEW));
        fixture.pauseAtGate();

        // 真并发：两线程同时提交同一版本的审核决定（RLock + Redis 令牌 + MySQL CAS 三重裁决）。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger conflict = new AtomicInteger(0);
        try {
            List<java.util.concurrent.Callable<Void>> calls = List.of(() -> {
                ready.countDown();
                awaitLatch(ready);
                try {
                    fixture.prepare(AgentGraphHitlFixture.USER_ID, true, null, 1);
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getErrorCode() == ErrorCode.AGENT_STATE_CONFLICT) {
                        conflict.incrementAndGet();
                    }
                }
                return null;
            }, () -> {
                ready.countDown();
                awaitLatch(ready);
                try {
                    fixture.prepare(AgentGraphHitlFixture.USER_ID, true, null, 1);
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getErrorCode() == ErrorCode.AGENT_STATE_CONFLICT) {
                        conflict.incrementAndGet();
                    }
                }
                return null;
            });
            for (var future : pool.invokeAll(calls)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        verify(fixture.stack.executionService, never()).execute(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("G10：已批准恢复后再提交同一版本 → 图不再暂停于 HITL，重复提交被拒且业务不重放")
    void duplicateAfterResume_shouldBeRejectedWithoutReplay() throws Exception {
        AgentGraphHitlFixture fixture = register(AgentGraphHitlFixture.create(AgentGraphHitlFixture.GATE_NEEDS_REVIEW));
        fixture.pauseAtGate();
        PreparedPlanDecision decision = fixture.prepare(AgentGraphHitlFixture.USER_ID, true, null, 1);
        fixture.resume(decision);

        assertThatThrownBy(() -> fixture.prepare(AgentGraphHitlFixture.USER_ID, true, null, 1))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_STATE_CONFLICT));

        verify(fixture.stack.executionService, times(1))
                .execute(eq(AgentGraphHitlFixture.USER_ID), any(), eq(fixture.runId), anyLong());
        assertThat(fixture.stack.llm.calls("[SQL-CANDIDATE]")).isEqualTo(3);
    }

    // ------------------------------------------------------------------ G11

    @Test
    @DisplayName("G11：错误版本提交 → 版本过期冲突，且没有任何任务被执行")
    void staleVersion_shouldConflictWithoutExecution() throws Exception {
        AgentGraphHitlFixture fixture = register(AgentGraphHitlFixture.create(AgentGraphHitlFixture.GATE_NEEDS_REVIEW));
        fixture.pauseAtGate();

        assertThatThrownBy(() -> fixture.prepare(AgentGraphHitlFixture.USER_ID, true, null, 7))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_STATE_CONFLICT);

        verify(fixture.stack.executionService, never()).execute(anyLong(), any(), anyLong(), anyLong());
        // 图仍暂停于 HITL，正确的版本在之后依旧可恢复（未被误消费）。
        assertThat(fixture.pause().hitlVersion()).isEqualTo(1);
        fixture.decide(AgentGraphHitlFixture.USER_ID, true, null, 1);
        verify(fixture.stack.executionService, times(1))
                .execute(eq(AgentGraphHitlFixture.USER_ID), any(), eq(fixture.runId), anyLong());
    }

    @Test
    @DisplayName("G11：跨用户提交审核 → 404 无权限，原 owner 审核不受影响")
    void crossUser_shouldBeForbidden() throws Exception {
        AgentGraphHitlFixture fixture = register(AgentGraphHitlFixture.create(AgentGraphHitlFixture.GATE_NEEDS_REVIEW));
        fixture.pauseAtGate();

        assertThatThrownBy(() -> fixture.prepare(OTHER_USER_ID, true, null, 1))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_NOT_FOUND);

        verify(fixture.stack.executionService, never()).execute(anyLong(), any(), anyLong(), anyLong());
        // 越权尝试不得消费 owner 的一次性令牌。
        fixture.decide(AgentGraphHitlFixture.USER_ID, true, null, 1);
        verify(fixture.stack.executionService, times(1))
                .execute(eq(AgentGraphHitlFixture.USER_ID), any(), eq(fixture.runId), anyLong());
    }

    @Test
    @DisplayName("G11：审核会话过期（令牌已消失） → 明确冲突，不得自动放行")
    void expiredSession_shouldConflict() throws Exception {
        AgentGraphHitlFixture fixture = register(AgentGraphHitlFixture.create(AgentGraphHitlFixture.GATE_NEEDS_REVIEW));
        fixture.pauseAtGate();
        fixture.stack.planReviewSessionStore.removeRun(fixture.runId);

        assertThat(fixture.stack.planReviewSessionStore
                .claim(fixture.runId, AgentGraphHitlFixture.USER_ID, 1))
                .isEqualTo(PlanReviewSessionStore.Claim.EXPIRED);
        assertThatThrownBy(() -> fixture.prepare(AgentGraphHitlFixture.USER_ID, true, null, 1))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_STATE_CONFLICT);

        verify(fixture.stack.executionService, never()).execute(anyLong(), any(), anyLong(), anyLong());
        verify(fixture.stack.repository, never())
                .claimPlanApproval(anyLong(), anyLong(), anyInt());
    }

    // ------------------------------------------------------------------ G12

    @Test
    @DisplayName("G12：重建 Runner（模拟进程重启）后，能按同一 threadId 的真实 checkpoint 恢复并只执行一次")
    void restart_shouldResumeSameThreadCheckpoint() throws Exception {
        AgentGraphHitlFixture fixture = register(AgentGraphHitlFixture.create(AgentGraphHitlFixture.GATE_NEEDS_REVIEW));
        fixture.pauseAtGate();
        String threadId = fixture.stack.runner.snapshotOf(fixture.runId).orElseThrow()
                .config().threadId().orElseThrow();

        // 第二个 Runner：全新的 CompiledGraph 实例（同一 Redis），只复用同一 threadId。
        AgentGraphStack restarted =
                register(AgentGraphStack.create(AgentGraphStack.props(3, 2, 12, 2,
                        com.queryforge.domain.agent.AgentProperties.DISPATCH_OFF)));
        stubRestartedStack(restarted, fixture, threadId);

        assertThat(restarted.runner.hasCheckpoint(fixture.runId)).isTrue();
        assertThat(restarted.runner.pauseOf(fixture.runId).orElseThrow().nextNode())
                .isEqualTo(AgentGraphNodes.NODE_HITL);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        restarted.runner.resumeDecision(fixture.runId, AgentGraphHitlFixture.USER_ID, 1, true, "",
                restarted.capturingWriter(out));
        String resumed = out.toString(StandardCharsets.UTF_8);

        assertThat(resumed).contains("event: plan.review_decided");
        assertThat(resumed).contains("event: step.succeeded");
        assertThat(resumed).contains("event: run.completed");
        // 业务在重启后的新实例上只执行一次；旧实例从未执行。
        verify(restarted.executionService, times(1))
                .execute(eq(AgentGraphHitlFixture.USER_ID), any(), eq(fixture.runId), anyLong());
        verify(fixture.stack.executionService, never()).execute(anyLong(), any(), anyLong(), anyLong());
        // 多候选路径：一个取数步骤 = 3 条候选生成 + 1 次评分，全部计入模型预算。
        assertThat(restarted.llm.calls("[SQL-CANDIDATE]")).isEqualTo(3);
        assertThat(restarted.llm.calls("[SQL-SCORER]")).isEqualTo(1);
        assertThat(fixture.stack.llm.calls("[SQL-CANDIDATE]")).isZero();
    }

    // ------------------------------------------------------------------ G13

    @Test
    @DisplayName("G13：Graph checkpoint 不可用 → 不伪装审核成功，回滚一次性领取并保持等待")
    void checkpointUnavailable_shouldRollbackClaim() throws Exception {
        AgentGraphHitlFixture fixture = register(AgentGraphHitlFixture.create(AgentGraphHitlFixture.GATE_NEEDS_REVIEW));
        fixture.pauseAtGate();
        PreparedPlanDecision decision = fixture.prepare(AgentGraphHitlFixture.USER_ID, false, "请换口径", 1);
        // 领取成功后图检查点失效（Saver 写入失败/被驱逐）：不得把 Run 推进到下一节点。
        when(fixture.stack.repository.findGraphThreadId(fixture.runId))
                .thenReturn(Optional.of(UUID.randomUUID().toString()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fixture.stack.service.continuePlanReview(decision, out);
        String failed = out.toString(StandardCharsets.UTF_8);

        assertThat(failed).contains("event: plan.review_failed");
        assertThat(failed).doesNotContain("event: plan.review_decided");
        assertThat(failed).doesNotContain("event: step.succeeded");
        verify(fixture.stack.executionService, never()).execute(anyLong(), any(), anyLong(), anyLong());
        // 一次性领取被补偿回滚：状态回到 AWAITING，拒绝计数也回滚（不会提前触发第三次终结）。
        verify(fixture.stack.repository)
                .markPlanReviewStatus(fixture.runId, AgentGraphHitlFixture.USER_ID, PlanReviewStatus.AWAITING);
        verify(fixture.stack.repository).compensatePlanRejection(fixture.runId);
        assertThat(fixture.rejectionCount.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 装配

    private <T extends AutoCloseable> T register(T closable) {
        closables.add(closable);
        return closable;
    }

    private void stubRestartedStack(AgentGraphStack restarted, AgentGraphHitlFixture fixture, String threadId) {
        restarted.stubCommonContext(AgentGraphHitlFixture.USER_ID, AgentGraphHitlFixture.CONNECTION_ID,
                AgentGraphHitlFixture.CONVERSATION_ID,
                "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL, created_at DATETIME)");
        restarted.stubGraphCheckpointStore();
        restarted.stubGraphThreadBinding();
        restarted.llm.on("[PLANNER]", AgentGraphHitlFixture.PLAN_JSON)
                .on("[PLAN-REVIEW]", AgentGraphHitlFixture.GATE_NEEDS_REVIEW)
                .on("[SQL]", AgentGraphHitlFixture.SQL)
                .on("[SQL-CANDIDATE]", AgentGraphHitlFixture.SQL)
                .on("[SQL-SCORER]", AgentGraphHitlFixture.SCORER_JSON)
                .on("[SUMMARY]", "重启后续跑完成。")
                .fallback("{}");

        when(restarted.configResolver.resolveForRun(any(), any(), any()))
                .thenReturn(ResolvedAgentConfig.defaults());
        when(restarted.guard.validate(anyString(), anyString())).thenReturn(AgentGraphHitlFixture.SQL);
        when(restarted.executionService.execute(eq(AgentGraphHitlFixture.USER_ID), any(),
                eq(fixture.runId), anyLong()))
                .thenReturn(new com.queryforge.domain.query.QueryResultResponse(
                        List.of("month", "total"), List.of(java.util.Map.of("month", "2026-01", "total", 128)),
                        1, false));
        // 同一 threadId：新实例只认 MySQL 中的 runId↔threadId 归属映射。
        when(restarted.repository.findGraphThreadId(fixture.runId)).thenReturn(Optional.of(threadId));
        AgentRun awaitingRun = fixture.run(RunStatus.WAITING_PLAN_REVIEW, null, 1,
                PlanReviewStatus.AWAITING, false);
        when(restarted.repository.findRunOwned(eq(fixture.runId), anyLong()))
                .thenReturn(Optional.of(awaitingRun));
        when(restarted.repository.findRun(fixture.runId)).thenReturn(Optional.of(awaitingRun));
        when(restarted.repository.findSteps(fixture.runId))
                .thenReturn(new java.util.ArrayList<>(fixture.steps.values()));
        when(restarted.repository.claimNonSqlStep(eq(fixture.runId), anyLong())).thenReturn(1);
        when(restarted.repository.setStepSql(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(1);
        when(restarted.repository.completeStep(anyLong(), anyInt(), Mockito.anyBoolean(), anyString()))
                .thenReturn(1);
        Mockito.lenient().when(restarted.repository.updateRunStatus(eq(fixture.runId), any(), any(), any(), any()))
                .thenReturn(1);
        Mockito.lenient().when(restarted.repository.transitionRunStatus(eq(fixture.runId), any())).thenReturn(1);
        Mockito.lenient().when(restarted.repository.tryIncrementLlmCalls(fixture.runId, 12)).thenReturn(1);
        Mockito.lenient().when(restarted.repository.insertStep(eq(fixture.runId), anyInt(), anyString(),
                nullable(Integer.class), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(9001L);
    }

    private static void awaitLatch(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发提交未按时就绪");
        }
    }
}
