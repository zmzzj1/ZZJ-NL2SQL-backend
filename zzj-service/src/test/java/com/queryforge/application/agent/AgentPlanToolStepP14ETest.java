package com.queryforge.application.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.application.agent.roles.CodeAnalystAgent;
import com.queryforge.application.agent.roles.RoleAssigner;
import com.queryforge.application.agent.roles.ToolAssistantAgent;
import com.queryforge.application.agent.sql.SqlCandidateSelectionService;
import com.queryforge.application.analysis.AnalysisService;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.application.report.ReportService;
import com.queryforge.application.tool.AgentRunToolPolicy;
import com.queryforge.application.tool.ToolOutputSanitizer;
import com.queryforge.domain.agent.AgentPlan;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.ApprovalStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.model.LlmClient;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.domain.tool.ToolInvocationResult;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.AgentPlanStore;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.infrastructure.web.SseEventWriter;
import java.io.ByteArrayOutputStream;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * P14-E E06：计划内工具步骤的最小交接。
 *
 * <p>覆盖：计划解析保留真实工具身份、缺少身份时明确拒绝、执行只调用一次受控
 * ToolAssistantAgent（内置/MCP 同一入口）、白名单外工具不发起调用、恢复后仍读
 * 持久化的工具名与参数。工具身份绝不从自由文字推断。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P14-E 计划内工具步骤交接")
class AgentPlanToolStepP14ETest {

