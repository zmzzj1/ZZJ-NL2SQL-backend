package com.queryforge.application.agent.runtime;

import com.queryforge.application.agent.AgentLlmCallLimiter;
import com.queryforge.application.agent.AgentPlanStateService;
import com.queryforge.application.agent.AgentSqlAssistant;
import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.graph.GraphNode;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import com.queryforge.infrastructure.web.SseEventWriter;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * P14-ARCH-01：Graph 运行终态收敛（Manager / Dashboard / 失败路径共用）。
 *
 * <p>保证每个 Run 只产出一个终态：MySQL Run 状态、会话消息、{@code run.completed}/
 * {@code run.failed} 事件与 Graph 终态标记一致；幂等——已终态的 Run 不被迟到回调覆写。
 */
@Service
public class AgentRunFinalizer {

    private static final Logger log = LoggerFactory.getLogger(AgentRunFinalizer.class);

    private final AgentRunRepository repository;
    private final ConversationService conversationService;
    private final AgentSqlAssistant sqlAssistant;
    private final AgentLlmCallLimiter llmLimiter;
    private final AgentPlanStateService planState;
    private final ApprovalSessionStore approvalSessionStore;
    private final PlanReviewSessionStore planReviewSessionStore;
    private final AgentGraphStateService graphStateService;

    public AgentRunFinalizer(AgentRunRepository repository, ConversationService conversationService,
                             AgentSqlAssistant sqlAssistant, AgentLlmCallLimiter llmLimiter,
                             AgentPlanStateService planState, ApprovalSessionStore approvalSessionStore,
                             PlanReviewSessionStore planReviewSessionStore,
                             AgentGraphStateService graphStateService) {
        this.repository = repository;
        this.conversationService = conversationService;
        this.sqlAssistant = sqlAssistant;
        this.llmLimiter = llmLimiter;
        this.planState = planState;
        this.approvalSessionStore = approvalSessionStore;
        this.planReviewSessionStore = planReviewSessionStore;
        this.graphStateService = graphStateService;
    }

    /**
     * 成功终态：真实总结（模型失败时有界降级）→ COMPLETED 落库 → 会话消息 → run.completed。
     * {@code answer} 非空时直接采用（Dashboard 已生成）；为空时按真实步骤摘要总结一次。
     */
    public String complete(AgentRunTaskContext ctx, SseEventWriter writer, List<AgentStep> steps,
                           String answer) {
        Long runId = ctx.runId();
        if (isTerminal(runId)) {
            return currentSummary(runId);
        }
        String summary = answer;
        if (summary == null || summary.isBlank()) {
            String stepResults = allStepSummaries(steps);
            try {
                summary = llmLimiter.count(runId, () -> sqlAssistant.summarize(ctx.question(), stepResults));
            } catch (RuntimeException ex) {
                log.warn("agent_summary_failed runId={} message={}", runId, ex.getMessage());
                summary = null;
            }
            if (summary == null || summary.isBlank()) {
                summary = "查询与分析已完成，共 " + steps.size() + " 步。\n" + stepResults;
            }
        }
        repository.updateRunStatus(runId, RunStatus.COMPLETED, summary, null, null);
        conversationService.appendMessage(ctx.conversationId(),
                com.queryforge.domain.conversation.MessageRole.ASSISTANT, summary,
                com.queryforge.domain.conversation.MessageStatus.COMPLETED);
        updateSessionMemory(ctx, summary);
        cleanup(runId);
        enterGraphNode(runId, writer, GraphNode.COMPLETED);
        emit(writer, "run.completed", new AgentStreamEvents.RunCompleted(runId, summary));
        return summary;
    }

    /** 失败终态：FAILED 落库 + run.failed 事件 + 短期状态清理。 */
    public void fail(AgentRunTaskContext ctx, SseEventWriter writer, String code, String message) {
        Long runId = ctx.runId();
        if (isTerminal(runId)) {
            return;
        }
        repository.updateRunStatus(runId, RunStatus.FAILED, null, code, safe(message));
        cleanup(runId);
        enterGraphNode(runId, writer, GraphNode.FAILED);
        emit(writer, "run.failed", new AgentStreamEvents.RunFailed(runId, code, safe(message)));
    }

    /** 取消（服务端取消/客户端明确取消）：CANCELED 落库，不发送重复终态事件。 */
    public void cancel(Long runId) {
        repository.transitionRunStatus(runId, RunStatus.CANCELED);
        cleanup(runId);
    }

    public boolean isTerminal(Long runId) {
        return repository.findRun(runId)
                .map(run -> run.status().terminal())
                .orElse(false);
    }

    private String currentSummary(Long runId) {
        return repository.findRun(runId).map(AgentRun::summary).orElse("");
    }

    private void updateSessionMemory(AgentRunTaskContext ctx, String answer) {
        if (ctx.config() != null && !ctx.config().memoryEnabled()) {
            return;
        }
        try {
            String previous = conversationService.memorySummary(ctx.conversationId());
            String summary = llmLimiter.count(ctx.runId(), () ->
                    sqlAssistant.memorySummary(previous, ctx.question(), answer));
            if (summary != null && !summary.isBlank()) {
                conversationService.updateMemorySummary(ctx.conversationId(), summary);
            }
        } catch (RuntimeException ex) {
            log.warn("agent_memory_update_failed runId={} message={}", ctx.runId(), ex.getMessage());
        }
    }

    /** 展示审计：终态节点投影（失败不影响主流程）。 */
    private void enterGraphNode(Long runId, SseEventWriter writer, GraphNode node) {
        try {
            graphStateService.enter(runId, node, writer);
        } catch (RuntimeException ex) {
            log.debug("agent_graph_terminal_node_skipped runId={} node={} message={}",
                    runId, node, ex.getMessage());
        }
    }

    private void cleanup(Long runId) {
        approvalSessionStore.removeRun(runId);
        planReviewSessionStore.removeRun(runId);
        planState.clear(runId);
    }

    private static String allStepSummaries(List<AgentStep> steps) {
        StringBuilder builder = new StringBuilder();
        for (AgentStep step : steps) {
            if (step.resultSummary() != null && !step.resultSummary().isBlank()) {
                builder.append("步骤 ").append(step.sequence()).append("：")
                        .append(step.resultSummary()).append("\n");
            }
        }
        return builder.toString();
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "运行失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static void emit(SseEventWriter writer, String name, Object payload) {
        if (writer == null) {
            return;
        }
        try {
            writer.event(name, payload);
        } catch (IOException ignored) {
            // SSE 为可选输出，失败不阻断业务
        }
    }
}
