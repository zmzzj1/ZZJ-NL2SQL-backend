package com.queryforge.application.agent.runtime.worker;

import com.queryforge.application.agent.runtime.GraphPlanStep;

/**
 * P14-ARCH-01：一次 Worker 调用的任务描述（由 Graph Node 构造，不可变）。
 *
 * @param role              受控角色（DATA_SCIENTIST/CODE_ASSISTANT/TOOL_ASSISTANT/DASHBOARD）
 * @param step              计划步骤快照（MySQL stepId 为持久事实）
 * @param attemptId         本次尝试标识（每次派发生成；重试保持 attemptId 以便审计关联）
 * @param attempt           第几次尝试（1-based，受上限约束）
 * @param diagnostics       上一次失败的真实诊断（脱敏、长度受限；首次为空）
 * @param route             本次 Run 的最终路由（Graph 状态事实）
 * @param previousSummaries 前序已成功步骤的真实结果摘要（Graph 计划快照派生）
 * @param upstreamStepId    Code/Report 步骤的上游 SQL 步骤 stepId（节点按计划依赖解析，可为 null）
 */
public record WorkerTask(
        String role,
        GraphPlanStep step,
        String attemptId,
        int attempt,
        String diagnostics,
        String route,
        String previousSummaries,
        Long upstreamStepId
) {

    public Long stepId() {
        return step == null ? null : step.stepId();
    }
}
