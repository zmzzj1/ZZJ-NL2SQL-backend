package com.queryforge.application.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.queryforge.application.agent.roles.RoleAssigner;
import com.queryforge.application.agent.roles.ToolAssistantAgent;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.application.knowledge.KnowledgeService;
import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.application.memory.MemoryService;
import com.queryforge.application.skill.SkillGuidanceSelector;
import com.queryforge.application.skill.SkillService;
import com.queryforge.application.tool.AgentRunToolPolicy;
import com.queryforge.application.tool.ToolOutputSanitizer;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentRoute;
import com.queryforge.domain.agent.CreateAgentRunRequest;
import com.queryforge.domain.agent.CreateAgentRunRequest.ToolInvocationRequest;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.conversation.MessageRole;
import com.queryforge.domain.conversation.MessageStatus;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.domain.tool.ToolInvocationResult;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.AgentPlanStore;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
import com.queryforge.domain.agent.ResolvedAgentConfig;

/**
 * P14-A：Manager 闲聊/澄清真实状态与显式工具快捷路径（T2/T3/T4）。
 * 只覆盖本轮新增逻辑，不重复既有 SQL 审批链测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P14-A 闲聊、澄清与显式工具快捷路径")
class AgentRunServiceP14ATest {

    private static final long USER_ID = 1L;
    private static final long RUN_ID = 5L;
    private static final long CONVERSATION_ID = 7L;
    private static final long CONNECTION_ID = 10L;
    private static final long STEP_ID = 11L;
    private static final String QUESTION = "你好";
    private static final String SCHEMA = "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL)";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);

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
    @Mock
    private MemoryService memoryService;
    @Mock
    private AgentGraphStateService graphStateService;
    @Mock
    private RoleAssigner roleAssigner;
    @Mock
    private AgentPlanStore planStore;
    @Mock
    private ApprovalSessionStore approvalSessionStore;
    @Mock
    private PlanReviewSessionStore planReviewSessionStore;
    @Mock
    private PlanReviewGate planReviewGate;
    @Mock
    private ToolAssistantAgent toolAssistant;
    @Mock
    private AgentRunToolPolicy toolPolicy;
    @Mock
    private ToolOutputSanitizer toolOutputSanitizer;
    @Mock
    private com.queryforge.application.agent.sql.SqlCandidateSelectionService selectionService;

    @Mock
    private AgentPythonAssistant pythonAssistant;

    @Mock
    private com.queryforge.application.agent.roles.CodeAnalystAgent codeAnalyst;

    @Mock
    private com.queryforge.application.report.ReportService reportService;

    @Mock
    private AgentReportComposer reportComposer;

    @Mock
    private com.queryforge.application.analysis.AnalysisService analysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentStepEngine engine;
    private AgentRunService service;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties(3, 2, 6000, 12);
        AgentPlanStateService planState = new AgentPlanStateService(planStore);
        AgentRoleRecorder roleRecorder = new AgentRoleRecorder(repository, objectMapper);
        AgentLlmCallLimiter llmLimiter = new AgentLlmCallLimiter(repository, properties);
        AgentContextAssembler contextAssembler = new AgentContextAssembler(
                knowledgeService, new SkillGuidanceSelector(skillService), memoryService, repository, objectMapper);
        AgentRunFailureHandler failureHandler = new AgentRunFailureHandler(
                repository, graphStateService, approvalSessionStore, planState);
        engine = new AgentStepEngine(
                repository, conversationService, connectionService, planner, sqlAssistant,
                executionService, guard, properties, managerRouter, graphStateService,
                roleAssigner, approvalSessionStore, contextAssembler, failureHandler,
                planState, roleRecorder, llmLimiter, toolAssistant, toolPolicy, toolOutputSanitizer,
                selectionService, pythonAssistant, codeAnalyst, reportService, reportComposer,
                analysisService);
        service = new AgentRunService(
                repository, conversationService, connectionService, sqlAssistant, objectMapper,
                knowledgeService, configResolver, graphStateService, approvalSessionStore,
                engine, contextAssembler, failureHandler, planState, roleRecorder, llmLimiter,
                new ToolInvocationRequestGuard(),
                planReviewGate, planReviewSessionStore, planner);
        lenient().when(repository.tryIncrementLlmCalls(anyLong(), anyInt())).thenReturn(1);
    }

    private void stubCommon() {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("");
    }

    private PreparedRun prepared() {
        return new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "sql_demo_a", QUESTION);
    }

    private PreparedRun preparedWithTool(String toolName, Map<String, Object> args) {
        return new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "sql_demo_a", QUESTION,
                ResolvedAgentConfig.defaults(), null, new ToolInvocationRequest(toolName, args), Boolean.TRUE);
    }

    private String start(PreparedRun prepared) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.startRun(prepared, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------- T2 闲聊

    @Test
    @DisplayName("T2：闲聊使用真实模型回答并持久化，不产生 SQL 步骤")
    void chitchat_shouldUseRealModelAnswerAndPersist() throws Exception {
        stubCommon();
        when(managerRouter.route(any(ManagerRouter.RoutingContext.class)))
                .thenReturn(new ManagerRouter.RouteDecision(AgentRoute.CHITCHAT, "问候"));
        when(sqlAssistant.chitchatAnswer(anyString(), anyString()))
                .thenReturn("你好！我可以基于已授权 Schema 生成只读 SQL。");
        when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        when(sqlAssistant.memorySummary(any(), anyString(), anyString())).thenReturn("用户打过招呼");

        String output = start(prepared());

        assertThat(output).contains("event: agent.routed", "CHITCHAT", "event: run.completed");
        assertThat(output).contains("你好！我可以基于已授权 Schema 生成只读 SQL。");
        verify(repository).updateRunStatus(
                RUN_ID, RunStatus.COMPLETED, "你好！我可以基于已授权 Schema 生成只读 SQL。", null, null);
        verify(conversationService).appendMessage(
                CONVERSATION_ID, MessageRole.ASSISTANT, "你好！我可以基于已授权 Schema 生成只读 SQL。",
                MessageStatus.COMPLETED);
        verify(repository, never()).insertStep(any(), anyInt(), anyString(), any());
        verify(planner, never()).plan(anyString(), anyString(), anyString());
        verify(sqlAssistant, never()).generateSql(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("T2：模型失败不伪造成功回复，进入 Run.FAILED")
    void chitchat_shouldFailWhenModelFails() throws Exception {
        stubCommon();
        when(managerRouter.route(any(ManagerRouter.RoutingContext.class)))
                .thenReturn(new ManagerRouter.RouteDecision(AgentRoute.CHITCHAT, "问候"));
        when(sqlAssistant.chitchatAnswer(anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "模型服务暂不可用"));

        String output = start(prepared());

        assertThat(output).contains("event: run.failed", "LLM_UPSTREAM_ERROR");
        verify(repository).updateRunStatus(
                RUN_ID, RunStatus.FAILED, null, "LLM_UPSTREAM_ERROR", "对话生成失败，请稍后重试");
        verify(repository, never()).insertStep(any(), anyInt(), anyString(), any());
    }

    @Test
    @DisplayName("T2：模型返回空串时用简洁能力说明兜底（不冒充模型内容）")
    void chitchat_shouldFallbackOnlyOnBlankAnswer() throws Exception {
        stubCommon();
        when(managerRouter.route(any(ManagerRouter.RoutingContext.class)))
                .thenReturn(new ManagerRouter.RouteDecision(AgentRoute.CHITCHAT, "问候"));
        when(sqlAssistant.chitchatAnswer(anyString(), anyString())).thenReturn("   ");
        when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        when(sqlAssistant.memorySummary(any(), anyString(), anyString())).thenReturn("用户打过招呼");

        String output = start(prepared());

        assertThat(output).contains("event: run.completed");
        verify(repository).updateRunStatus(
                RUN_ID, RunStatus.COMPLETED,
                "我可以基于你已授权的数据源生成只读 SQL（需你确认后执行）、做多步分析与图表，"
                        + "并解释授权知识库中的业务口径。直接描述你的问题即可。",
                null, null);
    }

    // ------------------------------------------------------------------- T3 澄清

    @Test
    @DisplayName("T3：缺少关键口径时进入 CLARIFYING，不生成 SQL 与审批")
    void clarify_shouldWaitWithoutGeneratingSql() throws Exception {
        stubCommon();
        when(managerRouter.route(any(ManagerRouter.RoutingContext.class)))
                .thenReturn(new ManagerRouter.RouteDecision(AgentRoute.CLARIFY, "请补充统计的时间窗口"));

        String output = start(prepared());

        assertThat(output).contains("event: clarify.required", "请补充统计的时间窗口");
        assertThat(output).doesNotContain("event: run.completed");
        verify(repository).updateRunStatus(
                RUN_ID, RunStatus.CLARIFYING, null, "AGENT_CLARIFY", "请补充统计的时间窗口");
        verify(repository, never()).insertStep(any(), anyInt(), anyString(), any());
        verify(approvalSessionStore, never()).register(any(), any(), anyInt(), anyString());
        verifyNoInteractions(planner, sqlAssistant);
    }

    // --------------------------------------------------------------- T4 显式工具

    @Test
    @DisplayName("T4：已授权工具绕开分类与 Planner，单次执行并落库")
    void toolInvocation_shouldBypassRouterAndPlanner() throws Exception {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        when(repository.insertStep(RUN_ID, 1, "调用工具 describe_schema", null, "TOOL_ASSISTANT"))
                .thenReturn(STEP_ID);
        when(repository.findSteps(RUN_ID)).thenReturn(List.of());
        when(toolPolicy.allowsStrictly(RUN_ID, "describe_schema")).thenReturn(true);
        when(repository.startNonSqlStep(STEP_ID)).thenReturn(1);
        when(toolAssistant.invoke(any(), any(), any(), any())).thenReturn(
                new ToolInvocationResult("describe_schema", Map.of("tables", List.of("shop_orders")), 12));
        when(toolOutputSanitizer.summarize(any())).thenReturn("{\"tables\":[\"shop_orders\"]}");
        when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        when(sqlAssistant.memorySummary(any(), anyString(), anyString())).thenReturn("查看了表结构");

        String output = start(preparedWithTool("describe_schema", Map.of("table", "orders")));

        assertThat(output).contains("event: agent.routed", "TOOL_INVOCATION");
        assertThat(output).contains("event: plan.ready", "event: tool.result", "event: run.completed");
        // 跳过分类模型与 Planner。
        verifyNoInteractions(managerRouter, planner);
        verify(sqlAssistant, never()).generateSql(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        // 恰好一次工具调用，经角色能力 + ToolRegistry 双层授权。
        verify(toolAssistant).invoke(any(), any(), any(), any());
        verify(toolPolicy).allowsStrictly(RUN_ID, "describe_schema");
        verify(repository).insertStep(RUN_ID, 1, "调用工具 describe_schema", null, "TOOL_ASSISTANT");
        verify(repository).startNonSqlStep(STEP_ID);
        verify(repository).completeNonSqlStep(STEP_ID, "{\"tables\":[\"shop_orders\"]}");
        verify(repository).updateRunStatus(
                RUN_ID, RunStatus.COMPLETED, "{\"tables\":[\"shop_orders\"]}", null, null);
        verify(repository, never()).setStepSql(any(), anyString(), anyInt(), anyInt());
        verify(approvalSessionStore, never()).register(any(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("T4：Agent 未授权的工具明确失败，不调用工具")
    void toolInvocation_shouldRejectUnauthorizedTool() throws Exception {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        when(repository.insertStep(RUN_ID, 1, "调用工具 drop_table", null, "TOOL_ASSISTANT"))
                .thenReturn(STEP_ID);
        when(repository.findSteps(RUN_ID)).thenReturn(List.of());
        when(toolPolicy.allowsStrictly(RUN_ID, "drop_table")).thenReturn(false);

        String output = start(preparedWithTool("drop_table", Map.of()));

        assertThat(output).contains("event: run.failed", "TOOL_NOT_IN_AGENT_WHITELIST");
        verify(toolAssistant, never()).invoke(any(), any());
        verify(repository).failNonSqlStep(STEP_ID, "TOOL_NOT_IN_AGENT_WHITELIST",
                "当前 Agent 配置未授权该工具：drop_table");
        verifyNoInteractions(managerRouter, planner);
    }

    @Test
    @DisplayName("T4：工具执行失败保存失败类别并发 run.failed")
    void toolInvocation_shouldFailWhenToolExecutionFails() throws Exception {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        when(repository.insertStep(RUN_ID, 1, "调用工具 describe_schema", null, "TOOL_ASSISTANT"))
                .thenReturn(STEP_ID);
        when(repository.findSteps(RUN_ID)).thenReturn(List.of());
        when(toolPolicy.allowsStrictly(RUN_ID, "describe_schema")).thenReturn(true);
        when(repository.startNonSqlStep(STEP_ID)).thenReturn(1);
        when(toolAssistant.invoke(any(), any()))
                .thenThrow(new ApiException(ErrorCode.TOOL_EXECUTION_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "工具执行失败"));

        String output = start(preparedWithTool("describe_schema", Map.of()));

        assertThat(output).contains("event: run.failed", "TOOL_EXECUTION_ERROR");
        verify(repository).failNonSqlStep(STEP_ID, "TOOL_EXECUTION_ERROR", "工具执行失败");
        verify(repository, never()).completeNonSqlStep(any(), anyString());
    }

    @Test
    @DisplayName("T4：步骤已被处理时不二次执行工具（重连/重试安全）")
    void toolInvocation_shouldNotExecuteTwice() throws Exception {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        when(repository.insertStep(RUN_ID, 1, "调用工具 describe_schema", null, "TOOL_ASSISTANT"))
                .thenReturn(STEP_ID);
        when(repository.findSteps(RUN_ID)).thenReturn(List.of());
        when(toolPolicy.allowsStrictly(RUN_ID, "describe_schema")).thenReturn(true);
        when(repository.startNonSqlStep(STEP_ID)).thenReturn(0);
        when(repository.findStep(RUN_ID, STEP_ID)).thenReturn(Optional.of(new com.queryforge.domain.agent.AgentStep(
                STEP_ID, RUN_ID, 1, "调用工具 describe_schema", null, StepStatus.SUCCEEDED, null, 0,
                "TOOL_ASSISTANT", com.queryforge.domain.agent.ApprovalStatus.PENDING, 0,
                null, null, null, null, "{\"tables\":[]}", NOW, NOW, null, null)));

        String output = start(preparedWithTool("describe_schema", Map.of()));

        verify(toolAssistant, never()).invoke(any(), any());
        assertThat(output).contains("event: tool.result", "SUCCEEDED");
    }

    @Test
    @DisplayName("T4：显式工具请求在服务端做边界校验（非法工具名直接拒绝）")
    void prepareStart_shouldRejectInvalidToolInvocation() {
        when(conversationService.requireMatchingConversation(USER_ID, CONVERSATION_ID, CONNECTION_ID))
                .thenReturn(CONVERSATION_ID);
        when(connectionService.summary(USER_ID, CONNECTION_ID))
                .thenReturn(new ConnectionService.ConnectionSummary(CONNECTION_ID, "A 商店库", "sql_demo_a"));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.prepareStart(
                USER_ID, new CreateAgentRunRequest(
                        CONVERSATION_ID, CONNECTION_ID, "查看表结构", null, null, null, null,
                        new ToolInvocationRequest("describe schema; drop", Map.of()), null))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                });
        verify(repository, never()).insertRun(any(), any(), any(), any(), anyString(), any(), any(), any(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("T1：分类不可用降级 MEDIUM 时继续走 Planner，不静默放行 SQL")
    void medium_shouldFallThroughToPlanner() throws Exception {
        stubCommon();
        when(managerRouter.route(any(ManagerRouter.RoutingContext.class)))
                .thenReturn(new ManagerRouter.RouteDecision(AgentRoute.MEDIUM, "分类不可用，已按 Planner 安全降级"));
        when(planner.plan(anyString(), anyString(), anyString()))
                .thenReturn(new com.queryforge.domain.agent.AgentPlan(
                        true, "", List.of(new com.queryforge.domain.agent.AgentPlan.Step(QUESTION))));
        when(repository.insertStep(RUN_ID, 1, QUESTION, null, "DATA_ANALYST")).thenReturn(STEP_ID);
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(new com.queryforge.domain.agent.AgentStep(
                STEP_ID, RUN_ID, 1, QUESTION, null, StepStatus.PENDING, null, 0, "SQL_AGENT",
                com.queryforge.domain.agent.ApprovalStatus.PENDING, 0, null, null, null, null, null, NOW, NOW,
                null, null)));
        when(roleAssigner.assign(anyString())).thenReturn(AgentRole.DATA_ANALYST);
        when(selectionService.generateAndSelect(
                any(com.queryforge.application.agent.sql.SqlSelectionRequest.class)))
                .thenReturn(new com.queryforge.application.agent.sql.SqlSelection(
                        "MULTI", "SELECT COUNT(*) AS c FROM shop_orders", 1, 3, 1, 0.998, 0.8, 0.879, false));
        when(guard.validate(anyString(), anyString())).thenReturn("SELECT COUNT(*) AS c FROM shop_orders");

        String output = start(prepared());

        assertThat(output).contains("event: agent.routed", "MEDIUM", "event: approval.required");
        verify(planner).plan(anyString(), anyString(), anyString());
    }
}
