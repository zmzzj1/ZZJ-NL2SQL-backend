package com.queryforge.application.agent.runtime;

import com.queryforge.application.agent.PreparedPlanDecision;
import com.queryforge.application.agent.PreparedRun;
import com.queryforge.application.agent.runtime.AgentGraphRunner.AgentGraphPause;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.PlanDecisionRequest;
import com.queryforge.domain.agent.PlanReviewStatus;
import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.infrastructure.web.SseEventWriter;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * P14-ARCH-01 R3：真实 Graph HITL 场景的可复用测试夹具。
 *
 * <p>真实部分：StateGraph/CompiledGraph + RedisSaver + 本机 Redis + 真实 PlanReviewSessionStore
 * （RLock 一次性领取、TTL）。受控替身：Fake LLM、MySQL Repository（Mockito）、JDBC/Report。
 *
 * <p>夹具把"计划生成 → Gate 判定 → 中断于 HITL → 版本 CAS → 恢复"的完整业务事实桩在第 1 次启动里跑齐，
 * 测试只需要表达场景差异（并发、跨用户、重启、checkpoint 不可用等）。
 */
final class AgentGraphHitlFixture implements AutoCloseable {

    static final long USER_ID = 1L;
    static final long CONNECTION_ID = 10L;
    static final long CONVERSATION_ID = 20L;
    static final String QUESTION = "统计各月订单金额";
    static final String SQL =
            "SELECT DATE_FORMAT(created_at, '%Y-%m') AS month, SUM(amount) AS total FROM shop_orders GROUP BY 1";
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);
    static final String PLAN_JSON = """
            {"answerable": true, "reason": "", "steps": [
                {"objective": "统计各月订单金额", "agent": "DATA_ANALYST", "dependsOn": null}
            ]}""";
    /** COMPLEX/MEDIUM 走多候选路径：候选生成使用 {@code [SQL-CANDIDATE]}，评分使用 {@code [SQL-SCORER]}。 */
    static final String SCORER_JSON =
            "[{\"candidate\":1,\"score\":9,\"reason\":\"口径清晰\"},"
                    + "{\"candidate\":2,\"score\":7,\"reason\":\"可读性一般\"},"
                    + "{\"candidate\":3,\"score\":6,\"reason\":\"冗余列\"}]";

    static final String GATE_NEEDS_REVIEW = "{\"needs_review\": true, \"reason\": \"多步且含汇总口径，需确认\"}";
    static final String GATE_AUTO_PASS = "{\"needs_review\": false, \"reason\": \"单步只读聚合\"}";

    private static final AtomicLong RUN_SEQUENCE = new AtomicLong(9300);

    final long runId;
    final AgentGraphStack stack;
    final AtomicReference<AgentRun> currentRun = new AtomicReference<>();
    final AtomicInteger reviewVersion = new AtomicInteger(0);
    final AtomicInteger rejectionCount = new AtomicInteger(0);
    final Map<Long, AgentStep> steps = new LinkedHashMap<>();

    private final AtomicLong stepIdSequence = new AtomicLong(7000);

    private AgentGraphHitlFixture(String gateResponse) throws Exception {
        this.runId = RUN_SEQUENCE.incrementAndGet();
        this.stack = AgentGraphStack.create(AgentGraphStack.props(3, 2, 12, 2,
                com.queryforge.domain.agent.AgentProperties.DISPATCH_OFF));
        stubContext();
        stubReview();
        stubSteps();
        stack.llm.on("[MANAGER]", "{\"route\":\"COMPLEX\",\"reasonCode\":\"多步聚合，需要规划\"}")
                .on("[PLANNER]", PLAN_JSON)
                .on("[PLAN-REVIEW]", gateResponse)
                .on("[SQL]", SQL)
                .on("[SQL-CANDIDATE]", SQL)
                .on("[SQL-SCORER]", SCORER_JSON)
                .on("[SUMMARY]", "各月订单金额统计完成。")
                .fallback("{}");
    }

    static AgentGraphHitlFixture create(String gateResponse) throws Exception {
        return new AgentGraphHitlFixture(gateResponse);
    }

    /** 启动并让图真实中断于 HITL；随后 Run 业务状态同步为 WAITING_PLAN_REVIEW(当前版本)。 */
    String pauseAtGate() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String threadId = stack.runner.start(prepared(), stack.capturingWriter(output));
        assertThat(threadId).isNotBlank();
        awaiting();
        return output.toString(StandardCharsets.UTF_8);
    }

    /** 把 Run 切换为"等待计划审核"业务状态（详情视图与领取校验都以它为准）。 */
    void awaiting() {
        currentRun.set(run(RunStatus.WAITING_PLAN_REVIEW, null, reviewVersion.get(),
                PlanReviewStatus.AWAITING, false));
    }

    /** 审核决定：先一次性领取，再在同一 threadId 上恢复真实 Graph。 */
    String decide(long userId, boolean approved, String feedback, int version) {
        PreparedPlanDecision decision = prepare(userId, approved, feedback, version);
        return resume(decision);
    }

    PreparedPlanDecision prepare(long userId, boolean approved, String feedback, int version) {
        return stack.service.preparePlanDecision(userId, runId,
                new PlanDecisionRequest(approved, feedback, version));
    }

    String resume(PreparedPlanDecision decision) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        stack.service.continuePlanReview(decision, out);
        awaiting();
        return out.toString(StandardCharsets.UTF_8);
    }

    AgentGraphPause pause() {
        return stack.runner.pauseOf(runId).orElseThrow();
    }

    PreparedRun prepared() {
        return new PreparedRun(runId, USER_ID, CONVERSATION_ID, CONNECTION_ID, "test_db", QUESTION,
                AgentGraphStack.defaultConfig(), null, null, Boolean.FALSE);
    }

    AgentRun run(RunStatus status, String summary, int version, PlanReviewStatus reviewStatus,
                 boolean autoConfirm) {
        return new AgentRun(runId, USER_ID, CONVERSATION_ID, CONNECTION_ID, "test_db", QUESTION, status,
                null, null, null, 0, summary, null, null, NOW, NOW, null, null, null, null,
                autoConfirm, version, reviewStatus == null ? null : reviewStatus.name(), true, null, 0, null);
    }

    private void stubContext() {
        stack.stubCommonContext(USER_ID, CONNECTION_ID, CONVERSATION_ID,
                "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL, created_at DATETIME)");
        stack.stubGraphCheckpointStore();
        stack.stubGraphThreadBinding();
        when(stack.configResolver.resolveForRun(any(), any(), any()))
                .thenReturn(ResolvedAgentConfig.defaults());
        when(stack.guard.validate(anyString(), anyString())).thenReturn(SQL);
        when(stack.executionService.execute(eq(USER_ID), any(), eq(runId), anyLong())).thenReturn(result());

        currentRun.set(run(RunStatus.PLANNING, null, 0, PlanReviewStatus.NOT_REQUIRED, false));
        when(stack.repository.findRunOwned(eq(runId), anyLong()))
                .thenAnswer(invocation ->
                        Long.valueOf(USER_ID).equals(invocation.getArgument(1))
                                ? Optional.of(currentRun.get()) : Optional.empty());
        when(stack.repository.findRun(eq(runId))).thenAnswer(invocation -> Optional.of(currentRun.get()));
        Mockito.lenient().when(stack.repository.updateRunStatus(eq(runId), any(), any(), any(), any()))
                .thenReturn(1);
        Mockito.lenient().when(stack.repository.transitionRunStatus(eq(runId), any())).thenReturn(1);
        Mockito.lenient().when(stack.repository.updateRunRoute(eq(runId), anyString(), any(), any()))
                .thenReturn(1);
        Mockito.lenient().when(stack.repository.tryIncrementLlmCalls(runId, 12)).thenReturn(1);
    }

    private void stubReview() {
        when(stack.repository.enterPlanReviewAwaiting(eq(runId), eq(USER_ID), nullable(String.class)))
                .thenAnswer(invocation -> reviewVersion.incrementAndGet());
        when(stack.repository.claimPlanApproval(eq(runId), eq(USER_ID), anyInt())).thenReturn(1);
        when(stack.repository.claimPlanRejection(eq(runId), eq(USER_ID), anyInt(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    rejectionCount.incrementAndGet();
                    return 1;
                });
        when(stack.repository.currentRejectionCount(runId)).thenAnswer(invocation -> rejectionCount.get());
        Mockito.lenient().when(stack.repository.markPlanReviewStatus(eq(runId), eq(USER_ID), any()))
                .thenReturn(1);
        Mockito.lenient().when(stack.repository.failPlanReview(eq(runId), anyString(), anyString()))
                .thenReturn(1);
    }

    private void stubSteps() {
        when(stack.repository.insertStep(eq(runId), anyInt(), anyString(), nullable(Integer.class),
                nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    long stepId = stepIdSequence.incrementAndGet();
                    steps.put(stepId, step(stepId, invocation.getArgument(1, Integer.class),
                            invocation.getArgument(2, String.class), invocation.getArgument(4, String.class),
                            StepStatus.PENDING));
                    return stepId;
                });
        when(stack.repository.deletePendingSteps(runId)).thenAnswer(invocation -> {
            steps.clear();
            return 1;
        });
        when(stack.repository.findSteps(runId)).thenAnswer(invocation -> new ArrayList<>(steps.values()));
        when(stack.repository.findStep(eq(runId), anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(steps.get(invocation.getArgument(1, Long.class))));
        when(stack.repository.claimNonSqlStep(eq(runId), anyLong())).thenReturn(1);
        when(stack.repository.setStepSql(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(1);
        when(stack.repository.completeStep(anyLong(), anyInt(), anyBoolean(), anyString()))
                .thenAnswer(invocation -> {
                    Long stepId = invocation.getArgument(0, Long.class);
                    AgentStep existing = steps.get(stepId);
                    if (existing != null) {
                        steps.put(stepId, step(stepId, existing.sequence(), existing.objective(),
                                existing.agentRole(), StepStatus.SUCCEEDED));
                    }
                    return 1;
                });
    }

    private AgentStep step(long stepId, int sequence, String objective, String role, StepStatus status) {
        return new AgentStep(stepId, runId, sequence, objective, null, status, null, 0, role,
                com.queryforge.domain.agent.ApprovalStatus.PENDING, 0, 0, false, null, null,
                "已执行并返回结果摘要", NOW, NOW, null, null);
    }

    private QueryResultResponse result() {
        return new QueryResultResponse(List.of("month", "total"),
                List.of(Map.of("month", "2026-01", "total", 128)), 1, false);
    }

    @Override
    public void close() {
        stack.close();
    }
}
