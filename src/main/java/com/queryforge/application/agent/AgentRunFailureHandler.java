package com.queryforge.application.agent;

import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.graph.GraphNode;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent 运行失败终结与短期状态清理：步骤/运行置 FAILED、发出失败事件、
 * 清理待审批令牌与计划快照；客户端断开时标记执行状态未知。
 *
 * <p>所有写操作失败均不影响调用方主流程（Quietly 变体吞掉事件写入异常）。
 */
@Service
public class AgentRunFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentRunFailureHandler.class);
    private static final int MAX_ERROR_MESSAGE_CHARS = 300;

    private final AgentRunRepository repository;
    private final AgentGraphStateService graphStateService;
    private final ApprovalSessionStore approvalSessionStore;
    private final AgentPlanStateService planState;

    public AgentRunFailureHandler(
            AgentRunRepository repository,
            AgentGraphStateService graphStateService,
            ApprovalSessionStore approvalSessionStore,
            AgentPlanStateService planState) {
        this.repository = repository;
        this.graphStateService = graphStateService;
        this.approvalSessionStore = approvalSessionStore;
        this.planState = planState;
    }

    /** 步骤与运行一并失败，发出 step.failed + run.failed，并清理短期状态。 */
    public void failStepAndRun(Long runId, Long stepId, ErrorCode code, String message,
                               SseEventWriter writer) throws IOException {
        String safe = safeMessage(message);
        repository.failStep(stepId, code.name(), safe);
        repository.updateRunStatus(runId, RunStatus.FAILED, null, code.name(), safe);
        graphStateService.enter(runId, GraphNode.FAILED, writer);
        writer.event("step.failed", new AgentStreamEvents.StepFailed(stepId, code.name(), safe));
        writer.event("run.failed", new AgentStreamEvents.RunFailed(runId, code.name(), safe));
        // P12 S4：终态清理短期状态。
        approvalSessionStore.removeRun(runId);
        planState.clear(runId);
    }

    /** 失败降级：事件写入失败时至少保证步骤状态落库。 */
    public void failStepAndRunQuietly(Long runId, Long stepId, String code, String message,
                                      SseEventWriter writer) {
        try {
            String safe = safeMessage(message);
            repository.failStep(stepId, code, safe);
            repository.updateRunStatus(runId, RunStatus.FAILED, null, code, safe);
            writer.event("step.failed", new AgentStreamEvents.StepFailed(stepId, code, safe));
            writer.event("run.failed", new AgentStreamEvents.RunFailed(runId, code, safe));
        } catch (IOException | RuntimeException ex) {
            log.debug("agent_fail_event_skipped runId={}", runId);
        }
    }

    /** 无步骤粒度的运行失败（规划期/路由期），清理短期状态。 */
    public void failRunWithEvent(Long runId, String code, String message, SseEventWriter writer)
            throws IOException {
        String safe = safeMessage(message);
        repository.updateRunStatus(runId, RunStatus.FAILED, null, code, safe);
        graphStateService.enter(runId, GraphNode.FAILED, writer);
        writer.event("run.failed", new AgentStreamEvents.RunFailed(runId, code, safe));
        // P12 S4：终态清理短期状态。
        approvalSessionStore.removeRun(runId);
        planState.clear(runId);
    }

    public void failRunWithEventQuietly(Long runId, String code, String message, SseEventWriter writer) {
        try {
            failRunWithEvent(runId, code, message, writer);
        } catch (IOException | RuntimeException ex) {
            log.debug("agent_run_fail_event_skipped runId={}", runId);
        }
    }

    /** 客户端断开：执行状态未知，仅当步骤仍可变更时置失败。 */
    public void markInterrupted(Long runId, Long stepId) {
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

    /** 客户端断开（规划期）：尽量取消运行与待定步骤，失败仅记录。 */
    public void cancelRunQuietly(Long userId, Long runId) {
        try {
            if (repository.cancelActiveRun(runId, userId) > 0) {
                repository.cancelPendingSteps(runId);
            }
        } catch (RuntimeException ex) {
            log.warn("agent_cancel_failed runId={}", runId);
        }
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "运行失败";
        }
        return message.length() > MAX_ERROR_MESSAGE_CHARS
                ? message.substring(0, MAX_ERROR_MESSAGE_CHARS)
                : message;
    }
}
