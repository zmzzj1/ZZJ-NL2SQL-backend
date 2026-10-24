package com.queryforge.application.agent.runtime;

import com.queryforge.application.agent.PreparedRun;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.infrastructure.web.SseEventWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P14-ARCH-01 R1 门禁 + G01/G04：真实 Spring AI Alibaba CompiledGraph 冒烟。
 *
 * <p>强门禁：真实 StateGraph/CompiledGraph + 真实 RedisSaver/Redis + 真实 interruptBefore；
 * 不使用任何 Mock/自定义假状态转换类冒充图执行。
 */
class AgentGraphRuntimeSmokeTest {

    private static final long USER_ID = 1L;
    private static final long CONNECTION_ID = 10L;
    private static final long CONVERSATION_ID = 20L;
    private static final long RUN_ID = 9001L;
    private static final long STEP_ID = 5001L;
    private static final String QUESTION = "统计订单总数";
    private static final String SQL = "SELECT COUNT(*) AS total FROM shop_orders";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

    private AgentGraphStack stack;

    @BeforeEach
    void setUp() throws Exception {
        stack = AgentGraphStack.create(AgentGraphStack.props(3, 2, 12, 2, "OFF"));
        stack.stubCommonContext(USER_ID, CONNECTION_ID, CONVERSATION_ID,
                "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL)");
        stubRun();
        stack.stubGraphCheckpointStore();
        stack.stubGraphThreadBinding();
        // 受控 Fake LLM：Manager 路由 SIMPLE → SQL 单路径生成 → 总结。
        stack.llm.on("[MANAGER]", "{\"route\":\"SIMPLE\",\"reasonCode\":\"单条 SQL 即可\"}")
                .on("[SQL]", SQL)
                .on("[SUMMARY]", "共返回 1 行汇总结果。")
                .fallback("{}");
    }

    @AfterEach
    void tearDown() {
        stack.close();
    }

