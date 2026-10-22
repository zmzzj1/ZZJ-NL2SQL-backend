package com.queryforge.domain.agent;

import java.util.List;

/**
 * Planner 的结构化输出。answerable=false 时 steps 为空，reason 说明缺失信息。
 *
 * <p>P14-C：步骤可携带可选 {@code agent}（受控角色名）与 {@code dependsOn}
 * （必须引用已经出现的步骤）。二者缺失时保持旧行为（仅 objective，
 * 由 RoleAssigner 推断角色、按线性顺序依赖）。
 */
public record AgentPlan(boolean answerable, String reason, List<Step> steps) {

    public record Step(String objective, String agent, Integer dependsOn) {

        /** 旧契约：仅objective；角色由服务端推断，依赖按线性顺序。 */
        public Step(String objective) {
            this(objective, null, null);
        }
    }
}
