package com.queryforge.application.agent.runtime;

import java.io.Serializable;
import java.util.List;

/**
 * P14-ARCH-01：Graph State 中的计划步骤快照（可序列化）。
 *
 * <p>与 MySQL {@code agent_steps} 行一一对应：{@code stepId} 是持久化事实主键，
 * 图中只保存执行编排所需的最小字段（状态/依赖/工件引用），不保存 SQL 原文以外的
 * 大对象与执行产物。{@code dependsOn} 支持多个依赖引用（兼容旧单依赖计划）。
 */
public record GraphPlanStep(
        Long stepId,
        int sequence,
        String objective,
        String role,
        List<Integer> dependsOn,
        String toolName,
        String toolArgsJson,
        String status,
        Long artifactRef,
        String resultSummary
) implements Serializable {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    public boolean pending() {
        return STATUS_PENDING.equals(status);
    }

    public boolean succeeded() {
        return STATUS_SUCCEEDED.equals(status);
    }

    /** 该步骤的全部依赖是否都已成功（无依赖视为就绪）。 */
    public boolean dependenciesSatisfied(List<GraphPlanStep> all) {
        if (dependsOn == null || dependsOn.isEmpty()) {
            return true;
        }
        for (Integer dep : dependsOn) {
            GraphPlanStep upstream = findBySequence(all, dep);
            if (upstream == null || !upstream.succeeded()) {
                return false;
            }
        }
        return true;
    }

    public static GraphPlanStep findBySequence(List<GraphPlanStep> steps, int sequence) {
        for (GraphPlanStep step : steps) {
            if (step.sequence() == sequence) {
                return step;
            }
        }
        return null;
    }
}
