package com.queryforge.application.agent.runtime;

import java.io.Serializable;

/**
 * P14-ARCH-01：Worker 结构化执行结果（可序列化，进入 Graph State）。
 *
 * <p>{@code success} 只能由真实 Action 输出验证后产生，绝不以模型宣称完成替代执行验证；
 * {@code resultRef} 指向真实工件（analysisRunId/reportId/查询历史 id），失败时为 null；
 * {@code retryable} 区分可恢复失败与终态失败，由 Manager 统一判断重试或终结。
 */
public record WorkerOutcome(
        boolean success,
        String role,
        Long stepId,
        String attemptId,
        Long resultRef,
        String summary,
        boolean retryable,
        String failureCode,
        String failureReason
) implements Serializable {

    public static WorkerOutcome success(String role, Long stepId, String attemptId, Long resultRef, String summary) {
        return new WorkerOutcome(true, role, stepId, attemptId, resultRef, summary, false, null, null);
    }

    public static WorkerOutcome failure(String role, Long stepId, String attemptId, boolean retryable,
                                       String failureCode, String failureReason) {
        return new WorkerOutcome(false, role, stepId, attemptId, null, null, retryable,
                failureCode, failureReason);
    }
}
