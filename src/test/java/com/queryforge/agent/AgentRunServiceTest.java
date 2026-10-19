package com.queryforge.agent;
import com.queryforge.conversation.ConversationService;
import com.queryforge.datasource.ConnectionService;
import com.queryforge.knowledge.KnowledgeService;
import com.queryforge.query.execution.ReadOnlySqlGuard;
import com.queryforge.query.execution.SqlExecutionService;
import com.queryforge.config.ResolvedAgentConfig;
import com.queryforge.config.RuntimeConfigResolver;
import com.queryforge.skill.SkillService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.agent.AgentProperties;
import com.queryforge.agent.ApproveAgentStepRequest;
import com.queryforge.agent.CreateAgentRunRequest;
import com.queryforge.query.execution.ExecuteSqlRequest;
import com.queryforge.query.execution.QueryResultResponse;
import com.queryforge.agent.AgentRun;
import com.queryforge.agent.AgentStep;
import com.queryforge.agent.AgentRoute;
import com.queryforge.agent.ApprovalStatus;
import com.queryforge.conversation.MessageRole;
import com.queryforge.conversation.MessageStatus;
import com.queryforge.agent.RunStatus;
import com.queryforge.agent.StepStatus;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.query.execution.SqlExecutionException;
import com.queryforge.agent.AgentRunRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 运行编排")
class AgentRunServiceTest {

