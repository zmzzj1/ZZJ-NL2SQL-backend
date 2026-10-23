package com.queryforge.domain.agent;

import java.time.LocalDateTime;

/** 一次 SQL Agent 运行；状态由服务端维护，前端只展示。 */
public record AgentRun(
        Long id,
        Long userId,
        Long conversationId,
        Long connectionId,
        String connectionName,
        String question,
        RunStatus status,
        String route,
        String roleActivities,
        String knowledgeSources,
        int llmCallCount,
        String summary,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long agentId,
        Long agentVersionId,
        String resolvedSkillVersions,
        String toolPolicyHash,
        /** P14-D：创建时固定的计划级审核模式；true 跳过计划级风险评估与人工审核。 */
        Boolean planAutoConfirm,
        /** P14-D：当前待审核的计划版本（单调递增）。 */
        int planReviewVersion,
        /** P14-D：计划审核状态（SKIPPED/NOT_REQUIRED/AWAITING/APPROVED/REPLAN_REQUIRED/REJECTED/EVAL_UNAVAILABLE）。 */
        String planReviewStatus,
        /** P14-D：是否正在要求人工审核。 */
        Boolean planReviewRequired,
        /** P14-D：审核原因（经长度/内容限制）。 */
        String planReviewReason,
        /** P14-D：当前 Run 已拒绝次数（最大 3）。 */
        int planRejectionCount,
        /** P14-D：最近一次用户反馈，供重新规划（最长 4000 字符）。 */
        String planReviewFeedback
) {
}