    private static final long USER_ID = 1L;
    private static final long RUN_ID = 5L;
    private static final long CONVERSATION_ID = 7L;
    private static final long CONNECTION_ID = 10L;
    private static final long STEP_ID = 21L;
    private static final String QUESTION = "先查询各地区销售额，再调用知识库工具检索口径说明";
    private static final String TOOL_NAME = "knowledge_search";
    private static final String TOOL_ARGS_JSON = "{\"query\":\"营收口径\"}";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 10, 0);

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
    private ManagerRouter managerRouter;
    @Mock
    private AgentGraphStateService graphStateService;
    @Mock
    private RoleAssigner roleAssigner;
    @Mock
    private ApprovalSessionStore approvalSessionStore;
    @Mock
    private AgentContextAssembler contextAssembler;
    @Mock
    private AgentRunFailureHandler failureHandler;
    @Mock
    private AgentPlanStateService planState;
    @Mock
    private AgentRoleRecorder roleRecorder;
    @Mock
    private AgentLlmCallLimiter llmLimiter;
    @Mock
    private ToolAssistantAgent toolAssistant;
    @Mock
    private AgentRunToolPolicy toolPolicy;
    @Mock
    private ToolOutputSanitizer toolOutputSanitizer;
    @Mock
    private SqlCandidateSelectionService selectionService;
    @Mock
    private AgentPythonAssistant pythonAssistant;
    @Mock
    private CodeAnalystAgent codeAnalyst;
    @Mock
    private ReportService reportService;
    @Mock
    private AgentReportComposer reportComposer;
    @Mock
    private AnalysisService analysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentProperties properties = new AgentProperties(3, 2, 6000, 12);
    private AgentStepEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AgentStepEngine(
                repository, conversationService, connectionService, planner, sqlAssistant,
                executionService, new ReadOnlySqlGuard(), properties, managerRouter, graphStateService,
                roleAssigner, approvalSessionStore, contextAssembler, failureHandler, planState,
                roleRecorder, llmLimiter, toolAssistant, toolPolicy, toolOutputSanitizer,
                selectionService, pythonAssistant, codeAnalyst, reportService, reportComposer,
                analysisService);
        lenient().when(repository.failNonSqlStep(anyLong(), anyLong(), anyString(), anyString())).thenReturn(1);
        lenient().when(repository.findSteps(RUN_ID)).thenReturn(List.of());
        lenient().when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
    }

    private AgentStep toolStep() {
        return new AgentStep(STEP_ID, RUN_ID, 2, "调用知识库工具检索口径说明", 1, StepStatus.PENDING,
                null, 0, "TOOL_ASSISTANT", ApprovalStatus.PENDING, 0, null, null, null, null, null,
                NOW, NOW, null, null);
    }

    private PreparedRun run() {
        return new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "sql_demo_a", QUESTION);
    }

    private static String sse(ByteArrayOutputStream buffer) {
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 计划解析

    @Test
    @DisplayName("计划保留真实工具身份与参数，角色为 TOOL_ASSISTANT")
    void parse_shouldKeepExplicitToolIdentity() {
        AgentPlanner plannerUnderTest = new AgentPlanner(mock(LlmClient.class), objectMapper, properties);

        AgentPlan plan = plannerUnderTest.parse("""
                {"answerable": true, "steps": [
                  {"objective": "统计各地区销售额", "agent": "DATA_ANALYST"},
                  {"objective": "检索口径说明", "agent": "TOOL_ASSISTANT", "dependsOn": 1,
                   "tool": {"name": "knowledge_search", "args": {"query": "营收口径"}}}
                ]}
                """);

        assertThat(plan.steps()).hasSize(2);
        AgentPlan.ToolCall tool = plan.steps().get(1).tool();
        assertThat(tool).isNotNull();
        assertThat(tool.name()).isEqualTo(TOOL_NAME);
        assertThat(tool.args()).containsEntry("query", "营收口径");
        assertThat(plan.steps().get(0).tool()).isNull();
    }

    @Test
    @DisplayName("工具步骤缺少工具名时拒绝整个计划，不猜工具")
    void parse_shouldRejectToolStepWithoutToolName() {
        AgentPlanner plannerUnderTest = new AgentPlanner(mock(LlmClient.class), objectMapper, properties);

        assertThatThrownBy(() -> plannerUnderTest.parse("""
                {"answerable": true, "steps": [
                  {"objective": "调用某个外部工具", "agent": "TOOL_ASSISTANT"}
                ]}
                """))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_PLAN_INVALID));
    }

    @Test
    @DisplayName("工具参数不是 JSON 对象时拒绝计划")
    void parse_shouldRejectNonObjectToolArgs() {
        AgentPlanner plannerUnderTest = new AgentPlanner(mock(LlmClient.class), objectMapper, properties);

        assertThatThrownBy(() -> plannerUnderTest.parse("""
                {"answerable": true, "steps": [
                  {"objective": "检索", "agent": "TOOL_ASSISTANT",
                   "tool": {"name": "knowledge_search", "args": ["query"]}}
                ]}
                """))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_PLAN_INVALID));
    }

    @Test
    @DisplayName("计划落库时把工具名与参数写入同一步骤（可跨请求恢复）")
    void persistPlan_shouldStoreToolIdentityOnStep() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        engine.persistPlan(run(), new AgentPlan(true, "", List.of(
                new AgentPlan.Step("统计各地区销售额", "DATA_ANALYST", null, null),
                new AgentPlan.Step("检索口径说明", "TOOL_ASSISTANT", 1,
                        new AgentPlan.ToolCall(TOOL_NAME, Map.of("query", "营收口径"))))),
                new SseEventWriter(buffer, objectMapper));

        verify(repository).insertStep(RUN_ID, 1, "统计各地区销售额", null, "DATA_ANALYST");
        verify(repository).insertStep(RUN_ID, 2, "检索口径说明", 1, "TOOL_ASSISTANT",
                TOOL_NAME, "{\"query\":\"营收口径\"}");
    }

    // ------------------------------------------------------------------ 执行

    @Test
    @DisplayName("工具步骤经受控 ToolAssistantAgent 只执行一次并落真实摘要")
    void toolStep_shouldInvokeOnceThroughControlledAssistant() throws Exception {
        when(repository.findStepTool(RUN_ID, STEP_ID))
                .thenReturn(Optional.of(new AgentRunRepository.StepTool(TOOL_NAME, TOOL_ARGS_JSON)));
        when(toolPolicy.allowsStrictly(RUN_ID, TOOL_NAME)).thenReturn(true);
        when(repository.startNonSqlStep(STEP_ID)).thenReturn(1);
        when(toolAssistant.invoke(any(ToolInvocation.class), any(), any(), any()))
                .thenReturn(ToolInvocationResult.text(TOOL_NAME, "口径：营收=含税销售额", 12));
        when(toolOutputSanitizer.summarize(any())).thenReturn("口径：营收=含税销售额");
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(toolStep()));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        engine.advanceReadyStep(run(), toolStep(), null, "schema", new SseEventWriter(buffer, objectMapper), null);

        // 真实工具身份与参数来自持久化事实，调用恰好一次。
        verify(toolAssistant).invoke(any(ToolInvocation.class),
                eq(Map.of("query", "营收口径")), any(), any());
        verify(repository).completeNonSqlStep(STEP_ID, "口径：营收=含税销售额");
        String events = sse(buffer);
        assertThat(events).contains("tool.result");
        assertThat(events).contains("SUCCEEDED");
    }

    @Test
    @DisplayName("持久化的工具身份缺失时明确失败，不发起任何工具调用")
    void toolStep_shouldFailExplicitlyWhenToolIdentityMissing() throws Exception {
        when(repository.findStepTool(RUN_ID, STEP_ID)).thenReturn(Optional.empty());

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        engine.advanceReadyStep(run(), toolStep(), null, "schema", new SseEventWriter(buffer, objectMapper), null);

        verify(toolAssistant, never()).invoke(any(), any(), any(), any());
        verify(repository).failNonSqlStep(eq(RUN_ID), eq(STEP_ID),
                eq(ErrorCode.TOOL_INVOCATION_INCOMPLETE.name()), anyString());
    }

    @Test
    @DisplayName("Agent 白名单未授权的工具不进入调用")
    void toolStep_shouldRejectToolOutsideAgentWhitelist() throws Exception {
        when(repository.findStepTool(RUN_ID, STEP_ID))
                .thenReturn(Optional.of(new AgentRunRepository.StepTool(TOOL_NAME, TOOL_ARGS_JSON)));
        when(toolPolicy.allowsStrictly(RUN_ID, TOOL_NAME)).thenReturn(false);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        engine.advanceReadyStep(run(), toolStep(), null, "schema", new SseEventWriter(buffer, objectMapper), null);

        verify(toolAssistant, never()).invoke(any(), any(), any(), any());
        verify(repository, never()).startNonSqlStep(anyLong());
        verify(repository).failNonSqlStep(eq(RUN_ID), eq(STEP_ID),
                eq(ErrorCode.TOOL_NOT_IN_AGENT_WHITELIST.name()), anyString());
    }
}
