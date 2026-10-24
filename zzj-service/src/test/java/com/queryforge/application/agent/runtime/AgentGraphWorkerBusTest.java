package com.queryforge.application.agent.runtime;

import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.application.agent.PreparedRun;
import com.queryforge.application.agent.runtime.bus.AgentMessageBus;
import com.queryforge.application.agent.runtime.bus.BusMessage;
import com.queryforge.application.agent.runtime.bus.WorkerDispatcher;
import com.queryforge.application.agent.runtime.bus.WorkerEndpoint;
import com.queryforge.application.agent.runtime.worker.WorkerAction;
import com.queryforge.application.agent.runtime.worker.WorkerDiagnosticSink;
import com.queryforge.application.agent.runtime.worker.WorkerExecutor;
import com.queryforge.application.agent.runtime.worker.WorkerTask;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.SqlExecutionException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.infrastructure.web.SseEventWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P14-ARCH-01 R4/R5：Worker 统一循环（Thinking/Review/Act/Verify）与 In-JVM 总线三模式。
 *
 * <p>R4 用真实 {@link WorkerExecutor} + 受控 Action 替身校验有界重试与终态失败；
 * R5 用真实 Graph + 真实 {@link WorkerDispatcher}/{@link AgentMessageBus}/{@link WorkerEndpoint}
 * 校验 OFF/BYPASS/SWITCH 语义一致且业务只执行一次。
 */
@DisplayName("P14-ARCH-01 R4/R5 Worker 循环与总线（G15-G19、G21）")
class AgentGraphWorkerBusTest {

    private static final long USER_ID = 1L;
    private static final long CONNECTION_ID = 10L;
    private static final long CONVERSATION_ID = 20L;
    private static final long RUN_ID = 9401L;
    private static final long STEP_ID = 7401L;
    private static final String QUESTION = "统计订单总数";
    private static final String SQL = "SELECT COUNT(*) AS total FROM shop_orders";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

    // ------------------------------------------------------------------ G15

    @Test
    @DisplayName("G15：可恢复失败 → 新一轮 Thinking 带真实诊断，重试受上限约束")
    void retryableFailure_shouldRetryWithRealDiagnosticsUntilBound() {
        RecordingAction action = new RecordingAction(2);
        WorkerExecutor executor = new WorkerExecutor(new AgentProperties(3, 2, 6000, 12, 3,
                AgentProperties.DISPATCH_OFF));

        WorkerOutcome outcome = executor.execute(action, null, task(""), context(null));

        assertThat(outcome.success()).isTrue();
        assertThat(action.attempts()).isEqualTo(3);
        // 第二轮起 Thinking 携带上一轮真实诊断（不是空设法 sustaining）。
        assertThat(action.tasks().get(1).diagnostics()).contains("真实诊断-1");
        assertThat(action.tasks().get(2).diagnostics()).contains("真实诊断-2");
        // 重试期间 attemptId 保持不变以便审计关联。
        assertThat(action.tasks().get(1).attemptId()).isEqualTo(action.tasks().get(0).attemptId());
    }

    @Test
    @DisplayName("G15：可恢复失败超过上限 → 停止尝试并返回终态失败（不无限重试）")
    void retryableFailureBeyondLimit_shouldStopAtBound() {
        RecordingAction action = new RecordingAction(Integer.MAX_VALUE);
        WorkerExecutor executor = new WorkerExecutor(new AgentProperties(3, 2, 6000, 12, 2,
                AgentProperties.DISPATCH_OFF));

        WorkerOutcome outcome = executor.execute(action, null, task(""), context(null));

        assertThat(outcome.success()).isFalse();
        assertThat(action.attempts()).isEqualTo(2);
        assertThat(outcome.failureCode()).isEqualTo("RETRYABLE_FAILED");
        assertThat(outcome.failureReason()).contains("真实诊断-2");
    }

    // ------------------------------------------------------------------ G16

