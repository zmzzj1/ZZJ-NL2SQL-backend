package com.queryforge.domain.workflow;

/**
 * 工作流 SSE 具名事件负载。所有事件带 runId / nodeId 归属。
 */
public final class WorkflowStreamEvents {

    private WorkflowStreamEvents() {
    }

    public record WorkflowStarted(Long runId, Long workflowId) {
    }

    public record NodeStarted(Long runId, String nodeId, String nodeType) {
    }

    public record NodeWaitingApproval(Long runId, String nodeId, String message) {
    }

    public record NodeSucceeded(Long runId, String nodeId, String summary) {
    }

    public record NodeFailed(Long runId, String nodeId, String code, String message) {
    }

    public record WorkflowCompleted(Long runId, String summary) {
    }

    public record WorkflowFailed(Long runId, String code, String message) {
    }

    public record WorkflowCanceled(Long runId) {
    }
}
