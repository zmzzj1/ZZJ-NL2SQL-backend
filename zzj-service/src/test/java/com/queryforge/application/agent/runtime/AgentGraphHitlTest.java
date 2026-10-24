package com.queryforge.application.agent.runtime;

import com.queryforge.application.agent.runtime.AgentGraphRunner.AgentGraphPause;
import com.queryforge.domain.agent.AgentRunResponse;
import com.queryforge.domain.agent.PlanReviewStatus;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P14-ARCH-01 R3：真实 Spring AI Alibaba Graph 的原生 HITL（interruptBefore / 同 threadId 恢复 / 重规划）。
 *
 * <p>强门禁约束：
 * <ul>
 *   <li>图由真实 {@code StateGraph}/{@code CompiledGraph} 编译，checkpoint 落在真实 Redis（RedisSaver）；</li>
 *   <li>暂停/恢复以真实 {@code StateSnapshot.next()} 为准，不用 MySQL 业务状态冒充图状态；</li>
 *   <li>审核信封（{@link PlanReviewSessionStore}）使用真实 Redis（TTL + RLock 一次性领取）；</li>
 *   <li>只有 Fake LLM 与 JDBC/Report 替身是受控替身，Graph 本身与 HITL 不允许 Mock。</li>
 * </ul>
 */
@DisplayName("P14-ARCH-01 R3 原生 Graph HITL（G05-G09、G14）")
class AgentGraphHitlTest {

    private AgentGraphHitlFixture fixture;

