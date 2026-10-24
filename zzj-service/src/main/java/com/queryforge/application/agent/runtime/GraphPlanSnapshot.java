package com.queryforge.application.agent.runtime;

import java.io.Serializable;
import java.util.List;

/**
 * P14-ARCH-01：Graph State 中的结构化计划快照（可序列化）。
 * 由 Planner 节点写入、HITL_GATE/HITL/Manager 消费；MySQL {@code agent_steps} 仍是业务事实源。
 */
public record GraphPlanSnapshot(
        boolean answerable,
        String reason,
        List<GraphPlanStep> steps
) implements Serializable {

    public static GraphPlanSnapshot empty() {
        return new GraphPlanSnapshot(false, "", List.of());
    }

    public boolean hasSteps() {
        return steps != null && !steps.isEmpty();
    }

    /** 第一个依赖全部成功的 PENDING 步骤（顺序推进，不引入 DAG 并行引擎）。 */
    public GraphPlanStep firstReadyStep() {
        if (steps == null) {
            return null;
        }
        for (GraphPlanStep step : steps) {
            if (step.pending() && step.dependenciesSatisfied(steps)) {
                return step;
            }
        }
        return null;
    }

    /** 是否全部步骤都已成功。 */
    public boolean allSucceeded() {
        if (!hasSteps()) {
            return false;
        }
        for (GraphPlanStep step : steps) {
            if (!step.succeeded()) {
                return false;
            }
        }
        return true;
    }

    public boolean anySucceeded() {
        if (steps == null) {
            return false;
        }
        for (GraphPlanStep step : steps) {
            if (step.succeeded()) {
                return true;
            }
        }
        return false;
    }

    /** 是否含报告步骤（REPORT_ASSISTANT/DASHBOARD 角色）。 */
    public boolean hasReportStep() {
        if (steps == null) {
            return false;
        }
        for (GraphPlanStep step : steps) {
            if ("REPORT_ASSISTANT".equals(step.role()) || "DASHBOARD".equals(step.role())) {
                return true;
            }
        }
        return false;
    }

    /** 按 MySQL stepId 查找步骤。 */
    public GraphPlanStep findById(Long stepId) {
        if (steps == null || stepId == null) {
            return null;
        }
        for (GraphPlanStep step : steps) {
            if (stepId.equals(step.stepId())) {
                return step;
            }
        }
        return null;
    }
}
