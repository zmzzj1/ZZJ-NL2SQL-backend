package com.queryforge.application.agent;

import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.application.agent.roles.CodeAnalystAgent;
import com.queryforge.application.agent.roles.RoleAssigner;
import com.queryforge.application.agent.roles.ToolAssistantAgent;
import com.queryforge.application.agent.sql.SqlCandidateSelectionService;
import com.queryforge.application.agent.sql.SqlSelection;
import com.queryforge.application.agent.sql.SqlSelectionRequest;
import com.queryforge.application.analysis.AnalysisService;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.application.report.ReportService;
import com.queryforge.application.tool.AgentRunToolPolicy;
import com.queryforge.application.tool.ToolOutputSanitizer;
import com.queryforge.domain.agent.AgentPlan;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.ApprovalStatus;
import com.queryforge.domain.agent.CreateAgentRunRequest.ToolInvocationRequest;
import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.agent.graph.GraphNode;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.domain.analysis.ChartSchema;
import com.queryforge.domain.knowledge.KnowledgeSearchResponse;
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.domain.query.SqlExecutionException;
import com.queryforge.domain.report.ReportResponse;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.domain.tool.ToolInvocationResult;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.domain.conversation.MessageRole;
import com.queryforge.domain.conversation.MessageStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Agent 执行核心：Manager 路由、闲聊/澄清、显式工具快捷路径、Planner 规划、
 * 步骤推进（SQL 生成/审批/修复）、多步骤续跑与最终总结、会话记忆摘要。
 *
 * <p>本类只负责“怎么执行”；运行生命周期编排、审批幂等裁决与对外契约在
 * {@link AgentRunService}。事实来源始终是 DB；短期状态（Redis）经由
 * {@link AgentPlanStateService} fail-soft 读写。</p>
 *
 * <p>P14-A：所有需要模型的路径都消费 {@link AgentContextAssembler} 一次构造的
 * {@link EffectiveRunContext}，不再忽略构造结果只另取零散字符串。</p>
 *
 * <p>P14-B：SQL 生成统一经 {@link SqlCandidateSelectionService}（SIMPLE 单路径 /
 * COMPLEX、MEDIUM 多候选选优），选中 SQL 再经同一个 {@code ReadOnlySqlGuard} 后才
 * 进入原有审批链；候选不入库、不拆成多个待审批步骤。</p>
 *
 * <p>P14-C：按持久化角色分发执行——{@code DATA_ANALYST} 走原 SQL 链（Guard/审批/修复），
 * {@code CODE_ANALYST} 消费已审批真实行数据在隔离沙箱跑 Python 并落分析工件，
 * {@code REPORT_ASSISTANT} 基于同 Run 真实工件生成安全 HTML 报告。
 * 非 SQL 步骤不生成 SQL、不进 {@code WAITING_APPROVAL}、不发 {@code approval.required}；
 * 跨请求时只按“已审批的原样 SQL”重新鉴权取数，绝不执行模型提出的新 SQL。</p>
 */
