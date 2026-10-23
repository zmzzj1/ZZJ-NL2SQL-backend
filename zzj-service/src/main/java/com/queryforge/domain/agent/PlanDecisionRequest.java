package com.queryforge.domain.agent;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * P14-D：计划级审核决定请求。由 {@code POST /v1/agent/runs/{runId}/plan/continue/stream} 接收。
 *
 * <p>userId/workspaceId/connectionId 一律从登录态与 Run 持久化事实派生，绝不接受前端伪造。
 * {@code approved} 与 {@code hitlVersion} 必填；{@code feedback} 仅在拒绝时由服务端校验后注入 Planner。
 */
public record PlanDecisionRequest(
        /** 是否批准当前待审核计划版本；拒绝时传 false。 */
        @NotNull(message = "approved 不能为空")
        Boolean approved,

        /** 拒绝时附带的修改意见（最长 4000 字符）。批准时忽略。 */
        @Size(max = 4000, message = "feedback 最长 4000 字符")
        String feedback,

        /** 当前待审核的计划版本（与 Run 持久化的 plan_review_version 比对）。 */
        @NotNull(message = "hitlVersion 不能为空")
        Integer hitlVersion
) {
}