    private static final long USER_ID = 1L;
    private static final long RUN_ID = 5L;
    private static final long CONVERSATION_ID = 7L;
    private static final long CONNECTION_ID = 10L;
    private static final long STEP_ID = 11L;
    private static final String QUESTION = "统计订单数量";
    private static final String SCHEMA = "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL)";
    private static final String SQL = "SELECT COUNT(*) AS `订单数` FROM shop_orders";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 0);

    @Mock
    private AgentRunRepository repository;

    @Mock
    private ConversationService conversationService;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private AgentPlanner planner;

    @Mock
    private AgentSqlAssistant sqlAssistant;

    @Mock
    private SqlExecutionService executionService;

    @Mock
    private ReadOnlySqlGuard guard;

    @Mock
    private ManagerRouter managerRouter;

    @Mock
    private KnowledgeService knowledgeService;

    @Mock
    private RuntimeConfigResolver configResolver;

    @Mock
    private SkillService skillService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentRunService service;

    @BeforeEach
    void setUp() {
        service = new AgentRunService(
                repository, conversationService, connectionService, planner, sqlAssistant,
                executionService, guard, new AgentProperties(3, 2, 6000, 12), objectMapper,
                managerRouter, knowledgeService, configResolver, skillService);
    }

    @Test
    @DisplayName("创建运行前校验会话与数据源，并持久化用户问题")
    void prepareStart_shouldPersistQuestion() {
        when(conversationService.requireMatchingConversation(USER_ID, CONVERSATION_ID, CONNECTION_ID))
                .thenReturn(CONVERSATION_ID);
        when(connectionService.summary(USER_ID, CONNECTION_ID))
                .thenReturn(new ConnectionService.ConnectionSummary(CONNECTION_ID, "A 商店库", "sql_demo_a"));
        when(repository.insertRun(
                USER_ID, CONVERSATION_ID, CONNECTION_ID, "A 商店库", QUESTION, RunStatus.PLANNING,
                null, null, "{}", "[]"))
                .thenReturn(RUN_ID);

        AgentRunService.PreparedRun prepared = service.prepareStart(
                USER_ID, new CreateAgentRunRequest(CONVERSATION_ID, CONNECTION_ID, QUESTION, null, null, null));

        assertThat(prepared.runId()).isEqualTo(RUN_ID);
        assertThat(prepared.database()).isEqualTo("sql_demo_a");
        verify(conversationService).appendMessage(
                CONVERSATION_ID, MessageRole.USER, QUESTION, MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("规划成功后展示计划并生成第一步 SQL，等待用户确认")
    void startRun_shouldEmitPlanAndFirstApproval() throws Exception {
        stubPlan(1);
        when(sqlAssistant.generateSql(QUESTION, SCHEMA, "", "", "", "")).thenReturn(SQL);
        when(guard.validate(SQL, "sql_demo_a")).thenReturn(SQL);

        String output = start(prepared());

        assertThat(output).contains(
                "event: run.started",
                "event: plan.ready",
                "event: step.started",
                "event: step.sql_ready",
                "event: approval.required");
        assertThat(indexOf(output, "event: plan.ready")).isLessThan(indexOf(output, "event: approval.required"));
        verify(repository).setStepSql(STEP_ID, SQL, 1, 0);
        verify(repository).updateRunStatus(RUN_ID, RunStatus.WAITING_APPROVAL, null, null, null);
    }

    @Test
    @DisplayName("不可回答的问题以 run.failed 结束，不创建步骤")
    void startRun_shouldFailWhenPlanUnanswerable() throws Exception {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        when(managerRouter.route(QUESTION, null))
                .thenReturn(new ManagerRouter.RouteDecision(AgentRoute.COMPLEX, "需要多步分析"));
        when(planner.plan(QUESTION, SCHEMA))
                .thenReturn(new AgentPlan(false, "Schema 中没有退款表", List.of()));

        String output = start(prepared());

        assertThat(output).contains("event: run.failed", "AGENT_UNANSWERABLE");
        verify(repository, never()).insertStep(any(), anyInt(), anyString(), any());
    }

    @Test
    @DisplayName("生成期 SQL Guard 拒绝时步骤与运行都失败")
    void startRun_shouldFailWhenGuardRejects() throws Exception {
        stubPlan(1);
        when(sqlAssistant.generateSql(QUESTION, SCHEMA, "", "", "", "")).thenReturn(SQL);
        when(guard.validate(SQL, "sql_demo_a")).thenThrow(
                new ApiException(ErrorCode.SQL_NOT_ALLOWED, HttpStatus.BAD_REQUEST, "只允许只读 SELECT"));

        String output = start(prepared());

        assertThat(output).contains("event: step.failed", "event: run.failed", "SQL_NOT_ALLOWED");
        verify(repository).updateRunStatus(RUN_ID, RunStatus.FAILED, null, "SQL_NOT_ALLOWED", "只允许只读 SELECT");
    }

    @Test
    @DisplayName("确认后执行 SQL、写历史并生成最终总结")
    void executeApproval_shouldExecuteAndComplete() throws Exception {
        AgentStep waiting = step(StepStatus.WAITING_APPROVAL, SQL, 1, 0);
        AgentRun run = run(RunStatus.WAITING_APPROVAL);
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenReturn(result());
        when(repository.completeStep(eq(STEP_ID), eq(1), eq(false), anyString())).thenReturn(1);
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(
                step(StepStatus.SUCCEEDED, SQL, 1, 0)));
        when(sqlAssistant.summarize(eq(QUESTION), anyString())).thenReturn("订单总数为 3。");

        String output = approve(new AgentRunService.PreparedApproval(
                USER_ID, run, waiting, false, "sql_demo_a", ResolvedAgentConfig.defaults()));

        assertThat(output).contains("event: step.succeeded", "event: run.completed", "订单总数为 3。");
        verify(repository).updateRunStatus(RUN_ID, RunStatus.COMPLETED, "订单总数为 3。", null, null);
        verify(conversationService).appendMessage(
                CONVERSATION_ID, MessageRole.ASSISTANT, "订单总数为 3。", MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("可修复错误生成新版本并重新要求确认")
    void executeApproval_shouldRepairFixableError() throws Exception {
        AgentStep waiting = step(StepStatus.WAITING_APPROVAL, SQL, 1, 0);
        AgentRun run = run(RunStatus.RUNNING);
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenThrow(new SqlExecutionException(
                        ErrorCode.SQL_EXECUTION_ERROR, HttpStatus.UNPROCESSABLE_ENTITY, "SQL 执行失败",
                        "42S22", 1054, true, new SQLException("unknown column")));
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        String fixed = "SELECT COUNT(*) AS `订单数` FROM shop_orders WHERE status = 'paid'";
        when(sqlAssistant.fixSql(SQL, "SQL 执行失败", QUESTION, SCHEMA)).thenReturn(fixed);
        when(guard.validate(fixed, "sql_demo_a")).thenReturn(fixed);

        String output = approve(new AgentRunService.PreparedApproval(
                USER_ID, run, waiting, false, "sql_demo_a", ResolvedAgentConfig.defaults()));

        assertThat(output).contains("event: step.repairing", "event: step.sql_ready", "event: approval.required");
        verify(repository).setStepSql(STEP_ID, fixed, 2, 1);
        verify(repository).updateRunStatus(RUN_ID, RunStatus.WAITING_APPROVAL, null, null, null);
    }

    @Test
    @DisplayName("不可修复错误直接终止，不进入 Fixer")
    void executeApproval_shouldNotRepairNonFixableError() throws Exception {
        AgentStep waiting = step(StepStatus.WAITING_APPROVAL, SQL, 1, 0);
        AgentRun run = run(RunStatus.RUNNING);
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenThrow(new SqlExecutionException(
                        ErrorCode.SQL_EXECUTION_ERROR, HttpStatus.UNPROCESSABLE_ENTITY, "查询超时",
                        null, 0, false, new SQLException("timeout")));

        String output = approve(new AgentRunService.PreparedApproval(
                USER_ID, run, waiting, false, "sql_demo_a", ResolvedAgentConfig.defaults()));

        assertThat(output).contains("event: step.failed", "event: run.failed");
        verifyNoInteractions(sqlAssistant);
    }

    @Test
    @DisplayName("重复确认只回放既有结果，不重复执行")
    void executeApproval_shouldReplayWithoutExecuting() throws Exception {
        AgentStep succeeded = new AgentStep(
                STEP_ID, RUN_ID, 1, QUESTION, null, StepStatus.SUCCEEDED, SQL, 1, "SQL_AGENT",
                ApprovalStatus.APPROVED, 0, 1, false, null, null, "返回 1 行", NOW, NOW);
        AgentRun run = run(RunStatus.COMPLETED);
        when(connectionService.summary(USER_ID, CONNECTION_ID))
                .thenReturn(new ConnectionService.ConnectionSummary(CONNECTION_ID, "A 商店库", "sql_demo_a"));
        when(repository.findRunOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(run));
        when(repository.findStep(RUN_ID, STEP_ID)).thenReturn(Optional.of(succeeded));

        AgentRunService.PreparedApproval prepared = service.prepareApproval(
                USER_ID, RUN_ID, STEP_ID, new ApproveAgentStepRequest(1, true, "client-1"));
        String output = approve(prepared);

        assertThat(prepared.replay()).isTrue();
        assertThat(output).contains("event: step.succeeded", "返回 1 行");
        verifyNoInteractions(executionService);
        verify(repository, never()).claimStep(any(), any(), any(Integer.class));
    }

    @Test
    @DisplayName("版本不一致或非法状态返回 409")
    void prepareApproval_shouldRejectStaleVersion() {
        when(repository.findRunOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(run(RunStatus.WAITING_APPROVAL)));
        when(repository.findStep(RUN_ID, STEP_ID))
                .thenReturn(Optional.of(step(StepStatus.WAITING_APPROVAL, SQL, 2, 0)));

        assertThatThrownBy(() -> service.prepareApproval(
                USER_ID, RUN_ID, STEP_ID, new ApproveAgentStepRequest(1, true, null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_STATE_CONFLICT);
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    @DisplayName("拒绝待确认 SQL 会取消步骤与运行")
    void reject_shouldCancelRun() {
        when(repository.findRunOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(run(RunStatus.WAITING_APPROVAL)));
        when(repository.findStep(RUN_ID, STEP_ID))
                .thenReturn(Optional.of(step(StepStatus.WAITING_APPROVAL, SQL, 1, 0)));

        service.reject(USER_ID, RUN_ID, STEP_ID);

        verify(repository).rejectStep(STEP_ID, "用户拒绝执行该 SQL");
        verify(repository).updateRunStatus(
                RUN_ID, RunStatus.CANCELED, null, "AGENT_STEP_REJECTED", "用户拒绝执行该 SQL");
    }

    @Test
    @DisplayName("取消已是终态的运行时保持幂等")
    void cancel_shouldBeIdempotent() {
        when(repository.findRunOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(run(RunStatus.CANCELED)));
        when(repository.cancelActiveRun(RUN_ID, USER_ID)).thenReturn(0);

        service.cancel(USER_ID, RUN_ID);

        verify(repository, never()).cancelPendingSteps(RUN_ID);
    }

    private AgentRunService.PreparedRun prepared() {
        return new AgentRunService.PreparedRun(
                RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "sql_demo_a", QUESTION);
    }

    private void stubPlan(int stepCount) {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        when(managerRouter.route(QUESTION, null))
                .thenReturn(new ManagerRouter.RouteDecision(AgentRoute.COMPLEX, "需要多步分析"));
        when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("");
        List<AgentPlan.Step> planSteps = java.util.stream.IntStream.rangeClosed(1, stepCount)
                .mapToObj(index -> new AgentPlan.Step(QUESTION))
                .toList();
        when(planner.plan(QUESTION, SCHEMA)).thenReturn(new AgentPlan(true, "", planSteps));
        for (int index = 1; index <= stepCount; index++) {
            when(repository.insertStep(RUN_ID, index, QUESTION, index == 1 ? null : index - 1))
                    .thenReturn(STEP_ID + index - 1);
        }
        List<AgentStep> steps = java.util.stream.IntStream.rangeClosed(1, stepCount)
                .mapToObj(index -> new AgentStep(
                        STEP_ID + index - 1, RUN_ID, index, QUESTION, index == 1 ? null : index - 1,
                        StepStatus.PENDING, null, 0, "SQL_AGENT", ApprovalStatus.PENDING, 0,
                        null, null, null, null, null, NOW, NOW))
                .toList();
        when(repository.findSteps(RUN_ID)).thenReturn(steps);
    }

    private String start(AgentRunService.PreparedRun prepared) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.startRun(prepared, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private String approve(AgentRunService.PreparedApproval prepared) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.executeApproval(prepared, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private static int indexOf(String text, String needle) {
        return text.indexOf(needle);
    }

    private static AgentRun run(RunStatus status) {
        return new AgentRun(
                RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "A 商店库", QUESTION,
                status, null, null, null, 0, null, null, null, NOW, NOW,
                null, null, null, null);
    }

    private static AgentStep step(StepStatus status, String sql, int version, int repairCount) {
        return new AgentStep(
                STEP_ID, RUN_ID, 1, QUESTION, null, status, sql, version, "SQL_AGENT",
                ApprovalStatus.PENDING, repairCount, null, null, null, null, null, NOW, NOW);
    }

    private static QueryResultResponse result() {
        return new QueryResultResponse(
                List.of("订单数"), List.of(Map.of("订单数", 3)), 1, false);
    }
}