@Service
public class AgentStepEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentStepEngine.class);
    private static final int MAX_SUMMARY_CHARS = 500;
    private static final int MAX_ERROR_MESSAGE_CHARS = 300;
    private static final int TOOL_STEP_SEQUENCE = 1;
    private static final int RECENT_TURNS = 6;
    private static final String TOOL_ACTIVITY = "tool-1";
    /** 当前平台唯一支持的 SQL 方言（候选提示词只作说明，安全约束不依赖它）。 */
    private static final String DIALECT_MYSQL = "MySQL 8";
    /** P14-C：Python 代码生成的有界重试次数（含首次）。 */
    private static final int MAX_CODE_ATTEMPTS = 3;
    /** P14-C：进入提示词的真实数据样例行数/字符上限（AnalysisService 另有限行裁剪）。 */
    private static final int PROMPT_SAMPLE_ROWS = 20;
    private static final int PROMPT_SAMPLE_CHARS = 4000;
    private static final String ROLE_DATA_ANALYST = "DATA_ANALYST";
    private static final String ROLE_CODE_ANALYST = "CODE_ANALYST";
    private static final String ROLE_REPORT_ASSISTANT = "REPORT_ASSISTANT";
    /** P14-E：计划中显式携带真实工具身份的工具步骤。 */
    private static final String ROLE_TOOL_ASSISTANT = "TOOL_ASSISTANT";
    /** 模型返回空串时的简洁能力兜底（仅限空响应，不用于伪造失败）。 */
    private static final String CHITCHAT_FALLBACK =
            "我可以基于你已授权的数据源生成只读 SQL（需你确认后执行）、做多步分析与图表，"
                    + "并解释授权知识库中的业务口径。直接描述你的问题即可。";

    private final AgentRunRepository repository;
    private final ConversationService conversationService;
    private final ConnectionService connectionService;
    private final AgentPlanner planner;
    private final AgentSqlAssistant sqlAssistant;
    private final SqlExecutionService executionService;
    private final ReadOnlySqlGuard guard;
    private final AgentProperties properties;
    private final ManagerRouter managerRouter;
    private final AgentGraphStateService graphStateService;
    private final RoleAssigner roleAssigner;
    private final ApprovalSessionStore approvalSessionStore;
    private final AgentContextAssembler contextAssembler;
    private final AgentRunFailureHandler failureHandler;
    private final AgentPlanStateService planState;
    private final AgentRoleRecorder roleRecorder;
    private final AgentLlmCallLimiter llmLimiter;
    private final ToolAssistantAgent toolAssistant;
    private final AgentRunToolPolicy toolPolicy;
    private final ToolOutputSanitizer toolOutputSanitizer;
    private final SqlCandidateSelectionService selectionService;
    private final AgentPythonAssistant pythonAssistant;
    private final CodeAnalystAgent codeAnalyst;
    private final ReportService reportService;
    private final AgentReportComposer reportComposer;
    private final AnalysisService analysisService;

    public AgentStepEngine(
            AgentRunRepository repository,
            ConversationService conversationService,
            ConnectionService connectionService,
            AgentPlanner planner,
            AgentSqlAssistant sqlAssistant,
            SqlExecutionService executionService,
            ReadOnlySqlGuard guard,
            AgentProperties properties,
            ManagerRouter managerRouter,
            AgentGraphStateService graphStateService,
            RoleAssigner roleAssigner,
            ApprovalSessionStore approvalSessionStore,
            AgentContextAssembler contextAssembler,
            AgentRunFailureHandler failureHandler,
            AgentPlanStateService planState,
            AgentRoleRecorder roleRecorder,
            AgentLlmCallLimiter llmLimiter,
            ToolAssistantAgent toolAssistant,
            AgentRunToolPolicy toolPolicy,
            ToolOutputSanitizer toolOutputSanitizer,
            SqlCandidateSelectionService selectionService,
            AgentPythonAssistant pythonAssistant,
            CodeAnalystAgent codeAnalyst,
            ReportService reportService,
            AgentReportComposer reportComposer,
            AnalysisService analysisService) {
        this.repository = repository;
        this.conversationService = conversationService;
        this.connectionService = connectionService;
        this.planner = planner;
        this.sqlAssistant = sqlAssistant;
        this.executionService = executionService;
        this.guard = guard;
        this.properties = properties;
        this.managerRouter = managerRouter;
        this.graphStateService = graphStateService;
        this.roleAssigner = roleAssigner;
        this.approvalSessionStore = approvalSessionStore;
        this.contextAssembler = contextAssembler;
        this.failureHandler = failureHandler;
        this.planState = planState;
        this.roleRecorder = roleRecorder;
        this.llmLimiter = llmLimiter;
        this.toolAssistant = toolAssistant;
        this.toolPolicy = toolPolicy;
        this.toolOutputSanitizer = toolOutputSanitizer;
        this.selectionService = selectionService;
        this.pythonAssistant = pythonAssistant;
        this.codeAnalyst = codeAnalyst;
        this.reportService = reportService;
        this.reportComposer = reportComposer;
        this.analysisService = analysisService;
    }

    /** Manager 分类：使用真实授权上下文；模型侧失败由 ManagerRouter 降级为 MEDIUM。 */
    public ManagerRouter.RouteDecision route(PreparedRun run, ManagerRouter.RoutingContext routingContext,
                                             SseEventWriter writer) throws IOException {
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.runId(), "MANAGER", "route"));
        try {
            ManagerRouter.RouteDecision decision =
                    llmLimiter.count(run.runId(), () -> managerRouter.route(routingContext));
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "MANAGER", "route", "SUCCESS"));
            return decision;
        } catch (RuntimeException ex) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "MANAGER", "route", "FAILED"));
            throw ex;
        }
    }

    /**
     * P14-A：闲聊真实应答。调用当前可用模型生成自然语言文本，持久化为 Run.summary
     * 与会话 ASSISTANT 消息；模型失败/超时不伪造成功，进入 Run.FAILED。
     */
    public void answerChitchat(PreparedRun run, SseEventWriter writer, EffectiveRunContext context)
            throws IOException {
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.runId(), "MANAGER", "chitchat"));
        String answer;
        try {
            answer = llmLimiter.count(run.runId(), () -> sqlAssistant.chitchatAnswer(
                    run.question(), context == null ? "" : context.historyForClassifier()));
        } catch (RuntimeException ex) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "MANAGER", "chitchat", "FAILED"));
            ErrorCode code = ex instanceof ApiException api ? api.getErrorCode() : ErrorCode.LLM_UPSTREAM_ERROR;
            failureHandler.failRunWithEvent(run.runId(), code.name(), "对话生成失败，请稍后重试", writer);
            return;
        }
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.runId(), "MANAGER", "chitchat", "SUCCESS"));
        roleRecorder.record(run, "MANAGER");
        String reply = answer == null ? "" : answer.trim();
        if (reply.isEmpty()) {
            reply = CHITCHAT_FALLBACK;
        }
        // 图 ROUTING→COMPLETED（仅在真实成功回答后进入终态）。
        graphStateService.enter(run.runId(), GraphNode.COMPLETED, writer);
        repository.updateRunStatus(run.runId(), RunStatus.COMPLETED, reply, null, null);
        conversationService.appendMessage(
                run.conversationId(), MessageRole.ASSISTANT, reply, MessageStatus.COMPLETED);
        writer.event("run.completed", new AgentStreamEvents.RunCompleted(run.runId(), reply));
        // 只对成功完成的回答更新会话摘要，不把失败写成成功记忆。
        updateSessionMemory(run, reply);
    }

    /**
     * P14-A：用户显式选择工具的快捷路径。跳过 Manager 分类与 Planner，
     * 建立单个非 SQL 工具步骤，经角色能力 + ToolRegistry + Agent 工具白名单双层授权后
     * 执行恰好一次；未授权/参数错误/执行失败都明确失败，不转为绕过白名单的调用。
     */
    public void executeToolInvocation(PreparedRun run, SseEventWriter writer) throws IOException {
        ToolInvocationRequest request = run.toolInvocation();
        String toolName = request.toolName();
        String objective = "调用工具 " + toolName;
        roleRecorder.record(run, "MANAGER");

        graphStateService.enter(run.runId(), GraphNode.TOOL_INVOCATION, writer);
        Long stepId = repository.insertStep(
                run.runId(), TOOL_STEP_SEQUENCE, objective, null, "TOOL_ASSISTANT");
        writer.event("agent.selected", new AgentStreamEvents.AgentSelected(
                run.runId(), TOOL_STEP_SEQUENCE, objective, "TOOL_ASSISTANT"));
        writer.event("plan.ready", new AgentStreamEvents.PlanReady(
                run.runId(),
                List.of(new AgentStreamEvents.PlanStep(stepId, TOOL_STEP_SEQUENCE, objective))));
        planState.save(run.runId(), repository.findSteps(run.runId()));

        // 双层授权之一：当前 Run 的 Agent 工具白名单（配置损坏时拒绝，不降级放行）。
        if (!toolPolicy.allowsStrictly(run.runId(), toolName)) {
            failToolStep(run, stepId, toolName, ErrorCode.TOOL_NOT_IN_AGENT_WHITELIST,
                    "当前 Agent 配置未授权该工具：" + toolName, writer);
            return;
        }

        writer.event("agent.started", new AgentStreamEvents.AgentStarted(
                run.runId(), "TOOL_ASSISTANT", TOOL_ACTIVITY));
        writer.event("step.started", new AgentStreamEvents.StepStarted(stepId, TOOL_STEP_SEQUENCE));

        // 原子进入执行态：已被处理过（SSE 重连/前端重试）时不二次执行工具。
        int claimed = repository.startNonSqlStep(stepId);
        if (claimed == 0) {
            replayToolStep(run, stepId, toolName, writer);
            return;
        }

        ToolInvocation invocation = new ToolInvocation(
                run.userId(), toolName, run.conversationId(), run.runId(), stepId, null, null);
        ToolInvocationResult result;
        try {
            result = toolAssistant.invoke(invocation, request.args(), null,
                    (attempt, diagnostic) -> {
                        try {
                            writer.event("tool.repair", new AgentStreamEvents.ToolRepair(
                                    run.runId(), stepId, toolName, attempt, diagnostic));
                        } catch (IOException ignored) {
                            // 修复元数据为可选信息，发送失败不阻断主流程
                        }
                    });
        } catch (ApiException ex) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "TOOL_ASSISTANT", TOOL_ACTIVITY, "FAILED"));
            failToolStep(run, stepId, toolName, ex.getErrorCode(), ex.getMessage(), writer);
            return;
        } catch (RuntimeException ex) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "TOOL_ASSISTANT", TOOL_ACTIVITY, "FAILED"));
            failToolStep(run, stepId, toolName, ErrorCode.TOOL_EXECUTION_ERROR, "工具执行失败", writer);
            return;
        }

        String summary = toolOutputSanitizer.summarize(result);
        repository.completeNonSqlStep(stepId, summary);
        repository.updateRunStatus(run.runId(), RunStatus.COMPLETED, summary, null, null);
        conversationService.appendMessage(
                run.conversationId(), MessageRole.ASSISTANT, summary, MessageStatus.COMPLETED);
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.runId(), "TOOL_ASSISTANT", TOOL_ACTIVITY, "SUCCESS"));
        roleRecorder.record(run, "TOOL_ASSISTANT");
        writer.event("step.succeeded", new AgentStreamEvents.StepSucceeded(
                stepId, 0, false, summary));
        writer.event("tool.result", new AgentStreamEvents.ToolResult(
                run.runId(), stepId, toolName, "SUCCEEDED", summary));
        graphStateService.enter(run.runId(), GraphNode.COMPLETED, writer);
        writer.event("run.completed", new AgentStreamEvents.RunCompleted(run.runId(), summary));
        approvalSessionStore.removeRun(run.runId());
        planState.clear(run.runId());
        updateSessionMemory(run, summary);
    }

    /** 步骤已被处理过：只回放既有状态，绝不二次执行工具。 */
    private void replayToolStep(PreparedRun run, Long stepId, String toolName, SseEventWriter writer)
            throws IOException {
        AgentStep existing = repository.findStep(run.runId(), stepId).orElse(null);
        String status = existing == null ? "UNKNOWN" : existing.status().name();
        String summary = existing != null && existing.resultSummary() != null
                ? existing.resultSummary()
                : "该工具调用已处理过，不重复执行";
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.runId(), "TOOL_ASSISTANT", TOOL_ACTIVITY,
                StepStatus.SUCCEEDED.name().equals(status) ? "SUCCESS" : "FAILED"));
        writer.event("tool.result", new AgentStreamEvents.ToolResult(
                run.runId(), stepId, toolName, status, summary));
        graphStateService.enter(run.runId(), GraphNode.COMPLETED, writer);
        writer.event("run.completed", new AgentStreamEvents.RunCompleted(run.runId(), summary));
    }

    private void failToolStep(PreparedRun run, Long stepId, String toolName, ErrorCode code, String message,
                              SseEventWriter writer) throws IOException {
        String safe = safeMessage(message);
        repository.failNonSqlStep(stepId, code.name(), safe);
        repository.updateRunStatus(run.runId(), RunStatus.FAILED, null, code.name(), safe);
        graphStateService.enter(run.runId(), GraphNode.FAILED, writer);
        writer.event("step.failed", new AgentStreamEvents.StepFailed(stepId, code.name(), safe));
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.runId(), "TOOL_ASSISTANT", TOOL_ACTIVITY, "FAILED"));
        writer.event("tool.result", new AgentStreamEvents.ToolResult(
                run.runId(), stepId, toolName, "FAILED", safe));
        writer.event("run.failed", new AgentStreamEvents.RunFailed(run.runId(), code.name(), safe));
        approvalSessionStore.removeRun(run.runId());
        planState.clear(run.runId());
    }

    public AgentPlan plan(PreparedRun run, String schema, SseEventWriter writer, EffectiveRunContext context)
            throws IOException {
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.runId(), "PLANNER", "plan"));
        try {
            AgentPlan plan = llmLimiter.count(run.runId(), () -> planner.plan(
                    run.question(), schema, context == null ? "" : context.effectiveContext()));
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "PLANNER", "plan", "SUCCESS"));
            roleRecorder.record(run, "PLANNER");
            if (!plan.answerable()) {
                failureHandler.failRunWithEvent(run.runId(), "AGENT_UNANSWERABLE", plan.reason(), writer);
                return null;
            }
            return plan;
        } catch (ApiException ex) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "PLANNER", "plan", "FAILED"));
            failureHandler.failRunWithEvent(run.runId(), "AGENT_PLAN_INVALID", ex.getMessage(), writer);
            return null;
        }
    }

    public void executePlan(PreparedRun run, String schema, AgentPlan plan, SseEventWriter writer,
                            EffectiveRunContext context) throws IOException {
        List<AgentStep> steps = persistPlan(run, plan, writer);
        startFirstReadyStep(run, schema, writer, context, steps);
    }

    /**
     * P14-D：仅持久化计划步骤（PENDING）+ 发出 plan.ready + 保存短期快照；不启动执行。
     * 供计划级 Gate 在进入 WAITING_PLAN_REVIEW 之前把计划落盘，使刷新/恢复能展示等待中的计划。
     */
    public List<AgentStep> persistPlan(PreparedRun run, AgentPlan plan, SseEventWriter writer) throws IOException {
        List<AgentStep> steps = createSteps(run.runId(), plan);
        for (AgentStep step : steps) {
            String role = step.agentRole() == null ? ROLE_DATA_ANALYST : step.agentRole();
            writer.event("agent.selected", new AgentStreamEvents.AgentSelected(
                    run.runId(), step.sequence(), step.objective(), role));
        }
        writer.event("plan.ready", new AgentStreamEvents.PlanReady(
                run.runId(),
                steps.stream()
                        .map(step -> new AgentStreamEvents.PlanStep(step.id(), step.sequence(), step.objective()))
                        .toList()));
        // P12 S4：计划短期状态入 Redis（DB agent_steps 仍为事实来源）。
        planState.save(run.runId(), steps);
        return steps;
    }

    /**
     * P14-D：计划批准后/重规划后，从已持久化的 PENDING 步骤中启动第一个就绪步骤。
     * 跨请求恢复时由调用方先用服务端来源重建上下文，绝不信任客户端内存中的压缩结果。
     */
    public void resumePersistedPlan(PreparedRun run, String schema, SseEventWriter writer, EffectiveRunContext context)
            throws IOException {
        List<AgentStep> steps = repository.findSteps(run.runId());
        startFirstReadyStep(run, schema, writer, context, steps);
    }

    private void startFirstReadyStep(PreparedRun run, String schema, SseEventWriter writer,
                                    EffectiveRunContext context, List<AgentStep> steps) throws IOException {
        Optional<AgentStep> first = nextReadyStep(steps);
        if (first.isEmpty()) {
            failureHandler.failRunWithEvent(run.runId(), "AGENT_PLAN_INVALID", "计划没有可执行的步骤", writer);
            return;
        }
        AgentStepExecutionInputs inputs = inputsFor(run, first.get(), null, context);
        advanceReadyStep(run, first.get(), inputs, schema, writer, context);
    }

    /**
     * P14-C：统一推进入口。按持久化角色分发：
     * DATA_ANALYST/SQL_AGENT → 原 SQL 链；CODE_ANALYST → 隔离 Python 分析；
     * REPORT_ASSISTANT → 基于真实工件生成报告；P14-E 的 TOOL_ASSISTANT → 受控工具调用。
     */
    public void advanceReadyStep(PreparedRun run, AgentStep step, AgentStepExecutionInputs inputs, String schema,
                                 SseEventWriter writer, EffectiveRunContext context) throws IOException {
        String role = step.agentRole() == null ? "" : step.agentRole();
        switch (role) {
            case ROLE_CODE_ANALYST -> advanceCodeStep(run, step, inputs, writer, context);
            case ROLE_REPORT_ASSISTANT -> advanceReportStep(run, step, inputs, writer, context);
            case ROLE_TOOL_ASSISTANT -> advanceToolStep(run, step, writer, context);
            default -> advance(run, step, schema, writer, context);
        }
    }

    /**
     * P14-E：计划内工具步骤的受控执行。工具身份与参数只来自该步骤持久化的事实
     * （{@code agent_steps.tool_name/tool_args}），不接受自由文字或模型回复猜测；
     * 仍走 Agent 工具白名单 + ToolAssistantAgent（内置注册表 / 当前用户 MCP）+ 参数 schema 校验 + 审计。
     */
    private void advanceToolStep(PreparedRun run, AgentStep step, SseEventWriter writer,
                                 EffectiveRunContext context) throws IOException {
        AgentRunRepository.StepTool tool = repository.findStepTool(run.runId(), step.id()).orElse(null);
        if (tool == null || tool.toolName() == null || tool.toolName().isBlank()) {
            failNonSqlStepAndRun(run, step, ErrorCode.TOOL_INVOCATION_INCOMPLETE,
                    "计划中的工具步骤缺少可执行工具信息", writer, ROLE_TOOL_ASSISTANT);
            return;
        }
        String toolName = tool.toolName();
        if (!toolPolicy.allowsStrictly(run.runId(), toolName)) {
            failNonSqlStepAndRun(run, step, ErrorCode.TOOL_NOT_IN_AGENT_WHITELIST,
                    "当前 Agent 配置未授权该工具：" + toolName, writer, ROLE_TOOL_ASSISTANT);
            return;
        }
        graphStateService.enter(run.runId(), GraphNode.TOOL_INVOCATION, writer);
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(
                run.runId(), ROLE_TOOL_ASSISTANT, TOOL_ACTIVITY));
        writer.event("step.started", new AgentStreamEvents.StepStarted(step.id(), step.sequence()));
        // 与显式工具路径同一语义：只有仍为 PENDING 的步骤被领取，重复触发不二次调用。
        int claimed = repository.startNonSqlStep(step.id());
        if (claimed == 0) {
            replayNonSqlStep(run, step, writer, context);
            return;
        }
        ToolInvocation invocation = new ToolInvocation(
                run.userId(), toolName, run.conversationId(), run.runId(), step.id(), null, null);
        ToolInvocationResult result;
        try {
            result = toolAssistant.invoke(invocation, parseToolArgs(tool.toolArgsJson()), null,
                    (attempt, diagnostic) -> {
                        try {
                            writer.event("tool.repair", new AgentStreamEvents.ToolRepair(
                                    run.runId(), step.id(), toolName, attempt, diagnostic));
                        } catch (IOException ignored) {
                            // 修复元数据为可选信息，发送失败不阻断主流程
                        }
                    });
        } catch (ApiException ex) {
            failNonSqlStepAndRun(run, step, ex.getErrorCode(), ex.getMessage(), writer, ROLE_TOOL_ASSISTANT);
            return;
        } catch (RuntimeException ex) {
            failNonSqlStepAndRun(run, step, ErrorCode.TOOL_EXECUTION_ERROR, "工具执行失败", writer,
                    ROLE_TOOL_ASSISTANT);
            return;
        }
        String summary = toolOutputSanitizer.summarize(result);
        repository.completeNonSqlStep(step.id(), summary);
        roleRecorder.record(run, ROLE_TOOL_ASSISTANT);
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.runId(), ROLE_TOOL_ASSISTANT, TOOL_ACTIVITY, "SUCCESS"));
        writer.event("step.succeeded", new AgentStreamEvents.StepSucceeded(step.id(), 0, false, summary));
        writer.event("tool.result", new AgentStreamEvents.ToolResult(
                run.runId(), step.id(), toolName, "SUCCEEDED", summary));
        // 下游步骤只能消费受控摘要，工具返回的原文不会进入下游指令。
        advanceAfterNonSqlStep(run, step, writer, context);
    }

    /** 计划内工具参数：必须是 JSON object；损坏时不传参猜测，按空对象交给 schema 校验拒绝。 */
    private static java.util.Map<String, Object> parseToolArgs(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (IOException ex) {
            log.warn("agent_tool_step_args_corrupt length={}", json.length());
            return java.util.Map.of();
        }
    }

    /** SQL 步骤路径：生成/选优 → Guard → 待审批（P14-B 唯一 SQL 生成入口在此）。 */
    public void advance(PreparedRun run, AgentStep step, String schema, SseEventWriter writer,
                        EffectiveRunContext context) throws IOException {
        RunPrompts prompts = resolvePrompts(run, context, writer);
        String knowledge = prompts.knowledge();
        String memoryContext = prompts.memoryContext();
        String skillGuidance = prompts.skillGuidance();
        String effectiveContext = prompts.effectiveContext();

        graphStateService.enter(run.runId(), GraphNode.SQL_GENERATION, writer);
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.runId(), "SQL_AGENT", "step-" + step.sequence()));
        writer.event("step.started", new AgentStreamEvents.StepStarted(step.id(), step.sequence()));
        SqlSelection selection;
        String sql;
        try {
            // P14-B：唯一 SQL 生成入口。SIMPLE 单次生成；COMPLEX/MEDIUM 走多候选选优。
            selection = selectionService.generateAndSelect(selectionRequest(run, step, schema,
                    knowledge + memoryContext, skillGuidance, effectiveContext, run.config()));
            // 候选验证不是最终执行安全豁免：选中 SQL 再走一次同一个严格 Guard。
            sql = guard.validate(selection.selectedSql(), run.database());
        } catch (ApiException ex) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "SQL_AGENT", "step-" + step.sequence(), "FAILED"));
            failureHandler.failStepAndRun(run.runId(), step.id(), ex.getErrorCode(), ex.getMessage(), writer);
            return;
        } catch (RuntimeException ex) {
            // 全部候选生成/验证失败等未预期异常：统一失败收敛，不留等待审批孤儿。
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "SQL_AGENT", "step-" + step.sequence(), "FAILED"));
            failureHandler.failStepAndRun(
                    run.runId(), step.id(), ErrorCode.AGENT_STEP_FAILED, "SQL 生成失败，请稍后重试", writer);
            return;
        }
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.runId(), "SQL_AGENT", "step-" + step.sequence(), "SUCCESS"));
        roleRecorder.record(run, "SQL_AGENT");
        if (sql.isBlank()) {
            failureHandler.failStepAndRun(
                    run.runId(), step.id(), ErrorCode.AGENT_PLAN_INVALID, "模型未生成可执行的 SQL", writer);
            return;
        }
        // P14-B：多候选选优摘要（脱敏计数与分值）在 step.sql_ready 之前发送；单路径不发。
        if (selection.multiCandidate()) {
            writer.event("step.sql_candidates_evaluated", new AgentStreamEvents.SqlCandidatesEvaluated(
                    step.id(), selection.mode(), selection.totalCandidates(), selection.validCandidates(),
                    selection.selectedCandidateIndex(), selection.ruleScore(), selection.llmScore(),
                    selection.compositeScore(), selection.ruleFallback()));
        }

        int nextVersion = step.sqlVersion() + 1;
        repository.setStepSql(step.id(), sql, nextVersion, step.repairCount());
        repository.updateRunStatus(run.runId(), RunStatus.WAITING_APPROVAL, null, null, null);
        graphStateService.enter(run.runId(), GraphNode.WAITING_SQL_APPROVAL, writer);
        graphStateService.markWaitingApproval(run.runId(), GraphNode.WAITING_SQL_APPROVAL, writer);
        writer.event("step.sql_ready", new AgentStreamEvents.StepSqlReady(step.id(), sql, nextVersion));
        writer.event("approval.required", new AgentStreamEvents.ApprovalRequired(step.id(), nextVersion));
        // P12 S4 场景二：登记待审批短期授权上下文（RLock 保护的领取见 prepareApproval）。
        approvalSessionStore.register(run.runId(), step.id(), nextVersion, run.database());
    }

    // ------------------------------------------------------------------ P14-C：Code 步骤

    /**
     * P14-C：Code 步骤。消费已审批 SQL 的真实行数据 → 生成受控 Python → 隔离沙箱执行 →
     * 校验 output.json → 落分析工件 → 解释真实结果 → 推进下一步。
     * 任何阶段失败都不跳过后续阶段冒充全链完成。
     */
    private void advanceCodeStep(PreparedRun run, AgentStep step, AgentStepExecutionInputs inputs,
                                 SseEventWriter writer, EffectiveRunContext context) throws IOException {
        graphStateService.enter(run.runId(), GraphNode.CODE_ANALYSIS, writer);
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(
                run.runId(), ROLE_CODE_ANALYST, "step-" + step.sequence()));
        writer.event("step.started", new AgentStreamEvents.StepStarted(step.id(), step.sequence()));

        // 原子领取：SSE 重连/前端双击/详情刷新都不能让已成功步骤二次执行。
        int claimed = repository.claimNonSqlStep(run.runId(), step.id());
        if (claimed == 0) {
            replayNonSqlStep(run, step, writer, context);
            return;
        }
        writer.event("analysis.started", new AgentStreamEvents.AnalysisStarted(
                run.runId(), step.id(), ROLE_CODE_ANALYST));

        // 可信来源：同请求交接或跨请求恢复；缺失即明确失败，绝不用空数组冒充数据。
        AuthorizedSqlInput input;
        try {
            input = resolveAuthorizedInput(run, step, inputs);
        } catch (ApiException ex) {
            failCodeStep(run, step, ex.getErrorCode(), ex.getMessage(), writer);
            return;
        } catch (RuntimeException ex) {
            log.warn("agent_code_input_failed runId={} stepId={} message={}", run.runId(), step.id(), ex.getMessage());
            failCodeStep(run, step, ErrorCode.AGENT_STEP_FAILED, "上游数据获取失败，无法开始分析", writer);
            return;
        }
        if (input == null || input.result() == null) {
            failCodeStep(run, step, ErrorCode.AGENT_STEP_FAILED,
                    "没有可证实的已授权上游结果，拒绝执行分析", writer);
            return;
        }

        RunPrompts prompts = resolvePrompts(run, context, writer);
        String code = generatePythonWithRetry(run, step, input, prompts);
        if (code == null) {
            failCodeStep(run, step, ErrorCode.AGENT_STEP_FAILED, "模型未生成可用的 Python 代码", writer);
            return;
        }

        AnalysisRunResponse analysis;
        try {
            analysis = codeAnalyst.analyzeApprovedResult(run.userId(), input, code, null);
        } catch (ApiException ex) {
            writer.event("analysis.failed", new AgentStreamEvents.AnalysisFailed(
                    run.runId(), step.id(), ex.getErrorCode().name(), safeMessage(ex.getMessage())));
            failCodeStep(run, step, ex.getErrorCode(), ex.getMessage(), writer);
            return;
        } catch (RuntimeException ex) {
            log.warn("agent_code_execute_failed runId={} stepId={} message={}", run.runId(), step.id(), ex.getMessage());
            writer.event("analysis.failed", new AgentStreamEvents.AnalysisFailed(
                    run.runId(), step.id(), ErrorCode.ANALYSIS_EXECUTION_ERROR.name(), "Python 分析执行失败"));
            failCodeStep(run, step, ErrorCode.ANALYSIS_EXECUTION_ERROR, "Python 分析执行失败", writer);
            return;
        }

        String summary = interpretAnalysis(run, step, input, analysis, prompts);
        int completed = repository.completeCodeStep(
                run.runId(), step.id(), analysis.id(), truncate(summary, MAX_SUMMARY_CHARS));
        if (completed == 0) {
            // 运行已被取消/终结：迟到结果不覆写终态，也不冒充成功。
            log.warn("agent_code_late_completion_ignored runId={} stepId={}", run.runId(), step.id());
            writer.event("analysis.failed", new AgentStreamEvents.AnalysisFailed(
                    run.runId(), step.id(), "AGENT_STATE_CONFLICT", "运行已结束，分析结果未接入步骤"));
            return;
        }
        // 成功事件只在工件真实落库后发送。
        writer.event("analysis.succeeded", new AgentStreamEvents.AnalysisSucceeded(
                run.runId(), step.id(), analysis.id(), analysis.charts().size(),
                truncate(summary, MAX_ERROR_MESSAGE_CHARS), analysis.durationMs()));
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.runId(), ROLE_CODE_ANALYST, "step-" + step.sequence(), "SUCCESS"));
        roleRecorder.record(run, ROLE_CODE_ANALYST);
        writer.event("step.succeeded", new AgentStreamEvents.StepSucceeded(
                step.id(), analysis.inputRowCount(), false, truncate(summary, MAX_SUMMARY_CHARS)));
        advanceAfterNonSqlStep(run, step, writer, context);
    }

    /** 有界生成：仅“空输出/超长代码”可重试；每次模型调用都计入 Run 预算。 */
    private String generatePythonWithRetry(PreparedRun run, AgentStep step, AuthorizedSqlInput input,
                                           RunPrompts prompts) {
        String columnsInfo = buildColumnInfo(input.result());
        String dataSummary = buildDataSummary(input.result());
        String instructions = input.refetched()
                ? "注意：本次输入是跨请求重新获取的较新数据，请只基于本次输入分析，并在 summary 中说明数据再次获取。"
                : "";
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            String code = llmLimiter.count(run.runId(), () -> pythonAssistant.generateCode(
                    run.runId(), step.objective(), columnsInfo, dataSummary,
                    prompts.effectiveContext(), instructions));
            if (!code.isBlank()) {
                return code;
            }
            log.warn("agent_python_attempt_empty runId={} stepId={} attempt={}", run.runId(), step.id(), attempt);
        }
        return null;
    }

    /** 解释真实分析结果；模型不可用/预算不足时回退确定性摘要，不伪装解释成功。 */
    private String interpretAnalysis(PreparedRun run, AgentStep step, AuthorizedSqlInput input,
                                     AnalysisRunResponse analysis, RunPrompts prompts) {
        String deterministic = deterministicAnalysisSummary(input, analysis);
        String interpretation;
        try {
            interpretation = llmLimiter.tryCount(run.runId(), () -> pythonAssistant.interpretResult(
                    run.runId(), run.question(), step.objective(), analysis.charts(), analysis.summary(), ""));
        } catch (RuntimeException ex) {
            log.warn("agent_python_interpret_failed runId={} stepId={} message={}",
                    run.runId(), step.id(), ex.getMessage());
            interpretation = null;
        }
        if (interpretation == null || interpretation.isBlank()) {
            return deterministic;
        }
        return interpretation;
    }

    private static String deterministicAnalysisSummary(AuthorizedSqlInput input, AnalysisRunResponse analysis) {
        StringBuilder builder = new StringBuilder();
        if (input.refetched()) {
            builder.append("（数据为再次获取的较新结果）");
        }
        builder.append("分析运行 #").append(analysis.id())
                .append("：引擎 ").append(analysis.engine())
                .append("，输入 ").append(analysis.inputRowCount()).append(" 行，产出 ")
                .append(analysis.charts().size()).append(" 个图表。");
        if (analysis.summary() != null && !analysis.summary().isBlank()) {
            builder.append(analysis.summary());
        }
        return truncate(builder.toString(), MAX_SUMMARY_CHARS);
    }

    private void failCodeStep(PreparedRun run, AgentStep step, ErrorCode code, String message,
                              SseEventWriter writer) throws IOException {
        failNonSqlStepAndRun(run, step, code, message, writer, ROLE_CODE_ANALYST);
    }

    // ------------------------------------------------------------------ P14-C：Report 步骤

    /**
     * P14-C：Report 步骤。聚合同一次 Run 内已成功的分析工件（或仅有 SQL 时用
     * BuiltinAnalyzer 从真实受限数据生成 typed charts），合成导语，渲染安全 HTML 并落库。
     * 没有真实数据时明确失败，不生成占位图表。
     */
    private void advanceReportStep(PreparedRun run, AgentStep step, AgentStepExecutionInputs inputs,
                                   SseEventWriter writer, EffectiveRunContext context) throws IOException {
        graphStateService.enter(run.runId(), GraphNode.REPORT_GENERATION, writer);
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(
                run.runId(), ROLE_REPORT_ASSISTANT, "step-" + step.sequence()));
        writer.event("step.started", new AgentStreamEvents.StepStarted(step.id(), step.sequence()));

        int claimed = repository.claimNonSqlStep(run.runId(), step.id());
        if (claimed == 0) {
            replayNonSqlStep(run, step, writer, context);
            return;
        }

        List<AgentStep> steps = repository.findSteps(run.runId());
        List<Long> analysisIds = steps.stream()
                .filter(item -> ROLE_CODE_ANALYST.equals(item.agentRole()))
                .filter(item -> item.status() == StepStatus.SUCCEEDED && item.analysisRunId() != null)
                .map(AgentStep::analysisRunId)
                .toList();

        // 仅已确认 SQL、尚无分析工件：用受信任内置分析器从真实受限数据生成 typed charts。
        if (analysisIds.isEmpty()) {
            try {
                AuthorizedSqlInput input = resolveAuthorizedInput(run, step, inputs);
                if (input == null || input.result() == null) {
                    throw new ApiException(ErrorCode.AGENT_STEP_FAILED, HttpStatus.UNPROCESSABLE_ENTITY,
                            "没有可用的真实结果，无法生成报告");
                }
                AnalysisRunResponse builtin = codeAnalyst.analyzeApprovedResult(run.userId(), input, null, null);
                analysisIds = List.of(builtin.id());
            } catch (ApiException ex) {
                failNonSqlStepAndRun(run, step, ex.getErrorCode(), ex.getMessage(), writer, ROLE_REPORT_ASSISTANT);
                return;
            } catch (RuntimeException ex) {
                log.warn("agent_report_prepare_failed runId={} stepId={} message={}",
                        run.runId(), step.id(), ex.getMessage());
                failNonSqlStepAndRun(run, step, ErrorCode.AGENT_STEP_FAILED, "报告数据准备失败", writer,
                        ROLE_REPORT_ASSISTANT);
                return;
            }
        }

        String narrative = composeNarrative(run, steps, analysisIds);
        ReportResponse report;
        try {
            report = reportService.createForAgentRun(
                    run.userId(), run.runId(), step.id(), analysisIds, reportTitle(run), narrative);
        } catch (ApiException ex) {
            failNonSqlStepAndRun(run, step, ex.getErrorCode(), ex.getMessage(), writer, ROLE_REPORT_ASSISTANT);
            return;
        } catch (RuntimeException ex) {
            log.warn("agent_report_failed runId={} stepId={} message={}", run.runId(), step.id(), ex.getMessage());
            failNonSqlStepAndRun(run, step, ErrorCode.AGENT_STEP_FAILED, "报告生成失败", writer,
                    ROLE_REPORT_ASSISTANT);
            return;
        }

        String summary = truncate("报告《" + report.title() + "》已生成（#" + report.id() + "）", MAX_SUMMARY_CHARS);
        int completed = repository.completeReportStep(run.runId(), step.id(), report.id(), summary);
        if (completed == 0) {
            log.warn("agent_report_late_completion_ignored runId={} stepId={}", run.runId(), step.id());
            return;
        }
        writer.event("report.ready", new AgentStreamEvents.ReportReady(
                run.runId(), step.id(), report.id(), report.title()));
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.runId(), ROLE_REPORT_ASSISTANT, "step-" + step.sequence(), "SUCCESS"));
        roleRecorder.record(run, ROLE_REPORT_ASSISTANT);
        writer.event("step.succeeded", new AgentStreamEvents.StepSucceeded(step.id(), 0, false, summary));
        advanceAfterNonSqlStep(run, step, writer, context);
    }

    /** 报告导语：材料全部来自真实工件；模型不可用时返回 null（报告只呈现真实工件）。 */
    private String composeNarrative(PreparedRun run, List<AgentStep> steps, List<Long> analysisIds) {
        String sqlFacts = steps.stream()
                .filter(item -> item.status() == StepStatus.SUCCEEDED && item.resultSummary() != null
                        && !ROLE_CODE_ANALYST.equals(item.agentRole())
                        && !ROLE_REPORT_ASSISTANT.equals(item.agentRole()))
                .map(item -> "步骤 " + item.sequence() + "：" + item.resultSummary())
                .collect(Collectors.joining("\n"));
        StringBuilder analysisFacts = new StringBuilder();
        StringBuilder chartDigest = new StringBuilder();
        for (Long analysisId : analysisIds) {
            AnalysisRunResponse analysis;
            try {
                analysis = analysisService.detail(run.userId(), analysisId);
            } catch (RuntimeException ex) {
                log.warn("agent_report_analysis_read_failed runId={} analysisId={} message={}",
                        run.runId(), analysisId, ex.getMessage());
                continue;
            }
            analysisFacts.append("分析运行 #").append(analysis.id())
                    .append("：").append(analysis.summary() == null ? "" : analysis.summary()).append("\n");
            for (ChartSchema.TypedChart chart : analysis.charts()) {
                chartDigest.append(chartKind(chart)).append(" / ").append(chartTitle(chart))
                        .append(" / 来源 ").append(chartSource(chart)).append("\n");
            }
        }
        if (analysisFacts.isEmpty() && sqlFacts.isBlank()) {
            return null;
        }
        try {
            return llmLimiter.tryCount(run.runId(), () -> reportComposer.compose(
                    run.runId(), run.question(), sqlFacts, analysisFacts.toString(), chartDigest.toString()));
        } catch (RuntimeException ex) {
            log.warn("agent_report_compose_failed runId={} message={}", run.runId(), ex.getMessage());
            return null;
        }
    }

    private static String reportTitle(PreparedRun run) {
        String question = run.question() == null ? "" : run.question().trim();
        return question.isEmpty() ? "Agent 数据分析报告" : truncate(question, 120);
    }

    // ------------------------------------------------------------------ P14-C：可信交接与推进

    /**
     * P14-C：审批执行结果 → {@link ApprovedSqlOutcome}。真实行数据只在本进程内交给
     * 下游步骤，不入 SSE、不落库、不跨用户流转。
     */
    public ApprovedSqlOutcome executeAndContinue(PreparedApproval approval, SseEventWriter writer)
            throws IOException {
        AgentRun run = approval.run();
        AgentStep step = approval.step();
        graphStateService.enter(run.id(), GraphNode.SQL_EXECUTION, writer);
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.id(), "SQL_AGENT", "step-" + step.sequence()));
        writer.event("step.started", new AgentStreamEvents.StepStarted(step.id(), step.sequence()));
        QueryResultResponse result = executionService.execute(
                approval.userId(),
                new ExecuteSqlRequest(run.connectionId(), run.conversationId(), step.sql()),
                run.id(),
                step.id());
        String digest = digest(result);
        repository.completeStep(step.id(), result.rowCount(), result.truncated(), digest);
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.id(), "SQL_AGENT", "step-" + step.sequence(), "SUCCESS"));
        writer.event("step.succeeded", new AgentStreamEvents.StepSucceeded(
                step.id(), result.rowCount(), result.truncated(), digest));
        roleRecorder.record(run, "SQL_AGENT");
        return new ApprovedSqlOutcome(
                run.id(), step.id(), step.sqlVersion(), approval.userId(),
                run.connectionId(), run.conversationId(), approval.database(), result);
    }

    public void handleExecutionFailure(PreparedApproval approval, SqlExecutionException ex,
                                       SseEventWriter writer) throws IOException {
        AgentRun run = approval.run();
        AgentStep step = approval.step();
        if (!ex.isFixable() || step.repairCount() >= properties.maxRepairs()) {
            failureHandler.failStepAndRun(run.id(), step.id(), ex.getErrorCode(), ex.getMessage(), writer);
            return;
        }

        int attempt = step.repairCount() + 1;
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.id(), "FIXER", "step-" + step.sequence()));
        writer.event("step.repairing", new AgentStreamEvents.StepRepairing(
                step.id(), attempt, ex.getErrorCode().name()));
        String validated;
        try {
            String schema = connectionService.schemaContext(approval.userId(), run.connectionId());
            String fixed = llmLimiter.count(run.id(), () ->
                    sqlAssistant.fixSql(step.sql(), ex.getMessage(), step.objective(), schema));
            validated = guard.validate(fixed, approval.database());
        } catch (RuntimeException fixError) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.id(), "FIXER", "step-" + step.sequence(), "FAILED"));
            ErrorCode code = fixError instanceof ApiException api
                    ? api.getErrorCode()
                    : ErrorCode.AGENT_STEP_FAILED;
            failureHandler.failStepAndRun(run.id(), step.id(), code, "自动修复失败，已终止本次运行", writer);
            return;
        }
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.id(), "FIXER", "step-" + step.sequence(), "SUCCESS"));
        roleRecorder.record(run, "FIXER");
        if (validated.isBlank()) {
            failureHandler.failStepAndRun(
                    run.id(), step.id(), ErrorCode.AGENT_PLAN_INVALID, "修复后的 SQL 为空，运行终止", writer);
            return;
        }
        repository.setStepSql(step.id(), validated, step.sqlVersion() + 1, attempt);
        repository.updateRunStatus(run.id(), RunStatus.WAITING_APPROVAL, null, null, null);
        // AR-01-FB01：修复后必须回到 WAITING_SQL_APPROVAL，否则 Graph 停留在 SQL_EXECUTION，
        // 与 RunStatus=WAITING_APPROVAL 不一致，刷新详情也无法恢复待审批轨迹。
        graphStateService.enter(run.id(), GraphNode.WAITING_SQL_APPROVAL, writer);
        graphStateService.markWaitingApproval(run.id(), GraphNode.WAITING_SQL_APPROVAL, writer);
        writer.event("step.sql_ready", new AgentStreamEvents.StepSqlReady(
                step.id(), validated, step.sqlVersion() + 1));
        writer.event("approval.required", new AgentStreamEvents.ApprovalRequired(
                step.id(), step.sqlVersion() + 1));
        // P12 S4 场景二：修复后重新待审批，换发新版本令牌。
        approvalSessionStore.register(run.id(), step.id(), step.sqlVersion() + 1, approval.database());
    }

    /**
     * P14-C：SQL 步骤成功（或幂等重放）后推进下一个就绪步骤。
     * {@code outcome} 为空表示跨请求恢复：下游只按已审批原样 SQL 重新鉴权取数。
     */
    public void advanceAfterApprovedStep(PreparedApproval approval, ApprovedSqlOutcome outcome,
                                         SseEventWriter writer) throws IOException {
        AgentRun run = approval.run();
        if (run.status().terminal()) {
            return; // 已终结 Run 不因迟到回调复活
        }
        List<AgentStep> steps = repository.findSteps(run.id());
        Optional<AgentStep> next = nextReadyStep(steps);
        // P12 S4 场景一：步骤完成，推进短期计划状态（RLock 保护；DB 仍为准）。
        planState.advance(run.id(), approval.step().sequence());
        if (next.isPresent()) {
            PreparedRun continuation = new PreparedRun(
                    run.id(), approval.userId(), run.conversationId(),
                    run.connectionId(), approval.database(), run.question(), approval.config());
            String schema = connectionService.schemaContext(approval.userId(), run.connectionId());
            // P14-A：跨审批请求从服务端持久会话/Run 与授权资源重建必要上下文，
            // 绝不信任客户端提供的“已压缩上下文”。
            EffectiveRunContext context = rebuildContext(continuation, writer, schema);
            graphStateService.enter(run.id(), GraphNode.RUNNING, writer);
            AgentStepExecutionInputs inputs = inputsFor(continuation, next.get(), outcome, context);
            advanceReadyStep(continuation, next.get(), inputs, schema, writer, context);
            return;
        }
        completeRunWithSummary(continuationRun(approval), steps, writer);
    }

    /** P14-C：非 SQL 步骤成功后的统一推进（终态守卫 + 选择下一个就绪步骤）。 */
    private void advanceAfterNonSqlStep(PreparedRun run, AgentStep completed, SseEventWriter writer,
                                        EffectiveRunContext context) throws IOException {
        if (isRunTerminal(run.runId())) {
            return;
        }
        List<AgentStep> steps = repository.findSteps(run.runId());
        // 刚完成的步骤即使 DB 仍回读成 PENDING（复制延迟/重放），也不得再次领取，避免自推进死循环。
        Optional<AgentStep> next = nextReadyStep(steps, completed.id());
        planState.advance(run.runId(), completed.sequence());
        if (next.isPresent()) {
            PreparedRun continuation = new PreparedRun(
                    run.runId(), run.userId(), run.conversationId(),
                    run.connectionId(), run.database(), run.question(), run.config());
            String schema = connectionService.schemaContext(run.userId(), run.connectionId());
            EffectiveRunContext rebuilt = rebuildContext(continuation, writer, schema);
            graphStateService.enter(run.runId(), GraphNode.RUNNING, writer);
            AgentStepExecutionInputs inputs = inputsFor(continuation, next.get(), null, rebuilt);
            advanceReadyStep(continuation, next.get(), inputs, schema, writer, rebuilt);
            return;
        }
        completeRunWithSummary(run, steps, writer);
    }

    private PreparedRun continuationRun(PreparedApproval approval) {
        AgentRun run = approval.run();
        return new PreparedRun(
                run.id(), approval.userId(), run.conversationId(), run.connectionId(), approval.database(),
                run.question(), approval.config());
    }

    private boolean isRunTerminal(Long runId) {
        return repository.findRun(runId)
                .map(status -> status.status().terminal())
                .orElse(false);
    }

    /** 运行收尾：真实总结（模型失败时有界降级）、COMPLETED 落库与短期状态清理。 */
    private void completeRunWithSummary(PreparedRun run, List<AgentStep> steps, SseEventWriter writer)
            throws IOException {
        graphStateService.enter(run.runId(), GraphNode.REPORT_GENERATION, writer);
        String stepResults = allStepSummaries(steps);
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.runId(), "INSIGHT", "summary"));
        String summary;
        try {
            summary = llmLimiter.count(run.runId(), () -> sqlAssistant.summarize(run.question(), stepResults));
        } catch (RuntimeException ex) {
            log.warn("agent_summary_failed runId={} message={}", run.runId(), ex.getMessage());
            summary = "查询与分析已完成，共 " + steps.size() + " 步。\n" + stepResults;
        }
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(run.runId(), "INSIGHT", "summary", "SUCCESS"));
        roleRecorder.record(run, "INSIGHT");
        repository.updateRunStatus(run.runId(), RunStatus.COMPLETED, summary, null, null);
        conversationService.appendMessage(
                run.conversationId(), MessageRole.ASSISTANT, summary, MessageStatus.COMPLETED);
        writer.event("run.completed", new AgentStreamEvents.RunCompleted(run.runId(), summary));
        // P12 S4：运行完成，清理短期状态（令牌 + 计划快照）。
        approvalSessionStore.removeRun(run.runId());
        planState.clear(run.runId());
        updateSessionMemory(run, summary);
    }

    /**
     * P14-C：构造步骤执行输入。有审批结果时同请求交接；否则按上游 SQL 步骤做跨请求恢复
     * （由 {@link #resolveAuthorizedInput} 重新鉴权执行，绝不使用内存外伪造数据）。
     */
    private AgentStepExecutionInputs inputsFor(PreparedRun run, AgentStep step, ApprovedSqlOutcome outcome,
                                               EffectiveRunContext context) {
        String effectiveContext = context == null ? "" : context.effectiveContext();
        if (outcome != null) {
            return AgentStepExecutionInputs.fromOutcome(outcome, run.workspaceId(), run.conversationId(),
                    effectiveContext);
        }
        Long sourceStepId = upstreamSqlStepId(run.runId(), step);
        return new AgentStepExecutionInputs(
                run.runId(), run.userId(), run.workspaceId(), run.conversationId(), run.connectionId(),
                sourceStepId, null, null,
                sourceStepId == null ? null : run.runId() + "/" + sourceStepId,
                effectiveContext);
    }

    /**
     * P14-C：解析可信授权输入。优先用同请求交接的真实行；否则按“已审批、未修改的原样 SQL”
     * 经 SqlExecutionService（Guard + 审计）重新取数，并标记 refetched。
     * 上游缺失/未成功/权限失效 → 返回 null（调用方明确失败）。
     */
    private AuthorizedSqlInput resolveAuthorizedInput(PreparedRun run, AgentStep step,
                                                      AgentStepExecutionInputs inputs) {
        if (inputs != null && inputs.hasQueryResult()) {
            Long sourceStepId = inputs.sourceStepId();
            Integer version = inputs.approvedSqlVersion();
            return new AuthorizedSqlInput(
                    run.userId(), run.runId(), sourceStepId, version == null ? 0 : version,
                    run.connectionId(), run.conversationId(), null, null, run.database(),
                    inputs.queryResult(), false);
        }
        if (inputs == null || inputs.sourceStepId() == null) {
            return null;
        }
        AgentStep source = repository.findStep(run.runId(), inputs.sourceStepId()).orElse(null);
        if (source == null || source.sql() == null || source.sql().isBlank()) {
            return null;
        }
        // 只恢复已成功且已审批的步骤；历史已审批版本不能作为新 SQL 自动执行的借口。
        if (source.status() != StepStatus.SUCCEEDED || source.approvalStatus() != ApprovalStatus.APPROVED) {
            return null;
        }
        QueryResultResponse result = executionService.execute(
                run.userId(),
                new ExecuteSqlRequest(run.connectionId(), run.conversationId(), source.sql()),
                run.runId(),
                source.id());
        return new AuthorizedSqlInput(
                run.userId(), run.runId(), source.id(), source.sqlVersion(), run.connectionId(),
                run.conversationId(), null, source.sql(), run.database(), result, true);
    }

    /** 上游 SQL 步骤：优先计划指定的依赖，否则取之前最近的成功 SQL 步骤。 */
    private Long upstreamSqlStepId(Long runId, AgentStep step) {
        List<AgentStep> steps = repository.findSteps(runId);
        if (step.dependsOn() != null) {
            for (AgentStep candidate : steps) {
                if (candidate.sequence() == step.dependsOn() && candidate.sql() != null
                        && !candidate.sql().isBlank()) {
                    return candidate.id();
                }
            }
        }
        return steps.stream()
                .filter(candidate -> candidate.sequence() < step.sequence())
                .filter(candidate -> candidate.sql() != null && !candidate.sql().isBlank())
                .reduce((first, second) -> second)
                .map(AgentStep::id)
                .orElse(null);
    }

    /** 非 SQL 步骤已被处理过：只回放既有事实，不二次执行、不伪造成功。 */
    private void replayNonSqlStep(PreparedRun run, AgentStep step, SseEventWriter writer,
                                  EffectiveRunContext context) throws IOException {
        AgentStep existing = repository.findStep(run.runId(), step.id()).orElse(null);
        if (existing == null) {
            return;
        }
        if (existing.status() == StepStatus.SUCCEEDED) {
            String summary = existing.resultSummary() == null ? "" : existing.resultSummary();
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), existing.agentRole() == null ? "AGENT" : existing.agentRole(),
                    "step-" + existing.sequence(), "SUCCESS"));
            writer.event("step.succeeded", new AgentStreamEvents.StepSucceeded(
                    existing.id(),
                    existing.rowCount() == null ? 0 : existing.rowCount(),
                    Boolean.TRUE.equals(existing.truncated()),
                    summary));
            advanceAfterNonSqlStep(run, existing, writer, context);
            return;
        }
        if (existing.status() == StepStatus.FAILED) {
            writer.event("step.failed", new AgentStreamEvents.StepFailed(
                    existing.id(),
                    existing.errorCode() == null ? "AGENT_STEP_FAILED" : existing.errorCode(),
                    existing.errorMessage() == null ? "步骤执行失败" : existing.errorMessage()));
            return;
        }
        log.warn("agent_non_sql_step_busy runId={} stepId={} status={}",
                run.runId(), step.id(), existing.status());
    }

    /** 非 SQL 步骤失败收敛：步骤 + Run 置 FAILED，发失败事件并清理短期状态。 */
    private void failNonSqlStepAndRun(PreparedRun run, AgentStep step, ErrorCode code, String message,
                                      SseEventWriter writer, String role) throws IOException {
        String safe = safeMessage(message);
        int failed = repository.failNonSqlStep(run.runId(), step.id(), code.name(), safe);
        if (failed == 0) {
            // 步骤已是终态（并发/迟到回调）：不改写事实，也不冒充失败。
            log.warn("agent_non_sql_fail_skipped runId={} stepId={} status=terminal", run.runId(), step.id());
            return;
        }
        repository.updateRunStatus(run.runId(), RunStatus.FAILED, null, code.name(), safe);
        graphStateService.enter(run.runId(), GraphNode.FAILED, writer);
        writer.event("step.failed", new AgentStreamEvents.StepFailed(step.id(), code.name(), safe));
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.runId(), role, "step-" + step.sequence(), "FAILED"));
        writer.event("run.failed", new AgentStreamEvents.RunFailed(run.runId(), code.name(), safe));
        approvalSessionStore.removeRun(run.runId());
        planState.clear(run.runId());
    }

    /** 下一个就绪步骤：PENDING 且依赖已成功（按 sequence 顺序取第一个）。 */
    private static Optional<AgentStep> nextReadyStep(List<AgentStep> steps) {
        return nextReadyStep(steps, null);
    }

    /** @param excludeStepId 需要跳过的步骤（通常是本轮刚完成的步骤），防止重复领取。 */
    private static Optional<AgentStep> nextReadyStep(List<AgentStep> steps, Long excludeStepId) {
        Map<Integer, StepStatus> bySequence = steps.stream()
                .collect(Collectors.toMap(AgentStep::sequence, AgentStep::status, (first, second) -> first));
        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> excludeStepId == null || !step.id().equals(excludeStepId))
                .filter(step -> {
                    if (step.dependsOn() == null) {
                        return true;
                    }
                    return bySequence.get(step.dependsOn()) == StepStatus.SUCCEEDED;
                })
                .findFirst();
    }

    // ------------------------------------------------------------------ P14-C：提示词与数据摘要

    private record RunPrompts(String knowledge, String memoryContext, String skillGuidance,
                              String effectiveContext) {
    }

    /** 解析本轮知识/记忆/Skill/有效上下文（SQL 与 Code 步骤共用同一条受控上下文）。 */
    private RunPrompts resolvePrompts(PreparedRun run, EffectiveRunContext context, SseEventWriter writer)
            throws IOException {
        ResolvedAgentConfig config = run.config();
        boolean ragEnabled = config == null || config.ragEnabled();
        List<KnowledgeSearchResponse.Hit> hits = ragEnabled && (context == null || context.knowledge().isBlank())
                ? contextAssembler.knowledgeHits(run)
                : List.of();
        if (!hits.isEmpty()) {
            contextAssembler.emitKnowledge(run.runId(), writer, hits);
        }
        int knowledgeBudget = config != null && config.memoryContextBudget() > 0
                ? config.memoryContextBudget() : AgentContextAssembler.DEFAULT_KNOWLEDGE_CONTEXT_CHARS;
        String knowledge = context != null && !context.knowledge().isBlank()
                ? context.knowledge()
                : (ragEnabled ? contextAssembler.knowledgeContext(run, knowledgeBudget) : "");
        String memoryContext = context != null ? context.memoryContext() : "";
        String skillGuidance = context != null ? context.skillGuidance() : "";
        String effectiveContext = context == null ? "" : context.effectiveContext();
        return new RunPrompts(knowledge, memoryContext, skillGuidance, effectiveContext);
    }

    /** 列名 + 首行推断类型（只作提示词说明，不授予任何数据访问权）。 */
    private static String buildColumnInfo(QueryResultResponse result) {
        Map<String, Object> first = result.rows().isEmpty() ? null : result.rows().get(0);
        StringBuilder builder = new StringBuilder();
        for (String column : result.columns()) {
            String type = "unknown";
            if (first != null && first.get(column) != null) {
                type = first.get(column).getClass().getSimpleName();
            }
            builder.append(column).append(": ").append(type).append("\n");
        }
        return builder.toString().trim();
    }

    /** 有界真实数据样例：行数 + 截断标记 + 前 N 行紧凑 JSON（超限截断，不外推）。 */
    private String buildDataSummary(QueryResultResponse result) {
        int rows = Math.min(result.rows().size(), PROMPT_SAMPLE_ROWS);
        String json;
        try {
            json = OBJECT_MAPPER.writeValueAsString(result.rows().subList(0, rows));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.debug("agent_python_sample_failed message={}", ex.getMessage());
            json = "（数据样例序列化失败）";
        }
        if (json.length() > PROMPT_SAMPLE_CHARS) {
            json = json.substring(0, PROMPT_SAMPLE_CHARS) + "…（已截断）";
        }
        return "总行数 " + result.rowCount() + (result.truncated() ? "（已截断）" : "") + "\n" + json;
    }

    private static String chartKind(ChartSchema.TypedChart chart) {
        if (chart instanceof ChartSchema.TableChart) {
            return "table";
        }
        if (chart instanceof ChartSchema.BarChart) {
            return "bar";
        }
        if (chart instanceof ChartSchema.LineChart) {
            return "line";
        }
        if (chart instanceof ChartSchema.PieChart) {
            return "pie";
        }
        return "unknown";
    }

    private static String chartTitle(ChartSchema.TypedChart chart) {
        if (chart instanceof ChartSchema.TableChart table) {
            return table.title();
        }
        if (chart instanceof ChartSchema.BarChart bar) {
            return bar.title();
        }
        if (chart instanceof ChartSchema.LineChart line) {
            return line.title();
        }
        if (chart instanceof ChartSchema.PieChart pie) {
            return pie.title();
        }
        return "图表";
    }

    private static String chartSource(ChartSchema.TypedChart chart) {
        if (chart instanceof ChartSchema.TableChart table) {
            return String.valueOf(table.sourceRunId());
        }
        if (chart instanceof ChartSchema.BarChart bar) {
            return String.valueOf(bar.sourceRunId());
        }
        if (chart instanceof ChartSchema.LineChart line) {
            return String.valueOf(line.sourceRunId());
        }
        if (chart instanceof ChartSchema.PieChart pie) {
            return String.valueOf(pie.sourceRunId());
        }
        return "unknown";
    }

    /**
     * P14-B：构造候选选优请求。身份/Workspace/连接/Agent 配置只来自可信 Run 与
     * P14-A 已授权上下文；route 取本次 run 的持久化事实，不用单例共享开关。
     */
    private SqlSelectionRequest selectionRequest(
            PreparedRun run, AgentStep step, String schema, String knowledge,
            String skillGuidance, String effectiveContext, ResolvedAgentConfig config) {
        return new SqlSelectionRequest(
                run.runId(),
                step.id(),
                currentRoute(run),
                run.question(),
                step.objective(),
                schema,
                run.database(),
                previousSummaries(run.runId(), step.sequence()),
                knowledge,
                effectiveContext,
                skillGuidance,
                config == null ? "" : config.systemPrompt(),
                DIALECT_MYSQL);
    }

    /** 本次 run 的最终路由（DB 事实来源）；缺失时按单路径处理（最少模型调用）。 */
    private String currentRoute(PreparedRun run) {
        return repository.findRun(run.runId()).map(AgentRun::route).orElse("");
    }

    /**
     * P14-A：跨审批续跑时从服务端来源重建有效上下文（最近消息、会话摘要、授权 Schema/知识）。
     * 不读取任何客户端回传的压缩结果。供计划审批续跑复用。
     */
    public EffectiveRunContext rebuildContext(PreparedRun run, SseEventWriter writer, String schema)
            throws IOException {
        List<String> recent = conversationService.recentCompletedMessages(run.conversationId(), RECENT_TURNS);
        String memory = conversationService.memorySummary(run.conversationId());
        boolean pendingApproval = repository.findSteps(run.runId()).stream()
                .anyMatch(step -> step.status() == StepStatus.WAITING_APPROVAL);
        return contextAssembler.buildRunContext(
                run, writer, recent, memory, schema, "", pendingApproval);
    }

    /** 会话记忆摘要更新：失败不影响本轮结果，下次请求退回最近轮消息。 */
    public void updateSessionMemory(PreparedRun run, String answer) {
        ResolvedAgentConfig config = run.config();
        if (config != null && !config.memoryEnabled()) {
            return; // Memory 关闭：不写入会话摘要
        }
        try {
            String previous = conversationService.memorySummary(run.conversationId());
            String summary = llmLimiter.count(run.runId(), () ->
                    sqlAssistant.memorySummary(previous, run.question(), answer));
            if (summary != null && !summary.isBlank()) {
                conversationService.updateMemorySummary(run.conversationId(), summary);
            }
        } catch (RuntimeException ex) {
            log.warn("agent_memory_update_failed runId={} message={}", run.runId(), ex.getMessage());
        }
    }

    /**
     * P14-C：按计划创建步骤。角色取计划显式值；缺失时由 RoleAssigner 推断，
     * 但推断出的 CODE/REPORT 角色在没有上游取数步骤时降级为 DATA_ANALYST
     * （旧式仅 objective 的计划必须继续按单步 SQL 正常工作）。
     * 显式声明 CODE/REPORT 却没有上游真实结果时，拒绝整个计划。
     */
    private List<AgentStep> createSteps(Long runId, AgentPlan plan) {
        boolean seenDataStep = false;
        List<AgentPlan.Step> planSteps = plan.steps();
        for (int index = 0; index < planSteps.size(); index++) {
            AgentPlan.Step planStep = planSteps.get(index);
            int sequence = index + 1;
            boolean toolStep = planStep.tool() != null;
            String role = toolStep ? ROLE_TOOL_ASSISTANT : effectiveRole(planStep, seenDataStep);
            if (ROLE_DATA_ANALYST.equals(role)) {
                seenDataStep = true;
            }
            Integer dependsOn = planStep.dependsOn() != null
                    ? planStep.dependsOn()
                    : (index == 0 ? null : sequence - 1);
            // 工具步骤必须带真实工具身份，否则执行阶段会明确失败而不是猜一个工具。
            if (toolStep) {
                repository.insertStep(runId, sequence, planStep.objective(), dependsOn, role,
                        planStep.tool().name(), serializeToolArgs(planStep.tool().args()));
            } else {
                repository.insertStep(runId, sequence, planStep.objective(), dependsOn, role);
            }
        }
        return repository.findSteps(runId);
    }

    private static String serializeToolArgs(java.util.Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(args);
        } catch (IOException ex) {
            // 计划参数已在解析期校验；序列化失败时宁可让该步缺少参数（由 schema 校验拒绝），也不放行。
            return null;
        }
    }

    private String effectiveRole(AgentPlan.Step planStep, boolean seenDataStep) {
        // P14-E：工具步骤自带受控参数，不需要上游真实数据即可执行；
        // 其余非取数角色仍必须有可访问的上游真实结果。
        if (planStep.tool() != null) {
            return ROLE_TOOL_ASSISTANT;
        }
        if (planStep.agent() != null && !planStep.agent().isBlank()) {
            String role = planStep.agent();
            if (!ROLE_DATA_ANALYST.equals(role) && !seenDataStep) {
                throw new ApiException(ErrorCode.AGENT_PLAN_INVALID, HttpStatus.UNPROCESSABLE_ENTITY,
                        "计划中的 " + role + " 步骤缺少可访问的上游真实结果");
            }
            return role;
        }
        AgentRole inferred = roleAssigner.assign(planStep.objective());
        if (inferred != AgentRole.DATA_ANALYST && !seenDataStep) {
            return ROLE_DATA_ANALYST;
        }
        return inferred.name();
    }

    private String previousSummaries(Long runId, int beforeSequence) {
        return repository.findSteps(runId).stream()
                .filter(step -> step.sequence() < beforeSequence && step.resultSummary() != null)
                .map(step -> "步骤 " + step.sequence() + "：" + step.resultSummary())
                .collect(Collectors.joining("\n"));
    }

    private static String allStepSummaries(List<AgentStep> steps) {
        return steps.stream()
                .map(step -> "步骤 " + step.sequence() + "（" + step.objective() + "）："
                        + (step.resultSummary() == null ? "无结果" : step.resultSummary()))
                .collect(Collectors.joining("\n"));
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "步骤执行失败";
        }
        return message.length() > MAX_ERROR_MESSAGE_CHARS
                ? message.substring(0, MAX_ERROR_MESSAGE_CHARS)
                : message;
    }

    public static String digest(QueryResultResponse result) {
        if (result.rowCount() == 0) {
            return "返回 0 行";
        }
        StringBuilder builder = new StringBuilder("返回 ").append(result.rowCount()).append(" 行");
        if (result.truncated()) {
            builder.append("（已截断）");
        }
        builder.append("；");
        int rows = Math.min(result.rows().size(), 3);
        for (int index = 0; index < rows; index++) {
            Map<String, Object> row = result.rows().get(index);
            String line = row.entrySet().stream().limit(8)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));
            if (builder.length() + line.length() > MAX_SUMMARY_CHARS - 3) {
                break;
            }
            builder.append(line).append("; ");
        }
        return builder.length() > MAX_SUMMARY_CHARS
                ? builder.substring(0, MAX_SUMMARY_CHARS)
                : builder.toString();
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }
}