    @Test
    @DisplayName("G16：终态失败 → 不再尝试，结构化结果带失败码与真实原因")
    void terminalFailure_shouldNotRetry() {
        RecordingAction action = new RecordingAction(Integer.MAX_VALUE, false);
        WorkerExecutor executor = new WorkerExecutor(new AgentProperties(3, 2, 6000, 12, 5,
                AgentProperties.DISPATCH_OFF));

        WorkerOutcome outcome = executor.execute(action, null, task(""), context(null));

        assertThat(action.attempts()).isEqualTo(1);
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.retryable()).isFalse();
        assertThat(outcome.failureCode()).isEqualTo("TERMINAL_FAILED");
    }

    // ------------------------------------------------------------------ Review

    @Test
    @DisplayName("G16：Review 阶段角色不一致 → 直接拒绝执行，不调用业务 Action")
    void reviewMismatch_shouldRejectBeforeAct() {
        RecordingAction action = new RecordingAction(1);
        WorkerExecutor executor = new WorkerExecutor(new AgentProperties(3, 2, 6000, 12, 3,
                AgentProperties.DISPATCH_OFF));
        // 任务角色与步骤声明角色不一致：由 Review 确定性拦截（不做实测）。
        WorkerTask mismatched = new WorkerTask("DASHBOARD", step("DATA_ANALYST"), "attempt-1", 1, "",
                "SIMPLE", "", null);

        WorkerOutcome outcome = executor.execute(action, null, mismatched, context(null));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.failureCode()).isEqualTo("WORKER_INPUT_INVALID");
        assertThat(action.attempts()).isZero();
    }

    // ------------------------------------------------------------------ G17

    @Test
    @DisplayName("G17：SQL Guard 拒绝 → JDBC 零调用且步骤标记为失败（不写成功）")
    void guardRejection_shouldSkipJdbcAndMarkFailed() {
        try (AgentGraphStack stack = simpleStack(AgentProperties.DISPATCH_OFF)) {
            when(stack.guard.validate(anyString(), anyString()))
                    .thenThrow(new com.queryforge.shared.error.ApiException(
                            ErrorCode.SQL_NOT_ALLOWED,
                            org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "只允许 SELECT"));

            String sse = start(stack);

            assertThat(sse).contains("event: run.failed");
            assertThat(sse).contains("SQL_NOT_ALLOWED");
            assertThat(sse).doesNotContain("event: step.succeeded");
            verify(stack.executionService, never()).execute(anyLong(), any(), anyLong(), anyLong());
            verify(stack.repository).failStep(eq(STEP_ID), eq("SQL_NOT_ALLOWED"), anyString());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    @DisplayName("G17：SQL 执行 Fixable 失败且有界修复仍失败 → 终态失败，不上报成功")
    void repeatedSqlFailure_shouldTerminateAfterBoundedRepairs() {
        try (AgentGraphStack stack = simpleStack(AgentProperties.DISPATCH_OFF)) {
            when(stack.guard.validate(anyString(), anyString())).thenReturn(SQL);
            when(stack.executionService.execute(eq(USER_ID), any(), eq(RUN_ID), eq(STEP_ID)))
                    .thenThrow(sqlFailure("语法错误"))
                    .thenThrow(sqlFailure("仍然失败"));

            String sse = start(stack);

            assertThat(sse).contains("event: run.failed");
            assertThat(sse).doesNotContain("event: step.succeeded");
            // 初始执行 1 次 + 修复 1 次（maxRepairs=1 的上界），之后不再尝试。
            verify(stack.executionService, times(2)).execute(eq(USER_ID), any(), eq(RUN_ID), eq(STEP_ID));
            verify(stack.repository).failStep(eq(STEP_ID), eq("SQL_EXECUTION_ERROR"), anyString());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------ G21

    @Test
    @DisplayName("G21：LLM 无 reasoning_content → 不伪造思考内容，但真实 SQL/工具/报告照常运行")
    void missingReasoning_shouldNotFabricateThinking() {
        try (AgentGraphStack stack = simpleStack(AgentProperties.DISPATCH_OFF)) {
            when(stack.guard.validate(anyString(), anyString())).thenReturn(SQL);
            when(stack.executionService.execute(eq(USER_ID), any(), eq(RUN_ID), eq(STEP_ID)))
                    .thenReturn(result());

            String sse = start(stack);

            // Fake LLM 从不输出 reasoning_content：思考内容一律不出现（真实=false 时不得伪造）。
            assertThat(sse).doesNotContain("reasoningContent");
            assertThat(sse).doesNotContain("thinking_content");
            assertThat(sse).doesNotContain("event: thinking");
            // 真实执行链路不受影响：Worker 阶段事件仍在（thinking/acting/verified 为阶段名，不是内容）。
            assertThat(sse).contains("\"phase\":\"thinking\"");
            assertThat(sse).contains("\"phase\":\"verified\"");
            assertThat(sse).contains("event: step.succeeded");
            assertThat(sse).contains("event: run.completed");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------ G18

    @Test
    @DisplayName("G18：OFF/BYPASS/SWITCH 三模式同一业务结果一致，且业务动作各只执行一次")
    void threeDispatchModes_shouldProduceIdenticalOutcome() {
        String off = runMode(AgentProperties.DISPATCH_OFF);
        String bypass = runMode(AgentProperties.DISPATCH_BYPASS);
        String switchMode = runMode(AgentProperties.DISPATCH_SWITCH);

        for (String sse : List.of(off, bypass, switchMode)) {
            assertThat(sse).contains("\"role\":\"DATA_SCIENTIST\"");
            assertThat(sse).contains("event: step.succeeded");
            assertThat(sse).contains("event: run.completed");
            assertThat(execCount(sse)).isEqualTo(1);
        }
        // SWITCH 经 ACK + Result：总线事件出现 delivered 与 result 两种 kind。
        assertThat(switchMode).contains("\"kind\":\"dispatch\"");
        assertThat(switchMode).contains("\"kind\":\"delivered\"");
        assertThat(switchMode).contains("\"kind\":\"result\"");
        assertThat(switchMode).contains("\"mode\":\"SWITCH\"");
        // OFF 不产生总线事件；BYPASS 只镜像，不额外执行业务。
        assertThat(off).doesNotContain("event: bus.dispatched");
        assertThat(bypass).contains("\"mode\":\"BYPASS\"");
    }

    // ------------------------------------------------------------------ G19

    @Test
    @DisplayName("G19：重复 TaskDispatch（同 correlationId） → Worker 去重重放，不重复调用真实业务")
    void duplicateDispatch_shouldReplayWithoutSecondExecution() {
        AgentMessageBus bus = new AgentMessageBus();
        WorkerExecutor executor = new WorkerExecutor(new AgentProperties(3, 2, 6000, 12, 2,
                AgentProperties.DISPATCH_SWITCH));
        WorkerEndpoint endpoint = new WorkerEndpoint("endpoint-dedupe", bus, executor);
        bus.registerEndpoint(endpoint.endpointId());

        List<BusMessage> acks = new ArrayList<>();
        bus.subscribe("agent.bus.ack", acks::add);

        RecordingAction action = new RecordingAction(0);
        WorkerTask task = task("attempt-dedupe");
        String correlationId = RUN_ID + "/" + STEP_ID + "/" + task.attemptId();
        endpoint.registerPending(correlationId,
                new WorkerEndpoint.PendingExecution(action, null, task, context(null)));

        BusMessage.TaskDispatch message = new BusMessage.TaskDispatch("msg-1", correlationId, RUN_ID, STEP_ID,
                task.attemptId(), "graph", "endpoint", "worker", Map.of("role", "DATA_SCIENTIST"));
        BusMessage.WorkerResult first = endpoint.handle(message);
        BusMessage.WorkerResult second = endpoint.handle(message);

        assertThat(action.attempts()).isEqualTo(1);
        assertThat(first.outcome()).isEqualTo(second.outcome());
        assertThat(endpoint.cachedResult(correlationId)).isNotNull();
        // ACK 先于 Result：受理与结果分离，ACK 丢失不得被当作"未执行"。
        assertThat(acks).hasSize(1);
        assertThat(acks.get(0)).isInstanceOf(BusMessage.TaskAck.class);
    }

    @Test
    @DisplayName("G19：200 组 TaskDispatch 各重复投递一次 → 每组只执行一次并重放同一结果")
    void duplicateDispatchBurst_shouldExecuteExactlyOncePerCorrelationId() {
        AgentMessageBus bus = new AgentMessageBus();
        WorkerExecutor executor = new WorkerExecutor(new AgentProperties(3, 2, 6000, 12, 2,
                AgentProperties.DISPATCH_SWITCH));
        WorkerEndpoint endpoint = new WorkerEndpoint("endpoint-burst-dedupe", bus, executor);
        bus.registerEndpoint(endpoint.endpointId());

        List<BusMessage> acks = new ArrayList<>();
        bus.subscribe("agent.bus.ack", acks::add);

        RecordingAction action = new RecordingAction(0);
        int distinctTasks = 200;
        for (int index = 0; index < distinctTasks; index++) {
            WorkerTask task = task("attempt-burst-" + index);
            String correlationId = RUN_ID + "/" + STEP_ID + "/" + task.attemptId();
            endpoint.registerPending(correlationId,
                    new WorkerEndpoint.PendingExecution(action, null, task, context(null)));
            BusMessage.TaskDispatch message = new BusMessage.TaskDispatch(
                    "msg-burst-" + index, correlationId, RUN_ID, STEP_ID, task.attemptId(),
                    "graph", "endpoint", "worker", Map.of("role", "DATA_SCIENTIST"));

            BusMessage.WorkerResult first = endpoint.handle(message);
            BusMessage.WorkerResult duplicate = endpoint.handle(message);

            assertThat(duplicate.outcome()).isEqualTo(first.outcome());
            assertThat(endpoint.cachedResult(correlationId)).isNotNull();
        }

        // 400 次投递（200 个 correlationId，每个重复一次）只产生 200 次真实业务调用和 200 个 ACK。
        assertThat(action.attempts()).isEqualTo(distinctTasks);
        assertThat(acks).hasSize(distinctTasks)
                .allMatch(BusMessage.TaskAck.class::isInstance);
    }

    @Test
    @DisplayName("G19：SWITCH 端点未注册 → 明确失败且不本地兜底执行（防双执行）")
    void missingEndpoint_shouldFailWithoutLocalFallback() {
        AgentMessageBus bus = new AgentMessageBus();
        WorkerExecutor executor = new WorkerExecutor(new AgentProperties(3, 2, 6000, 12, 2,
                AgentProperties.DISPATCH_SWITCH));
        WorkerEndpoint endpoint = new WorkerEndpoint("endpoint-absent", bus, executor);
        // 未调用 bus.registerEndpoint：SWITCH 拒绝分派。
        WorkerDispatcher dispatcher = new WorkerDispatcher(new AgentProperties(3, 2, 6000, 12, 2,
                AgentProperties.DISPATCH_SWITCH), bus, endpoint, executor);
        RecordingAction action = new RecordingAction(1);

        WorkerOutcome outcome = dispatcher.dispatch(action, null, task("attempt-absent"), context(null));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.failureCode()).isEqualTo("BUS_NO_CONSUMER");
        assertThat(action.attempts()).isZero();
    }

    // ------------------------------------------------------------------ 装配

    private static String runMode(String mode) {
        try (AgentGraphStack stack = simpleStack(mode)) {
            when(stack.guard.validate(anyString(), anyString())).thenReturn(SQL);
            when(stack.executionService.execute(eq(USER_ID), any(), eq(RUN_ID), eq(STEP_ID)))
                    .thenReturn(result());
            return start(stack);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static int execCount(String sse) {
        int count = 0;
        int index = 0;
        while ((index = sse.indexOf("event: step.succeeded", index)) >= 0) {
            count++;
            index += "event: step.succeeded".length();
        }
        return count;
    }

    private static String start(AgentGraphStack stack) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SseEventWriter writer = stack.capturingWriter(output);
        PreparedRun prepared = new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID,
                "test_db", QUESTION, AgentGraphStack.defaultConfig(), null, null, Boolean.TRUE);
        stack.runner.start(prepared, writer);
        return output.toString(StandardCharsets.UTF_8);
    }

    private static AgentGraphStack simpleStack(String dispatchMode) throws Exception {
        AgentGraphStack stack = AgentGraphStack.create(AgentGraphStack.props(3, 1, 12, 2, dispatchMode));
        stack.stubCommonContext(USER_ID, CONNECTION_ID, CONVERSATION_ID,
                "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL)");
        stack.stubGraphCheckpointStore();
        stack.stubGraphThreadBinding();
        stack.llm.on("[MANAGER]", "{\"route\":\"SIMPLE\",\"reasonCode\":\"单步聚合\"}")
                .on("[SQL]", SQL)
                .on("[SUMMARY]", "共返回 1 行汇总结果。")
                .fallback("{}");

        AgentRun run = new AgentRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "test_db", QUESTION,
                RunStatus.PLANNING, null, null, null, 0, null, null, null, NOW, NOW, null, null, null, null,
                Boolean.TRUE, 0, null, false, null, 0, null);
        when(stack.repository.findRunOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(run));
        when(stack.repository.findRun(RUN_ID)).thenReturn(Optional.of(run));
        when(stack.repository.insertStep(eq(RUN_ID), anyInt(), anyString(), Mockito.nullable(Integer.class),
                Mockito.nullable(String.class), Mockito.nullable(String.class), Mockito.nullable(String.class)))
                .thenReturn(STEP_ID);
        when(stack.repository.claimNonSqlStep(RUN_ID, STEP_ID)).thenReturn(1);
        when(stack.repository.setStepSql(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(1);
        when(stack.repository.completeStep(eq(STEP_ID), anyInt(), anyBoolean(), anyString())).thenReturn(1);
        Mockito.lenient().when(stack.repository.findSteps(RUN_ID))
                .thenReturn(List.of(new AgentStep(STEP_ID, RUN_ID, 1, QUESTION, null, StepStatus.SUCCEEDED, SQL,
                        1, "DATA_ANALYST", com.queryforge.domain.agent.ApprovalStatus.PENDING, 0, 1, false,
                        null, null, "返回 1 行", NOW, NOW, null, null)));
        Mockito.lenient().when(stack.repository.findStep(RUN_ID, STEP_ID))
                .thenReturn(Optional.of(new AgentStep(STEP_ID, RUN_ID, 1, QUESTION, null, StepStatus.PENDING,
                        null, 0, "DATA_ANALYST", com.queryforge.domain.agent.ApprovalStatus.PENDING, 0, 0,
                        false, null, null, null, NOW, NOW, null, null)));
        Mockito.lenient().when(stack.repository.updateRunStatus(eq(RUN_ID), any(), any(), any(), any()))
                .thenReturn(1);
        Mockito.lenient().when(stack.repository.updateRunRoute(eq(RUN_ID), anyString(), any(), any()))
                .thenReturn(1);
        Mockito.lenient().when(stack.repository.transitionRunStatus(eq(RUN_ID), any())).thenReturn(1);
        Mockito.lenient().when(stack.repository.tryIncrementLlmCalls(RUN_ID, 12)).thenReturn(1);
        return stack;
    }

    private static SqlExecutionException sqlFailure(String message) {
        return new SqlExecutionException(ErrorCode.SQL_EXECUTION_ERROR,
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, message, "42S02", 1146, true, null);
    }

    private static QueryResultResponse result() {
        return new QueryResultResponse(List.of("total"), List.of(Map.of("total", 128)), 1, false);
    }

    private static WorkerTask task(String attemptId) {
        return new WorkerTask("DATA_SCIENTIST", step("DATA_ANALYST"), attemptId, 1, "", "SIMPLE", "", null);
    }

    private static GraphPlanStep step(String role) {
        return new GraphPlanStep(STEP_ID, 1, QUESTION, role, List.of(), null, null,
                GraphPlanStep.STATUS_PENDING, null, null);
    }

    private static AgentRunTaskContext context(SseEventWriter writer) {
        return new AgentRunTaskContext(RUN_ID, USER_ID, null, CONVERSATION_ID, CONNECTION_ID, "test_db",
                QUESTION, "thread-worker-test", null, true, null, null, writer, "schema", null);
    }

    /** 受控 Action：前 {@code failuresBeforeSuccess} 次返回可恢复失败，之后成功。 */
    private static final class RecordingAction implements WorkerAction {

        private final int failuresBeforeSuccess;
        private final boolean retryable;
        private final AtomicInteger attempts = new AtomicInteger(0);
        private final List<WorkerTask> tasks = new ArrayList<>();

        private RecordingAction(int failuresBeforeSuccess) {
            this(failuresBeforeSuccess, true);
        }

        private RecordingAction(int failuresBeforeSuccess, boolean retryable) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.retryable = retryable;
        }

        @Override
        public String role() {
            return "DATA_SCIENTIST";
        }

        @Override
        public WorkerOutcome act(WorkerTask task, AgentRunTaskContext ctx, WorkerDiagnosticSink sink) {
            tasks.add(task);
            int attempt = attempts.incrementAndGet();
            if (attempt > failuresBeforeSuccess) {
                return WorkerOutcome.success(role(), task.stepId(), task.attemptId(), task.stepId(),
                        "真实结果摘要");
            }
            String reason = "真实诊断-" + attempt;
            sink.accept("act", reason);
            return WorkerOutcome.failure(role(), task.stepId(), task.attemptId(), retryable,
                    retryable ? "RETRYABLE_FAILED" : "TERMINAL_FAILED", reason);
        }

        int attempts() {
            return attempts.get();
        }

        List<WorkerTask> tasks() {
            return tasks;
        }
    }
}
