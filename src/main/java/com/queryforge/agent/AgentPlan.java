package com.queryforge.agent;

import java.util.List;

/**
 * Planner 的结构化输出。answerable=false 时 steps 为空，reason 说明缺失信息。
 */
public record AgentPlan(boolean answerable, String reason, List<Step> steps) {

    public record Step(String objective) {
    }
}