    private void stubRun() {
        AgentRun run = new AgentRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "test_db", QUESTION,
                RunStatus.PLANNING, null, null, null, 0, null, null, null, NOW, NOW,
                null, null, null, null, Boolean.TRUE, 0, null, false, null, 0, null);
        stack.stubRun(RUN_ID, USER_ID, run);
        when(stack.repository.bindGraphThread(eq(RUN_ID), anyString())).thenReturn(1);
        lenient().when(stack.repository.insertStep(eq(RUN_ID), anyInt(), anyString(), any(), anyString(),
                any(), any())).thenReturn(STEP_ID);
        lenient().when(stack.repository.insertStep(eq(RUN_ID), anyInt(), anyString(), any()))
                .thenReturn(STEP_ID);
        lenient().when(stack.repository.claimNonSqlStep(RUN_ID, STEP_ID)).thenReturn(1);
        lenient().when(stack.repository.setStepSql(STEP_ID, SQL, 1, 0)).thenReturn(1);
        lenient().when(stack.repository.completeStep(eq(STEP_ID), anyInt(), eq(false), anyString()))
                .thenReturn(1);
        lenient().when(stack.repository.updateRunStatus(eq(RUN_ID), any(), any(), any(), any()))
                .thenReturn(1);
        lenient().when(stack.repository.tryIncrementLlmCalls(RUN_ID, 12)).thenReturn(1);
        lenient().when(stack.repository.findSteps(RUN_ID)).thenReturn(List.of(step(StepStatus.SUCCEEDED)));
        lenient().when(stack.repository.findStep(RUN_ID, STEP_ID))
                .thenReturn(Optional.of(step(StepStatus.SUCCEEDED)));
    }

    private AgentStep step(StepStatus status) {
        return new AgentStep(STEP_ID, RUN_ID, 1, QUESTION, null, status, SQL, 1, "DATA_ANALYST",
                com.queryforge.domain.agent.ApprovalStatus.PENDING, 0, 1, false, null, null,
                "返回 1 行", NOW, NOW, null, null);
    }

    private QueryResultResponse result() {
        return new QueryResultResponse(
                List.of("total"),
                List.of(Map.of("total", 128)),
                1,
                false);
    }

    @Test
    @DisplayName("R1/G01：真实 Graph START→MANAGER→DATA_SCIENTIST→END，Redis 保存真实 checkpoint，SQL Worker 一次执行")
    void simpleSqlFlow_shouldRunOnRealGraphWithRedisCheckpoint() {
        when(stack.guard.validate(anyString(), anyString())).thenReturn(SQL);
        when(stack.executionService.execute(eq(USER_ID), any(), eq(RUN_ID), eq(STEP_ID)))
                .thenReturn(result());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SseEventWriter writer = stack.capturingWriter(output);
        PreparedRun prepared = new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID,
                "test_db", QUESTION, AgentGraphStack.defaultConfig(), null, null, Boolean.TRUE);

        String threadId = stack.runner.start(prepared, writer);
        String sse = output.toString(StandardCharsets.UTF_8);

        // 真实图节点进入/完成（来自 GraphLifecycleListener，不是 UI 猜测）。
        assertThat(sse).contains("event: node.started");
        assertThat(sse).contains("\"node\":\"MANAGER\"");
        assertThat(sse).contains("\"node\":\"DATA_SCIENTIST\"");
        // 真实路由与执行事件。
        assertThat(sse).contains("event: agent.routed");
        assertThat(sse).contains("SIMPLE");
        assertThat(sse).contains("event: step.succeeded");
        assertThat(sse).contains("event: run.completed");
        assertThat(sse).contains("threadId");
        // SQL 只执行一次（真实 JDBC 替身调用计数）。
        verify(stack.executionService, org.mockito.Mockito.times(1))
                .execute(eq(USER_ID), any(), eq(RUN_ID), eq(STEP_ID));
        // Run/Step 一致落库。
        verify(stack.repository).updateRunStatus(eq(RUN_ID), eq(RunStatus.COMPLETED),
                org.mockito.ArgumentMatchers.contains("共返回 1 行"), eq(null), eq(null));
        verify(stack.repository).completeStep(eq(STEP_ID), eq(1), eq(false),
                org.mockito.ArgumentMatchers.contains("返回 1 行"));

        // RedisSaver 真实 checkpoint：同 threadId 可读取 StateSnapshot，终态为 END。
        Optional<AgentGraphRunner.AgentGraphPause> pause = stack.runner.pauseOf(RUN_ID);
        assertThat(pause).isPresent();
        assertThat(pause.get().checkpointId()).isNotBlank();
        assertThat(pause.get().nextNode()).isEqualTo(com.alibaba.cloud.ai.graph.StateGraph.END);
        assertThat(threadId).isNotBlank();
        // 真实 checkpoint 状态包含路由事实。
        var snapshot = stack.runner.snapshotOf(RUN_ID);
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().state().value("route", String.class)).contains("SIMPLE");
        assertThat(snapshot.get().state().value("terminalStatus", String.class)).contains("COMPLETED");
    }

    @Test
    @DisplayName("R1/G04：SIMPLE 路由不经过 Planner/Gate，Gate 模型零调用")
    void simpleFlow_shouldSkipPlannerAndGate() {
        when(stack.guard.validate(anyString(), anyString())).thenReturn(SQL);
        when(stack.executionService.execute(eq(USER_ID), any(), eq(RUN_ID), eq(STEP_ID)))
                .thenReturn(result());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PreparedRun prepared = new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID,
                "test_db", QUESTION, AgentGraphStack.defaultConfig(), null, null, Boolean.TRUE);
        stack.runner.start(prepared, stack.capturingWriter(output));

        // 计划级 Gate 的模型调用次数为 0（SIMPLE 单路径；autoConfirm 亦跳过 Gate）。
        assertThat(stack.llm.calls("计划风险审核")).isZero();
        verify(stack.repository, never()).enterPlanReviewAwaiting(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("R1：Guard 拒绝 SQL 时 JDBC 零调用，运行失败收敛")
    void guardRejection_shouldFailWithoutJdbcExecution() {
        when(stack.guard.validate(anyString(), anyString()))
                .thenThrow(new com.queryforge.shared.error.ApiException(
                        com.queryforge.shared.error.ErrorCode.SQL_NOT_ALLOWED,
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "只允许 SELECT"));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PreparedRun prepared = new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID,
                "test_db", QUESTION, AgentGraphStack.defaultConfig(), null, null, Boolean.TRUE);
        stack.runner.start(prepared, stack.capturingWriter(output));
        String sse = output.toString(StandardCharsets.UTF_8);

        assertThat(sse).contains("event: run.failed");
        assertThat(sse).contains("SQL_NOT_ALLOWED");
        verify(stack.executionService, never()).execute(any(), any(), anyLong(), anyLong());
        verify(stack.repository).updateRunStatus(eq(RUN_ID), eq(RunStatus.FAILED), eq(null),
                eq("SQL_NOT_ALLOWED"), any());
    }
}
