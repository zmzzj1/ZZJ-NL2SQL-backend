package com.queryforge.domain.agent;

import java.util.Locale;

/**
 * P14-D：计划级 HITL 审核状态。与 {@code agent_runs.plan_review_status} 列一一对应，
 * 作为 MySQL 持久状态机的补充事实来源（Run 状态为 {@code WAITING_PLAN_REVIEW} 时等待审核）。
 */
public enum PlanReviewStatus {
    /** autoConfirm 跳过计划级审核。 */
    SKIPPED,
    /** Gate 评估后判定无需人工审核。 */
    NOT_REQUIRED,
    /** 正在等待用户审核决策。 */
    AWAITING,
    /** 用户已批准当前版本计划。 */
    APPROVED,
    /** 用户拒绝后需要重新规划（Run 回到 PLANNING）。 */
    REPLAN_REQUIRED,
    /** 达到拒绝上限，Run 终结。 */
    REJECTED,
    /** Gate 模型不可用/解析失败，按原路径继续但不伪造已审核。 */
    EVAL_UNAVAILABLE;

    public static PlanReviewStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PlanReviewStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