    private void useGate(String gateResponse) throws Exception {
        fixture = AgentGraphHitlFixture.create(gateResponse);
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    // ------------------------------------------------------------------ G06

    @Test
    @DisplayName("G06：autoConfirm=false 且 Gate 判定有风险 → 真实图中断于 HITL，RedisSaver 有 checkpoint，Worker 零执行")
    void riskPlan_shouldPauseOnRealHitlNodeBeforeAnyWorker() throws Exception {
        useGate(AgentGraphHitlFixture.GATE_NEEDS_REVIEW);
        String started = fixture.pauseAtGate();

        AgentGraphPause pause = fixture.pause();
        assertThat(pause.nextNode()).isEqualTo(AgentGraphNodes.NODE_HITL);
        assertThat(pause.pausedAtHitl()).isTrue();
        assertThat(pause.hitlVersion()).isEqualTo(1);
        assertThat(pause.checkpointId()).isNotBlank();
        assertThat(started).contains("event: plan.review_required");
        assertThat(started).contains("\"node\":\"PLANNING\"");
        assertThat(started).contains("\"node\":\"HITL_GATE\"");
        assertThat(started).doesNotContain("\"node\":\"DATA_SCIENTIST\"");
        assertThat(started).doesNotContain("\"node\":\"DASHBOARD\"");

        // 真实快照：计划已进入等待审核，等待期间不派发任何 Worker。
        var snapshot = fixture.stack.runner.snapshotOf(fixture.runId).orElseThrow();
        assertThat(snapshot.state().value(AgentGraphStateKeys.HITL_PENDING, Boolean.class)).contains(true);
        assertThat(snapshot.state().value(AgentGraphStateKeys.ROUTE, String.class)).contains("COMPLEX");

        verify(fixture.stack.executionService, never()).execute(anyLong(), any(), anyLong(), anyLong());
        verify(fixture.stack.repository)
                .enterPlanReviewAwaiting(eq(fixture.runId), eq(AgentGraphHitlFixture.USER_ID), any());
        verify(fixture.stack.repository, never())
                .updateRunStatus(eq(fixture.runId), eq(RunStatus.RUNNING), any(), any(), any());
        assertThat(fixture.stack.llm.calls("[PLAN-REVIEW]")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ G07

    @Test
    @DisplayName("G07：正确版本且 owner 批准 → 同 threadId 恢复，计划不重生成，业务动作只执行一次")
    void approve_shouldResumeSameThreadAndExecuteOnce() throws Exception {
        useGate(AgentGraphHitlFixture.GATE_NEEDS_REVIEW);
        fixture.pauseAtGate();
        String threadId = fixture.stack.runner.snapshotOf(fixture.runId).orElseThrow()
                .config().threadId().orElseThrow();

        String resumed = fixture.decide(AgentGraphHitlFixture.USER_ID, true, null, 1);

        assertThat(resumed).contains("event: plan.review_decided");
        assertThat(resumed).contains("event: step.succeeded");
        assertThat(resumed).contains("event: run.completed");
        assertThat(resumed).contains("\"node\":\"HITL\"");
        assertThat(resumed).contains("\"node\":\"DATA_SCIENTIST\"");

        // 业务只执行一次（无重放、无重复 JDBC）。
        verify(fixture.stack.executionService, times(1))
                .execute(eq(AgentGraphHitlFixture.USER_ID), any(), eq(fixture.runId), anyLong());
        // 计划未被重新生成（批准不做 Replan）。
        assertThat(fixture.stack.llm.calls("[PLANNER]")).isEqualTo(1);
        verify(fixture.stack.repository)
                .updateRunStatus(eq(fixture.runId), eq(RunStatus.COMPLETED), any(), eq(null), eq(null));

        // 同一 threadId：checkpoint 由同一条图恢复，详情可查。
        AgentRunResponse detail = fixture.stack.service.detail(AgentGraphHitlFixture.USER_ID, fixture.runId);
        assertThat(detail.threadId()).isEqualTo(threadId);
        assertThat(fixture.stack.runner.hasCheckpoint(fixture.runId)).isTrue();
        var terminal = fixture.stack.runner.snapshotOf(fixture.runId).orElseThrow();
        assertThat(terminal.state().value(AgentGraphStateKeys.TERMINAL_STATUS, String.class))
                .contains(RunStatus.COMPLETED.name());
    }

    // ------------------------------------------------------------------ G08

    @Test
    @DisplayName("G08：拒绝并带反馈 → Graph 回 Manager→Planner 重规划，新计划再次过 Gate 并暂停到新版本")
    void rejectWithFeedback_shouldReplanAndReenterGate() throws Exception {
        useGate(AgentGraphHitlFixture.GATE_NEEDS_REVIEW);
        fixture.pauseAtGate();

        String resumed = fixture.decide(AgentGraphHitlFixture.USER_ID, false, "请按整月口径统计", 1);

        assertThat(resumed).contains("event: plan.review_decided");
        // 旧计划待执行步骤失效，重新规划后再次暂停（版本 2）。
        verify(fixture.stack.repository).deletePendingSteps(fixture.runId);
        assertThat(fixture.stack.llm.calls("[PLANNER]")).isEqualTo(2);
        List<String> plannerPrompts = fixture.stack.llm.userPrompts("[PLANNER]");
        assertThat(plannerPrompts.get(1)).contains("请按整月口径统计");

        AgentGraphPause pause = fixture.pause();
        assertThat(pause.nextNode()).isEqualTo(AgentGraphNodes.NODE_HITL);
        assertThat(pause.hitlVersion()).isEqualTo(2);
        verify(fixture.stack.executionService, never()).execute(anyLong(), any(), anyLong(), anyLong());
        assertThat(resumed).contains("event: plan.review_required");
    }

    // ------------------------------------------------------------------ G09

    @Test
    @DisplayName("G09：第三次拒绝 → Run 终态结束，无新 Worker 调用，无新的可领取审核")
    void thirdRejection_shouldTerminateRunWithoutWorkerExecution() throws Exception {
        useGate(AgentGraphHitlFixture.GATE_NEEDS_REVIEW);
        fixture.pauseAtGate();

        // 第一次/第二次拒绝：重规划后再次暂停。
        fixture.decide(AgentGraphHitlFixture.USER_ID, false, "口径一", 1);
        fixture.decide(AgentGraphHitlFixture.USER_ID, false, "口径二", 2);
        // 第三次拒绝：终结，不再重规划。
        String resumed = fixture.decide(AgentGraphHitlFixture.USER_ID, false, "仍不对", 3);

        assertThat(resumed).contains("event: plan.review_failed");
        assertThat(resumed).contains("PLAN_REJECTED_MAX");
        verify(fixture.stack.repository).failPlanReview(eq(fixture.runId), eq("PLAN_REJECTED_MAX"), anyString());
        verify(fixture.stack.executionService, never()).execute(anyLong(), any(), anyLong(), anyLong());

        var terminal = fixture.stack.runner.snapshotOf(fixture.runId).orElseThrow();
        assertThat(terminal.state().value(AgentGraphStateKeys.TERMINAL_STATUS, String.class))
                .contains(RunStatus.FAILED.name());
        // 无新的可领取审核：Redis 信封已移除，再次领取消费为 EXPIRED。
        assertThat(fixture.stack.planReviewSessionStore.claim(fixture.runId, AgentGraphHitlFixture.USER_ID, 3))
                .isEqualTo(PlanReviewSessionStore.Claim.EXPIRED);
        // 三次拒绝里只重规划两次（第三次直接终结）：初始规划 + 2 次带反馈的重规划 = 3 次 Planner 调用。
        assertThat(fixture.stack.llm.calls("[PLANNER]")).isEqualTo(3);
    }

    // ------------------------------------------------------------------ G05

    @Test
    @DisplayName("G05：autoConfirm=false 但 Gate 判定无需审核 → Gate 真实评估后同一条图继续执行到终态")
    void gateAutoPass_shouldContinueSameGraphWithoutPause() throws Exception {
        useGate(AgentGraphHitlFixture.GATE_AUTO_PASS);
        String started = fixture.pauseAtGate();

        assertThat(started).doesNotContain("event: plan.review_required");
        verify(fixture.stack.repository)
                .markPlanReviewStatus(fixture.runId, AgentGraphHitlFixture.USER_ID, PlanReviewStatus.NOT_REQUIRED);
        verify(fixture.stack.executionService, times(1))
                .execute(eq(AgentGraphHitlFixture.USER_ID), any(), eq(fixture.runId), anyLong());
        assertThat(started).contains("event: run.completed");

        AgentGraphPause pause = fixture.pause();
        assertThat(pause.pausedAtHitl()).isFalse();
        assertThat(pause.nextNode()).isEqualTo(com.alibaba.cloud.ai.graph.StateGraph.END);
    }

    // ------------------------------------------------------------------ G14

    @Test
    @DisplayName("G14：SSE 断线后刷新 → 待审核不被误取消，Run 详情返回真实待审版本与计划步骤，并可继续恢复")
    void disconnectThenRefresh_shouldKeepAwaitingAndRestoreFromFile() throws Exception {
        useGate(AgentGraphHitlFixture.GATE_NEEDS_REVIEW);
        fixture.pauseAtGate();

        // 浏览器断线：断层没有入口，取消/失败都不会被触发。
        verify(fixture.stack.repository, never())
                .updateRunStatus(eq(fixture.runId), eq(RunStatus.CANCELED), any(), any(), any());
        verify(fixture.stack.repository, never())
                .updateRunStatus(eq(fixture.runId), eq(RunStatus.FAILED), any(), any(), any());

        AgentRunResponse detail =
                fixture.stack.service.detail(AgentGraphHitlFixture.USER_ID, fixture.runId);
        assertThat(detail.planReview()).isNotNull();
        assertThat(detail.planReview().required()).isTrue();
        assertThat(detail.planReview().hitlVersion()).isEqualTo(1);
        assertThat(detail.planReview().status()).isEqualTo(PlanReviewStatus.AWAITING.name());
        assertThat(detail.planReview().autoConfirm()).isFalse();
        assertThat(detail.planReview().plan()).isNotEmpty();
        assertThat(detail.threadId()).isNotBlank();
        // 展示审计：MySQL 中的展示 checkpoint 记录了等待节点（刷新即可恢复界面）。
        assertThat(detail.graphState()).contains("WAITING_PLAN_REVIEW");

        // 刷新后按详情中的真实版本继续：同 threadId 恢复并只执行一次。
        fixture.decide(AgentGraphHitlFixture.USER_ID, true, null, 1);
        verify(fixture.stack.executionService, times(1))
                .execute(eq(AgentGraphHitlFixture.USER_ID), any(), eq(fixture.runId), anyLong());
    }
}
