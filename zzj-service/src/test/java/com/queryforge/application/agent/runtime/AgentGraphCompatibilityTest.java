package com.queryforge.application.agent.runtime;

import com.queryforge.application.agent.PreparedApproval;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentRunResponse;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.ApproveAgentStepRequest;
import com.queryforge.domain.agent.PlanDecisionRequest;
import com.queryforge.domain.agent.PlanReviewStatus;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P14-ARCH-01 R6 兼容性（G22-G24）：旧 Run 详情可读、Cancel/失败/断线三态互不混淆、
 * 旧 SQL 审批兼容接口仍按原语义安全返回且与六 Agent 主链不叠加双重人工审核。
 *
 * <p>Graph 仍为真实 {@code CompiledGraph}（只是这条 Run 没有 checkpoint），
 * 不用 MySQL 业务状态伪造可恢复性。
 */
@DisplayName("P14-ARCH-01 R6 兼容与三态（G22-G24）")
class AgentGraphCompatibilityTest {

    private static final long USER_ID = 1L;
    private static final long CONNECTION_ID = 10L;
    private static final long CONVERSATION_ID = 20L;
    private static final long RUN_ID = 9501L;
    private static final long STEP_ID = 7501L;
    private static final String SQL = "SELECT COUNT(*) AS total FROM shop_orders";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

    private AgentGraphStack stack;

    @AfterEach
    void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    // ------------------------------------------------------------------ G22

    @Test
    @DisplayName("G22：旧已完成 Run 的历史详情仍可读；不伪造 checkpoint，也不可被继续恢复")
    void legacyCompletedRun_shouldRemainReadableWithoutFakeCheckpoint() throws Exception {
        stack = newStack();
        AgentRun completed = run(RunStatus.COMPLETED, "历史结论：共 128 条", 0, null);
        stack.stubRun(RUN_ID, USER_ID, completed);
        when(stack.repository.findSteps(RUN_ID)).thenReturn(List.of(step(StepStatus.SUCCEEDED)));

        AgentRunResponse detail = stack.service.detail(USER_ID, RUN_ID);

        assertThat(detail.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(detail.summary()).contains("历史结论");
        assertThat(detail.steps()).hasSize(1);
        assertThat(detail.steps().get(0).sql()).isEqualTo(SQL);
        assertThat(detail.steps().get(0).reportId()).isEqualTo(77L);
        // 旧 Run 没有 Graph checkpoint：真实图查询为空，绝不为历史 Run 编造可恢复状态。
        assertThat(stack.runner.hasCheckpoint(RUN_ID)).isFalse();
        assertThat(stack.runner.pauseOf(RUN_ID)).isEmpty();
        // 因而提交计划级审核决定会被明确拒绝，而不是静默"续跑"历史运行。
        assertThatThrownBy(() -> stack.service.preparePlanDecision(USER_ID, RUN_ID,
                new PlanDecisionRequest(true, null, 1)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_STATE_CONFLICT);
    }

    // ------------------------------------------------------------------ G23

    @Test
    @DisplayName("G23：Cancel 是独立业务事件——不写失败终态，也不产生可恢复的审核态")
    void cancel_shouldBeDistinctFromFailureAndPause() throws Exception {
        stack = newStack();
        AgentRun waiting = run(RunStatus.WAITING_PLAN_REVIEW, null, 1, PlanReviewStatus.AWAITING);
        stack.stubRun(RUN_ID, USER_ID, waiting);
        when(stack.repository.cancelActiveRun(RUN_ID, USER_ID)).thenReturn(1);

        stack.service.cancel(USER_ID, RUN_ID);

        verify(stack.repository).cancelActiveRun(RUN_ID, USER_ID);
        verify(stack.repository).cancelPendingSteps(RUN_ID);
        verify(stack.repository, never()).failPlanReview(eq(RUN_ID), Mockito.anyString(), Mockito.anyString());
        verify(stack.repository, never())
                .updateRunStatus(eq(RUN_ID), eq(RunStatus.FAILED), Mockito.any(), Mockito.any(), Mockito.any());
        verify(stack.repository, never())
                .updateRunStatus(eq(RUN_ID), eq(RunStatus.COMPLETED), Mockito.any(), Mockito.any(), Mockito.any());
    }

    // ------------------------------------------------------------------ G24

    @Test
    @DisplayName("G24：旧 SQL 审批兼容接口仍按原语义返回，不叠加计划级人工审核")
    void legacyStepApproval_shouldStillWorkWithoutPlanReview() throws Exception {
        stack = newStack();
        AgentRun running = run(RunStatus.RUNNING, null, 0, null);
        stack.stubRun(RUN_ID, USER_ID, running);
        when(stack.repository.findStep(RUN_ID, STEP_ID))
                .thenReturn(Optional.of(step(StepStatus.WAITING_APPROVAL)));
        when(stack.repository.claimStep(RUN_ID, STEP_ID, 1)).thenReturn(1);
        // 旧审批令牌：真实 Redis 一次性消费。
        stack.approvalSessionStore.register(RUN_ID, STEP_ID, 1, "test_db");
        when(stack.connectionService.summary(USER_ID, CONNECTION_ID))
                .thenReturn(new com.queryforge.application.datasource.ConnectionService.ConnectionSummary(
                        CONNECTION_ID, "test-db", "test_db"));

        PreparedApproval approval = stack.service.prepareApproval(USER_ID, RUN_ID, STEP_ID,
                new ApproveAgentStepRequest(1, true, "web-1"));

        assertThat(approval.replay()).isFalse();
        assertThat(approval.step().sqlVersion()).isEqualTo(1);
        // 兼容路径不触发计划级审核：没有 enterPlanReviewAwaiting，也没有重复人工审核。
        verify(stack.repository, never())
                .enterPlanReviewAwaiting(eq(RUN_ID), anyLong(), Mockito.nullable(String.class));
    }

    // ------------------------------------------------------------------ 装配

    private AgentGraphStack newStack() throws Exception {
        stack = AgentGraphStack.create(AgentGraphStack.props(3, 2, 12, 2,
                com.queryforge.domain.agent.AgentProperties.DISPATCH_OFF));
        stack.stubGraphCheckpointStore();
        stack.stubGraphThreadBinding();
        return stack;
    }

    private AgentRun run(RunStatus status, String summary, int version, PlanReviewStatus reviewStatus) {
        return new AgentRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "test_db", "历史问题", status,
                "SIMPLE", null, null, 4, summary, null, null, NOW, NOW, null, null, null, null,
                Boolean.TRUE, version, reviewStatus == null ? null : reviewStatus.name(), false, null, 0, null);
    }

    private AgentStep step(StepStatus status) {
        return new AgentStep(STEP_ID, RUN_ID, 1, "统计订单总数", null, status, SQL, 1, "SQL_AGENT",
                com.queryforge.domain.agent.ApprovalStatus.PENDING, 0, 1, false, null, null,
                "返回 1 行", NOW, NOW, null, 77L);
    }
}
