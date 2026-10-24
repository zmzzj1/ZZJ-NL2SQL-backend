package com.queryforge.domain.agent;

import com.queryforge.domain.agent.RunStatus;
import java.time.LocalDateTime;
import java.util.List;

public record AgentRunResponse(
        Long id,
        Long conversationId,
        Long connectionId,
        String connectionName,
        String question,
        RunStatus status,
        String route,
        List<RoleActivity> roleActivities,
        List<KnowledgeSourceRef> knowledgeSources,
        int llmCallCount,
        String summary,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<AgentStepResponse> steps,
        String graphState,
        PlanReviewView planReview,
        /**
         * P14-ARCH-01：真实六 Agent Graph 的 threadId（runId↔threadId 归属映射）。
         * 旧 Run 为 null（无可恢复 Graph）；浏览器端不能凭 threadId 绕过服务端身份验证。
         */
        String threadId
) {
    public record RoleActivity(String role, String status) {
    }

    public record KnowledgeSourceRef(Long id, String sourceType, String title, int version) {
    }

    /**
     * P14-D：计划级审核视图（GET RunDetail 刷新恢复来源）。未触发 Gate 的旧 Run 为 {@code null}。
     * {@code status} 为 {@link PlanReviewStatus} 枚举名；{@code plan} 只含可展示字段，
     * 禁止夹带 SQL 候选、凭据或内部 Prompt。
     */
    public record PlanReviewView(
            boolean required,
            String status,
            int hitlVersion,
            String reason,
            int rejectionCount,
            boolean autoConfirm,
            List<PlanStepView> plan
    ) {
    }

    public record PlanStepView(long stepId, int sequence, String objective, String agentRole, Integer dependsOn) {
    }
}
