package com.queryforge.agent;
import com.queryforge.conversation.ConversationService;
import com.queryforge.datasource.ConnectionService;
import com.queryforge.knowledge.KnowledgeService;
import com.queryforge.query.execution.ReadOnlySqlGuard;
import com.queryforge.query.execution.SqlExecutionService;
import com.queryforge.query.execution.SseEventWriter;
import com.queryforge.config.ResolvedAgentConfig;
import com.queryforge.config.RuntimeConfigResolver;
import com.queryforge.skill.SkillService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.agent.AgentProperties;
import com.queryforge.agent.AgentRunResponse;
import com.queryforge.agent.AgentRunSummaryResponse;
import com.queryforge.agent.AgentStepResponse;
import com.queryforge.agent.AgentStreamEvents;
import com.queryforge.agent.ApproveAgentStepRequest;
import com.queryforge.agent.CreateAgentRunRequest;
import com.queryforge.query.execution.ExecuteSqlRequest;
import com.queryforge.knowledge.KnowledgeSearchResponse;
import com.queryforge.query.execution.QueryResultResponse;
import com.queryforge.agent.AgentRoute;
import com.queryforge.agent.AgentRun;
import com.queryforge.agent.AgentStep;
import com.queryforge.conversation.MessageRole;
import com.queryforge.conversation.MessageStatus;
import com.queryforge.agent.RunStatus;
import com.queryforge.agent.StepStatus;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.query.execution.SqlExecutionException;
import com.queryforge.agent.AgentRunRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 多角色协作编排（P5 在 P4 基础上扩展）：
 * Manager 路由 -> Planner/SQL Worker/Fixer/Insight 分工 -> Knowledge 证据注入 -> Memory 摘要。
 * SQL 的审批、Guard、只读执行与幂等约束与 P4 完全一致。
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);
    private static final int MAX_ERROR_MESSAGE_CHARS = 300;
    private static final int MAX_SUMMARY_CHARS = 500;
    private static final int LIST_LIMIT = 20;
    private static final int RECENT_TURNS = 6;
    private static final int KNOWLEDGE_CONTEXT_CHARS = 2000;

    private static final String CHITCHAT_REPLY =
            "你好！我是 SQL 分析助手。你可以描述一个业务问题，我会基于真实 Schema 生成只读 SQL 并等待你确认。";

    private final AgentRunRepository repository;
    private final ConversationService conversationService;
    private final ConnectionService connectionService;
    private final AgentPlanner planner;
    private final AgentSqlAssistant sqlAssistant;
    private final SqlExecutionService executionService;
    private final ReadOnlySqlGuard guard;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ManagerRouter managerRouter;
    private final KnowledgeService knowledgeService;
    private final RuntimeConfigResolver configResolver;
    private final SkillService skillService;

    public AgentRunService(
            AgentRunRepository repository,
            ConversationService conversationService,
            ConnectionService connectionService,
            AgentPlanner planner,
            AgentSqlAssistant sqlAssistant,
            SqlExecutionService executionService,
            ReadOnlySqlGuard guard,
            AgentProperties properties,
            ObjectMapper objectMapper,
            ManagerRouter managerRouter,
            KnowledgeService knowledgeService,
            RuntimeConfigResolver configResolver,
            SkillService skillService) {
        this.repository = repository;
        this.conversationService = conversationService;
        this.connectionService = connectionService;
        this.planner = planner;
        this.sqlAssistant = sqlAssistant;
        this.executionService = executionService;
        this.guard = guard;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.managerRouter = managerRouter;
        this.knowledgeService = knowledgeService;
        this.configResolver = configResolver;
        this.skillService = skillService;
    }

    public PreparedRun prepareStart(Long userId, CreateAgentRunRequest request) {
        Long conversationId = conversationService.requireMatchingConversation(
                userId, request.conversationId(), request.connectionId());
        ConnectionService.ConnectionSummary connection =
                connectionService.summary(userId, request.connectionId());
        String question = request.userInput().trim();

        ResolvedAgentConfig config = resolveConfig(userId, request);
        Long runId = repository.insertRun(
                userId, conversationId, connection.id(), connection.name(), question, RunStatus.PLANNING,
                config.agentId(), config.agentVersionId(),
                writeJson(config.skillVersionRefs()), writeJson(config.allowedToolIds()));
        conversationService.appendMessage(conversationId, MessageRole.USER, question, MessageStatus.COMPLETED);
        return new PreparedRun(
                runId, userId, conversationId, connection.id(), connection.database(), question, config);
    }

    /** 解析运行配置：显式指定版本 > 草稿（测试）> Agent 当前发布版 > 默认。 */
    private ResolvedAgentConfig resolveConfig(Long userId, CreateAgentRunRequest request) {
        if (request.agentVersionId() != null) {
            return configResolver.resolvePublishedByVersion(userId, request.agentVersionId());
        }
        if (request.agentId() != null) {
            if (Boolean.TRUE.equals(request.draft())) {
                return configResolver.resolveDraft(userId, request.agentId());
            }
            return configResolver.resolvePublished(userId, request.agentId());
        }
        return ResolvedAgentConfig.defaults();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException ex) {
            return null;
        }
    }

    public void startRun(PreparedRun run, OutputStream outputStream) {
        SseEventWriter writer = new SseEventWriter(outputStream, objectMapper);
        try {
            writer.event("run.started",
                    new AgentStreamEvents.RunStarted(run.runId(), run.conversationId()));
            if (run.config() != null && run.config().agentId() != null) {
                writer.event("config.resolved", new AgentStreamEvents.ConfigResolved(
                        run.runId(), run.config().agentId(), run.config().agentVersionId(),
                        run.config().ragEnabled(), run.config().memoryEnabled()));
            }
            String schema = connectionService.schemaContext(run.userId(), run.connectionId());
            List<String> recent = conversationService.recentCompletedMessages(run.conversationId(), RECENT_TURNS);
            String memory = conversationService.memorySummary(run.conversationId());
            writer.event("context.prepared", new AgentStreamEvents.ContextPrepared(
                    run.runId(), recent.size(), memory != null && !memory.isBlank()));

            ManagerRouter.RouteDecision decision = route(run, memory, writer);
            repository.updateRunRoute(run.runId(), decision.route().name(), null, null);
            writer.event("agent.routed", new AgentStreamEvents.AgentRouted(
                    run.runId(), decision.route().name(), decision.reason()));
            recordRole(run, "MANAGER");

            switch (decision.route()) {
                case CHITCHAT -> {
                    repository.updateRunStatus(run.runId(), RunStatus.COMPLETED, CHITCHAT_REPLY, null, null);
                    conversationService.appendMessage(
                            run.conversationId(), MessageRole.ASSISTANT, CHITCHAT_REPLY, MessageStatus.COMPLETED);
                    writer.event("run.completed", new AgentStreamEvents.RunCompleted(run.runId(), CHITCHAT_REPLY));
                    updateMemory(run, CHITCHAT_REPLY);
                }
                case CLARIFY -> {
                    String message = decision.reason().isBlank()
                            ? "请补充数据源或业务口径后再提问。" : decision.reason();
                    repository.updateRunStatus(run.runId(), RunStatus.CLARIFYING, null, "AGENT_CLARIFY", message);
                    writer.event("clarify.required", new AgentStreamEvents.ClarifyRequired(run.runId(), message));
                }
                case KNOWLEDGE -> answerFromKnowledge(run, writer);
                case SIMPLE -> {
                    AgentPlan plan = new AgentPlan(
                            true, "", List.of(new AgentPlan.Step(truncate(run.question(), 300))));
                    executePlan(run, schema, plan, writer);
                }
                case COMPLEX -> {
                    AgentPlan plan = plan(run, schema, writer);
                    if (plan != null) {
                        executePlan(run, schema, plan, writer);
                    }
                }
            }
        } catch (IOException ex) {
            log.info("agent_client_closed runId={} phase=planning", run.runId());
            cancelRunQuietly(run.userId(), run.runId());
        } catch (RuntimeException ex) {
            log.warn("agent_plan_failed runId={} message={}", run.runId(), ex.getMessage());
            failRunWithEventQuietly(run.runId(), "AGENT_PLAN_FAILED", "规划失败，请稍后重试", writer);
        }
    }

    private ManagerRouter.RouteDecision route(PreparedRun run, String memory, SseEventWriter writer)
            throws IOException {
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.runId(), "MANAGER", "route"));
        try {
            ManagerRouter.RouteDecision decision =
                    countLlm(run.runId(), () -> managerRouter.route(run.question(), memory));
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "MANAGER", "route", "SUCCESS"));
            return decision;
        } catch (RuntimeException ex) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "MANAGER", "route", "FAILED"));
            throw ex;
        }
    }

    private AgentPlan plan(PreparedRun run, String schema, SseEventWriter writer) throws IOException {
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.runId(), "PLANNER", "plan"));
        try {
            AgentPlan plan = countLlm(run.runId(), () -> planner.plan(run.question(), schema));
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "PLANNER", "plan", "SUCCESS"));
            recordRole(run, "PLANNER");
            if (!plan.answerable()) {
                failRunWithEvent(run.runId(), "AGENT_UNANSWERABLE", plan.reason(), writer);
                return null;
            }
            return plan;
        } catch (ApiException ex) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "PLANNER", "plan", "FAILED"));
            failRunWithEvent(run.runId(), "AGENT_PLAN_INVALID", ex.getMessage(), writer);
            return null;
        }
    }

    private void answerFromKnowledge(PreparedRun run, SseEventWriter writer) throws IOException {
        List<KnowledgeSearchResponse.Hit> hits =
                knowledgeService.hits(run.userId(), run.connectionId(), run.question());
        emitKnowledge(run.runId(), writer, hits);
        String knowledge = knowledgeService.knowledgeContext(
                run.userId(), run.connectionId(), run.question(), KNOWLEDGE_CONTEXT_CHARS);
        String answer = countLlm(run.runId(), () -> sqlAssistant.knowledgeAnswer(run.question(), knowledge));
        recordRole(run, "KNOWLEDGE");
        repository.updateRunStatus(run.runId(), RunStatus.COMPLETED, answer, null, null);
        conversationService.appendMessage(
                run.conversationId(), MessageRole.ASSISTANT, answer, MessageStatus.COMPLETED);
        writer.event("run.completed", new AgentStreamEvents.RunCompleted(run.runId(), answer));
        updateMemory(run, answer);
    }

    private void executePlan(PreparedRun run, String schema, AgentPlan plan, SseEventWriter writer)
            throws IOException {
        List<AgentStep> steps = createSteps(run.runId(), plan);
        writer.event("plan.ready", new AgentStreamEvents.PlanReady(
                run.runId(),
                steps.stream()
                        .map(step -> new AgentStreamEvents.PlanStep(step.id(), step.sequence(), step.objective()))
                        .toList()));
        advance(run, steps.get(0), schema, writer);
    }

    public PreparedApproval prepareApproval(Long userId, Long runId, Long stepId,
                                            ApproveAgentStepRequest request) {
        AgentRun run = requireRun(userId, runId);
        AgentStep step = requireStep(runId, stepId);
        if (!Boolean.TRUE.equals(request.confirm())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "必须显式确认后才可执行");
        }
        if (step.sqlVersion() <= 0 || step.sqlVersion() != request.sqlVersion()) {
            throw conflict("SQL 版本已变化，请刷新后重新确认");
        }
        if (step.status() == StepStatus.SUCCEEDED) {
            return new PreparedApproval(userId, run, step, true, databaseOf(userId, run), configOf(run));
        }
        if (run.status().terminal()) {
            throw conflict("运行已结束，不能继续确认");
        }
        if (step.status() != StepStatus.WAITING_APPROVAL) {
            throw conflict("该步骤当前不可确认");
        }
        String database = databaseOf(userId, run);
        int claimed = repository.claimStep(runId, stepId, request.sqlVersion());
        if (claimed == 0) {
            AgentStep latest = requireStep(runId, stepId);
            if (latest.status() == StepStatus.SUCCEEDED && latest.sqlVersion() == request.sqlVersion()) {
                return new PreparedApproval(userId, run, latest, true, database, configOf(run));
            }
            throw conflict("该步骤已被处理，不能重复执行");
        }
        repository.updateRunStatus(runId, RunStatus.RUNNING, null, null, null);
        return new PreparedApproval(userId, run, step, false, database, configOf(run));
    }

    /** 从 Run 固定的 agentVersionId 重新解析配置（版本不可变，结果稳定）。 */
    private ResolvedAgentConfig configOf(AgentRun run) {
        if (run.agentVersionId() != null) {
            return configResolver.resolvePublishedByVersion(run.userId(), run.agentVersionId());
        }
        if (run.agentId() != null) {
            return configResolver.resolvePublished(run.userId(), run.agentId());
        }
        return ResolvedAgentConfig.defaults();
    }

    public void executeApproval(PreparedApproval approval, OutputStream outputStream) {
        SseEventWriter writer = new SseEventWriter(outputStream, objectMapper);
        AgentRun run = approval.run();
        AgentStep step = approval.step();
        try {
            if (approval.replay()) {
                writer.event("step.succeeded", new AgentStreamEvents.StepSucceeded(
                        step.id(), step.rowCount() == null ? 0 : step.rowCount(),
                        Boolean.TRUE.equals(step.truncated()),
                        step.resultSummary() == null ? "" : step.resultSummary()));
                return;
            }
            executeAndContinue(approval, writer);
            advanceAfterStep(approval, writer);
        } catch (IOException ex) {
            log.info("agent_client_closed runId={} stepId={}", run.id(), step.id());
            markInterrupted(run.id(), step.id());
        } catch (SqlExecutionException ex) {
            try {
                handleExecutionFailure(approval, ex, writer);
            } catch (IOException | RuntimeException emitError) {
                log.info("agent_failure_event_skipped runId={} stepId={}", run.id(), step.id());
                markInterrupted(run.id(), step.id());
            }
        } catch (ApiException ex) {
            try {
                failStepAndRun(run, step, ex.getErrorCode(), ex.getMessage(), writer);
            } catch (IOException | RuntimeException emitError) {
                log.info("agent_failure_event_skipped runId={} stepId={}", run.id(), step.id());
            }
        } catch (RuntimeException ex) {
            log.warn("agent_step_failed runId={} stepId={} message={}", run.id(), step.id(), ex.getMessage());
            failStepAndRunQuietly(run, step, "AGENT_STEP_FAILED", "步骤执行失败", writer);
        }
    }

    @Transactional
    public void reject(Long userId, Long runId, Long stepId) {
        AgentRun run = requireRun(userId, runId);
        AgentStep step = requireStep(runId, stepId);
        if (run.status() == RunStatus.CANCELED || step.status() == StepStatus.CANCELED) {
            return;
        }
        if (step.status() != StepStatus.WAITING_APPROVAL) {
            throw conflict("该步骤当前不可拒绝");
        }
        repository.rejectStep(stepId, "用户拒绝执行该 SQL");
        repository.updateRunStatus(runId, RunStatus.CANCELED, null, "AGENT_STEP_REJECTED", "用户拒绝执行该 SQL");
    }

    @Transactional
    public void cancel(Long userId, Long runId) {
        requireRun(userId, runId);
        int changed = repository.cancelActiveRun(runId, userId);
        if (changed > 0) {
            repository.cancelPendingSteps(runId);
        }
    }

    public AgentRunResponse detail(Long userId, Long runId) {
        AgentRun run = requireRun(userId, runId);
        List<AgentStep> steps = repository.findSteps(runId);
        return new AgentRunResponse(
                run.id(), run.conversationId(), run.connectionId(), run.connectionName(),
                run.question(), run.status(), run.route(), parseRoles(run.roleActivities()),
                parseSources(run.knowledgeSources()), run.llmCallCount(),
                run.summary(), run.errorCode(), run.errorMessage(),
                run.createdAt(), run.updatedAt(), steps.stream().map(AgentRunService::toStepResponse).toList());
    }

    public List<AgentRunSummaryResponse> listByConversation(Long userId, Long conversationId) {
        conversationService.requireMatchingConversation(userId, conversationId, null);
        return repository.findRuns(userId, conversationId, LIST_LIMIT).stream()
                .map(run -> new AgentRunSummaryResponse(
                        run.id(), run.conversationId(), run.connectionId(), run.connectionName(),
                        run.question(), run.status(), run.createdAt(), run.updatedAt()))
                .toList();
    }

    public Optional<AgentRunResponse> latest(Long userId, Long conversationId) {
        conversationService.requireMatchingConversation(userId, conversationId, null);
        return repository.findLatestRun(userId, conversationId).map(run -> detail(userId, run.id()));
    }

    private void executeAndContinue(PreparedApproval approval, SseEventWriter writer) throws IOException {
        AgentRun run = approval.run();
        AgentStep step = approval.step();
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
        recordRole(run, "SQL_AGENT");
    }

    private void handleExecutionFailure(PreparedApproval approval, SqlExecutionException ex,
                                        SseEventWriter writer) throws IOException {
        AgentRun run = approval.run();
        AgentStep step = approval.step();
        if (!ex.isFixable() || step.repairCount() >= properties.maxRepairs()) {
            failStepAndRun(run, step, ex.getErrorCode(), ex.getMessage(), writer);
            return;
        }

        int attempt = step.repairCount() + 1;
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.id(), "FIXER", "step-" + step.sequence()));
        writer.event("step.repairing", new AgentStreamEvents.StepRepairing(
                step.id(), attempt, ex.getErrorCode().name()));
        String validated;
        try {
            String schema = connectionService.schemaContext(approval.userId(), run.connectionId());
            String fixed = countLlm(run.id(), () ->
                    sqlAssistant.fixSql(step.sql(), ex.getMessage(), step.objective(), schema));
            validated = guard.validate(fixed, approval.database());
        } catch (RuntimeException fixError) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.id(), "FIXER", "step-" + step.sequence(), "FAILED"));
            ErrorCode code = fixError instanceof ApiException api
                    ? api.getErrorCode()
                    : ErrorCode.AGENT_STEP_FAILED;
            failStepAndRun(run, step, code, "自动修复失败，已终止本次运行", writer);
            return;
        }
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.id(), "FIXER", "step-" + step.sequence(), "SUCCESS"));
        recordRole(run, "FIXER");
        if (validated.isBlank()) {
            failStepAndRun(run, step, ErrorCode.AGENT_PLAN_INVALID, "修复后的 SQL 为空，运行终止", writer);
            return;
        }
        repository.setStepSql(step.id(), validated, step.sqlVersion() + 1, attempt);
        repository.updateRunStatus(run.id(), RunStatus.WAITING_APPROVAL, null, null, null);
        writer.event("step.sql_ready", new AgentStreamEvents.StepSqlReady(
                step.id(), validated, step.sqlVersion() + 1));
        writer.event("approval.required", new AgentStreamEvents.ApprovalRequired(
                step.id(), step.sqlVersion() + 1));
    }

    private void advance(PreparedRun run, AgentStep step, String schema, SseEventWriter writer)
            throws IOException {
        ResolvedAgentConfig config = run.config();
        boolean ragEnabled = config == null || config.ragEnabled();
        List<KnowledgeSearchResponse.Hit> hits = ragEnabled
                ? knowledgeService.hits(run.userId(), run.connectionId(), run.question())
                : List.of();
        emitKnowledge(run.runId(), writer, hits);
        int knowledgeBudget = config != null && config.memoryContextBudget() > 0
                ? config.memoryContextBudget() : KNOWLEDGE_CONTEXT_CHARS;
        String knowledge = ragEnabled
                ? knowledgeService.knowledgeContext(
                        run.userId(), run.connectionId(), run.question(), knowledgeBudget)
                : "";

        // 选择相关 Skill 并渲染指引（记录选择证据，不泄露模板全文到普通日志）
        String skillGuidance = selectSkillGuidance(run, writer);

        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.runId(), "SQL_AGENT", "step-" + step.sequence()));
        writer.event("step.started", new AgentStreamEvents.StepStarted(step.id(), step.sequence()));
        String sql;
        try {
            String customPrompt = config == null ? "" : config.systemPrompt();
            sql = countLlm(run.runId(), () ->
                    sqlAssistant.generateSql(step.objective(), schema, previousSummaries(run.runId(), step.sequence()),
                            knowledge, customPrompt, skillGuidance));
            sql = guard.validate(sql, run.database());
        } catch (ApiException ex) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "SQL_AGENT", "step-" + step.sequence(), "FAILED"));
            failStepAndRun(run, step, ex.getErrorCode(), ex.getMessage(), writer);
            return;
        }
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.runId(), "SQL_AGENT", "step-" + step.sequence(), "SUCCESS"));
        recordRole(run, "SQL_AGENT");
        if (sql.isBlank()) {
            failStepAndRun(run, step, ErrorCode.AGENT_PLAN_INVALID, "模型未生成可执行的 SQL", writer);
            return;
        }

        int nextVersion = step.sqlVersion() + 1;
        repository.setStepSql(step.id(), sql, nextVersion, step.repairCount());
        repository.updateRunStatus(run.runId(), RunStatus.WAITING_APPROVAL, null, null, null);
        writer.event("step.sql_ready", new AgentStreamEvents.StepSqlReady(step.id(), sql, nextVersion));
        writer.event("approval.required", new AgentStreamEvents.ApprovalRequired(step.id(), nextVersion));
    }

    /** 按任务问题选择绑定的已发布 Skill 并渲染指引；无相关 Skill 时返回空串。 */
    private String selectSkillGuidance(PreparedRun run, SseEventWriter writer) throws IOException {
        ResolvedAgentConfig config = run.config();
        if (config == null || config.skillVersionRefs().isEmpty()) {
            return "";
        }
        List<SkillService.SkillRef> refs = config.skillVersionRefs().entrySet().stream()
                .map(entry -> new SkillService.SkillRef(entry.getKey(), entry.getValue()))
                .toList();
        List<SkillService.SelectedSkill> selected = skillService.selectAndRender(
                refs, run.question(), null, 3);
        if (selected.isEmpty()) {
            return "";
        }
        // 记录选择证据（skillId、版本号），不记录模板全文
        List<AgentStreamEvents.SkillSelectedView> views = selected.stream()
                .map(s -> new AgentStreamEvents.SkillSelectedView(s.skillId(), s.skillVersionId(), s.versionNo()))
                .toList();
        writer.event("skill.selected", new AgentStreamEvents.SkillSelected(run.runId(), views));
        StringBuilder guidance = new StringBuilder();
        for (SkillService.SelectedSkill s : selected) {
            guidance.append("【Skill v").append(s.versionNo()).append("】").append(s.renderedGuidance()).append("\n");
        }
        return guidance.toString();
    }

    private void advanceAfterStep(PreparedApproval approval, SseEventWriter writer) throws IOException {
        AgentRun run = approval.run();
        List<AgentStep> steps = repository.findSteps(run.id());
        Optional<AgentStep> next = steps.stream()
                .filter(step -> step.sequence() > approval.step().sequence() && step.status() == StepStatus.PENDING)
                .findFirst();
        if (next.isPresent()) {
            PreparedRun continuation = new PreparedRun(
                    run.id(), approval.userId(), run.conversationId(),
                    run.connectionId(), approval.database(), run.question(), approval.config());
            String schema = connectionService.schemaContext(approval.userId(), run.connectionId());
            advance(continuation, next.get(), schema, writer);
            return;
        }

        String stepResults = allStepSummaries(steps);
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.id(), "INSIGHT", "summary"));
        String summary;
        try {
            summary = countLlm(run.id(), () -> sqlAssistant.summarize(run.question(), stepResults));
        } catch (RuntimeException ex) {
            log.warn("agent_summary_failed runId={} message={}", run.id(), ex.getMessage());
            summary = "查询已完成，共 " + steps.size() + " 步。\n" + stepResults;
        }
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(run.id(), "INSIGHT", "summary", "SUCCESS"));
        recordRole(run, "INSIGHT");
        repository.updateRunStatus(run.id(), RunStatus.COMPLETED, summary, null, null);
        conversationService.appendMessage(
                run.conversationId(), MessageRole.ASSISTANT, summary, MessageStatus.COMPLETED);
        writer.event("run.completed", new AgentStreamEvents.RunCompleted(run.id(), summary));
        PreparedRun prepared = new PreparedRun(
                run.id(), approval.userId(), run.conversationId(), run.connectionId(), approval.database(),
                run.question(), approval.config());
        updateMemory(prepared, summary);
    }

    private void updateMemory(PreparedRun run, String answer) {
        ResolvedAgentConfig config = run.config();
        if (config != null && !config.memoryEnabled()) {
            return; // Memory 关闭：不写入会话摘要
        }
        try {
            String previous = conversationService.memorySummary(run.conversationId());
            String summary = countLlm(run.runId(), () ->
                    sqlAssistant.memorySummary(previous, run.question(), answer));
            if (summary != null && !summary.isBlank()) {
                conversationService.updateMemorySummary(run.conversationId(), summary);
            }
        } catch (RuntimeException ex) {
            // 摘要失败不影响本轮结果；下次请求退回最近轮消息。
            log.warn("agent_memory_update_failed runId={} message={}", run.runId(), ex.getMessage());
        }
    }

    private void emitKnowledge(Long runId, SseEventWriter writer, List<KnowledgeSearchResponse.Hit> hits)
            throws IOException {
        if (hits.isEmpty()) {
            return;
        }
        List<AgentStreamEvents.KnowledgeSourceView> sources = hits.stream()
                .map(hit -> new AgentStreamEvents.KnowledgeSourceView(
                        hit.id(), hit.sourceType().name(), hit.title(), hit.version()))
                .toList();
        writer.event("knowledge.retrieved", new AgentStreamEvents.KnowledgeRetrieved(runId, sources));
        try {
            String sourcesJson = objectMapper.writeValueAsString(sources);
            repository.updateRunKnowledgeSources(runId, sourcesJson);
        } catch (IOException | RuntimeException ex) {
            log.debug("agent_knowledge_persist_skipped runId={}", runId);
        }
    }

    private List<AgentStep> createSteps(Long runId, AgentPlan plan) {
        for (int index = 0; index < plan.steps().size(); index++) {
            int sequence = index + 1;
            Integer dependsOn = index == 0 ? null : sequence - 1;
            repository.insertStep(runId, sequence, plan.steps().get(index).objective(), dependsOn);
        }
        return repository.findSteps(runId);
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

    static String digest(QueryResultResponse result) {
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

    private void failStepAndRun(AgentRun run, AgentStep step, ErrorCode code, String message,
                                SseEventWriter writer) throws IOException {
        String safe = safeMessage(message);
        repository.failStep(step.id(), code.name(), safe);
        repository.updateRunStatus(run.id(), RunStatus.FAILED, null, code.name(), safe);
        writer.event("step.failed", new AgentStreamEvents.StepFailed(step.id(), code.name(), safe));
        writer.event("run.failed", new AgentStreamEvents.RunFailed(run.id(), code.name(), safe));
    }

    private void failStepAndRun(PreparedRun run, AgentStep step, ErrorCode code, String message,
                                SseEventWriter writer) throws IOException {
        String safe = safeMessage(message);
        repository.failStep(step.id(), code.name(), safe);
        repository.updateRunStatus(run.runId(), RunStatus.FAILED, null, code.name(), safe);
        writer.event("step.failed", new AgentStreamEvents.StepFailed(step.id(), code.name(), safe));
        writer.event("run.failed", new AgentStreamEvents.RunFailed(run.runId(), code.name(), safe));
    }

    private void failStepAndRunQuietly(AgentRun run, AgentStep step, String code, String message,
                                       SseEventWriter writer) {
        try {
            String safe = safeMessage(message);
            repository.failStep(step.id(), code, safe);
            repository.updateRunStatus(run.id(), RunStatus.FAILED, null, code, safe);
            writer.event("step.failed", new AgentStreamEvents.StepFailed(step.id(), code, safe));
            writer.event("run.failed", new AgentStreamEvents.RunFailed(run.id(), code, safe));
        } catch (IOException | RuntimeException ex) {
            log.debug("agent_fail_event_skipped runId={}", run.id());
        }
    }

    private void failRunWithEvent(Long runId, String code, String message, SseEventWriter writer)
            throws IOException {
        String safe = safeMessage(message);
        repository.updateRunStatus(runId, RunStatus.FAILED, null, code, safe);
        writer.event("run.failed", new AgentStreamEvents.RunFailed(runId, code, safe));
    }

    private void failRunWithEventQuietly(Long runId, String code, String message, SseEventWriter writer) {
        try {
            failRunWithEvent(runId, code, message, writer);
        } catch (IOException | RuntimeException ex) {
            log.debug("agent_run_fail_event_skipped runId={}", runId);
        }
    }

    private void markInterrupted(Long runId, Long stepId) {
        try {
            int failed = repository.failStep(stepId, "AGENT_CLIENT_CLOSED", "客户端断开，执行状态未知");
            if (failed > 0) {
                repository.updateRunStatus(
                        runId, RunStatus.FAILED, null, "AGENT_CLIENT_CLOSED", "客户端断开，执行状态未知");
            }
        } catch (RuntimeException ex) {
            log.warn("agent_interrupt_mark_failed runId={}", runId);
        }
    }

    private void cancelRunQuietly(Long userId, Long runId) {
        try {
            if (repository.cancelActiveRun(runId, userId) > 0) {
                repository.cancelPendingSteps(runId);
            }
        } catch (RuntimeException ex) {
            log.warn("agent_cancel_failed runId={}", runId);
        }
    }

    private <T> T countLlm(Long runId, Supplier<T> call) {
        if (repository.llmCallCount(runId) >= properties.maxLlmCalls()) {
            throw new ApiException(
                    ErrorCode.AGENT_STATE_CONFLICT, HttpStatus.TOO_MANY_REQUESTS, "本次运行的模型调用次数已达上限");
        }
        repository.incrementLlmCalls(runId);
        return call.get();
    }

    private void recordRole(PreparedRun prepared, String role) {
        AgentRun current = repository.findRunOwned(prepared.runId(), prepared.userId()).orElse(null);
        if (current != null) {
            recordRole(current, role);
        }
    }

    private void recordRole(AgentRun run, String role) {
        try {
            List<AgentRunResponse.RoleActivity> roles = new ArrayList<>(parseRoles(run.roleActivities()));
            if (roles.stream().noneMatch(item -> item.role().equals(role))) {
                roles.add(new AgentRunResponse.RoleActivity(role, "DONE"));
            }
            String rolesJson = objectMapper.writeValueAsString(roles);
            repository.updateRunRoute(run.id(), run.route(), rolesJson, run.knowledgeSources());
        } catch (IOException | RuntimeException ex) {
            log.debug("agent_role_persist_skipped runId={} role={}", run.id(), role);
        }
    }

    private List<AgentRunResponse.RoleActivity> parseRoles(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AgentRunResponse.RoleActivity>>() {
            });
        } catch (IOException ex) {
            return List.of();
        }
    }

    private List<AgentRunResponse.KnowledgeSourceRef> parseSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AgentRunResponse.KnowledgeSourceRef>>() {
            });
        } catch (IOException ex) {
            return List.of();
        }
    }

    private AgentRun requireRun(Long userId, Long runId) {
        return repository.findRunOwned(runId, userId).orElseThrow(() -> new ApiException(
                ErrorCode.AGENT_RUN_NOT_FOUND, HttpStatus.NOT_FOUND, "运行不存在或无权访问"));
    }

    private AgentStep requireStep(Long runId, Long stepId) {
        return repository.findStep(runId, stepId).orElseThrow(() -> new ApiException(
                ErrorCode.AGENT_STEP_NOT_FOUND, HttpStatus.NOT_FOUND, "步骤不存在或无权访问"));
    }

    private String databaseOf(Long userId, AgentRun run) {
        if (run.connectionId() == null) {
            throw new ApiException(
                    ErrorCode.CONNECTION_NOT_FOUND, HttpStatus.NOT_FOUND, "运行关联的数据源已删除");
        }
        return connectionService.summary(userId, run.connectionId()).database();
    }

    private static AgentStepResponse toStepResponse(AgentStep step) {
        return new AgentStepResponse(
                step.id(), step.sequence(), step.objective(), step.dependsOn(), step.status(),
                step.sql(), step.sqlVersion(), step.agentRole(), step.approvalStatus(), step.repairCount(),
                step.rowCount(), step.truncated(), step.errorCode(), step.errorMessage(), step.resultSummary());
    }

    private static ApiException conflict(String message) {
        return new ApiException(ErrorCode.AGENT_STATE_CONFLICT, HttpStatus.CONFLICT, message);
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "运行失败";
        }
        return message.length() > MAX_ERROR_MESSAGE_CHARS
                ? message.substring(0, MAX_ERROR_MESSAGE_CHARS)
                : message;
    }

    private static String truncate(String value, int maxChars) {
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }

    public record PreparedRun(
            Long runId,
            Long userId,
            Long conversationId,
            Long connectionId,
            String database,
            String question,
            ResolvedAgentConfig config
    ) {
        /** 兼容旧构造：无配置时用默认配置。 */
        public PreparedRun(Long runId, Long userId, Long conversationId, Long connectionId,
                           String database, String question) {
            this(runId, userId, conversationId, connectionId, database, question, ResolvedAgentConfig.defaults());
        }
    }

    public record PreparedApproval(
            Long userId,
            AgentRun run,
            AgentStep step,
            boolean replay,
            String database,
            ResolvedAgentConfig config
    ) {
    }
}
