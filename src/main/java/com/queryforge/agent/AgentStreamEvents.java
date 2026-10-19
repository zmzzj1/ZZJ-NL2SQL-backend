package com.queryforge.agent;

import java.util.List;

/**
 * Agent 流式事件负载。事件名与前端 api/agent.ts 及
 * frontend/docs/P4-FB04-api-contract.md 保持一致。
 */
public final class AgentStreamEvents {

    private AgentStreamEvents() {
    }

    public record RunStarted(Long runId, Long conversationId) {
    }

    public record PlanStep(Long stepId, int sequence, String objective) {
    }

    public record PlanReady(Long runId, List<PlanStep> steps) {
    }

    public record StepStarted(Long stepId, int sequence) {
    }

    public record StepSqlReady(Long stepId, String sql, int sqlVersion) {
    }

    public record ApprovalRequired(Long stepId, int sqlVersion) {
    }

    public record StepRepairing(Long stepId, int attempt, String reasonCode) {
    }

    public record StepSucceeded(Long stepId, int rowCount, boolean truncated, String summary) {
    }

    public record StepFailed(Long stepId, String code, String message) {
    }

    public record RunCompleted(Long runId, String summary) {
    }

    public record RunFailed(Long runId, String code, String message) {
    }

    public record RunCanceled(Long runId) {
    }

    public record AgentRouted(Long runId, String route, String reasonCode) {
    }

    public record AgentStarted(Long runId, String role, String activityId) {
    }

    public record AgentCompleted(Long runId, String role, String activityId, String status) {
    }

    public record KnowledgeSourceView(Long id, String sourceType, String title, int version) {
    }

    public record KnowledgeRetrieved(Long runId, List<KnowledgeSourceView> sources) {
    }

    public record ContextPrepared(Long runId, int usedTurns, boolean hasSummary) {
    }

    public record ClarifyRequired(Long runId, String message) {
    }
}
