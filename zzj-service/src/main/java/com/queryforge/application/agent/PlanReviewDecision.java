package com.queryforge.application.agent;

/**
 * P14-D：计划级风险审核的结构化决策。由 {@link PlanReviewGate} 经模型评估后给出，
 * 或由 Service 直接构造（autoConfirm 跳过 / Gate 不可用时降级）。
 *
 * <p>注意：{@code modelEvaluated} 仅在模型真实完成评估时为 true；降级与跳过场景必须为 false，
 * 不得伪造"已审核"以放行任何 SQL/角色授权。
 */
public record PlanReviewDecision(
        boolean needsReview,
        String reason,
        String reasonCode,
        boolean modelEvaluated
) {
    /** autoConfirm=true：跳过计划级评估，不调用 Gate 模型。 */
    public static PlanReviewDecision skip() {
        return new PlanReviewDecision(false, "autoConfirm 跳过计划级审核", "SKIPPED", false);
    }

    /** Gate 真实评估后判定无需人工审核。 */
    public static PlanReviewDecision notRequired(String reason) {
        return new PlanReviewDecision(false, reason == null ? "" : reason, "NOT_REQUIRED", true);
    }

    /** Gate 真实评估后判定需要人工审核。 */
    public static PlanReviewDecision required(String reason) {
        return new PlanReviewDecision(true, reason == null ? "" : reason, "NEEDS_REVIEW", true);
    }

    /** Gate 模型不可用/解析非法：如实标记降级，不伪造 modelEvaluated。 */
    public static PlanReviewDecision unavailable(String code) {
        return new PlanReviewDecision(false, "计划风险审核暂不可用，按原路径继续", code, false);
    }
}
