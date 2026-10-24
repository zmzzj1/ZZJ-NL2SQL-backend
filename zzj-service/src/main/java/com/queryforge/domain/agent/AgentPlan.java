package com.queryforge.domain.agent;

import java.util.List;
import java.util.Map;

/**
 * Planner 的结构化输出。answerable=false 时 steps 为空，reason 说明缺失信息。
 *
 * <p>P14-C：步骤可携带可选 {@code agent}（受控角色名）与 {@code dependsOn}
 * （必须引用已经出现的步骤）。二者缺失时保持旧行为（仅 objective，
 * 由 RoleAssigner 推断角色、按线性顺序依赖）。</p>
 *
 * <p>P14-E：当计划中确实存在可执行的工具步骤时，步骤可携带 {@link ToolCall}
 * 声明真实注册的工具名与受控参数；没有该声明时不允许由服务端猜测工具，
 * 执行侧会以「缺少可执行工具信息」明确失败。</p>
 */
public record AgentPlan(boolean answerable, String reason, List<Step> steps) {

    public record Step(String objective, String agent, Integer dependsOn, ToolCall tool) {

        /** 旧契约：仅objective；角色由服务端推断，依赖按线性顺序。 */
        public Step(String objective) {
            this(objective, null, null, null);
        }

        /** P14-C 契约：携带角色与依赖，不携带工具身份。 */
        public Step(String objective, String agent, Integer dependsOn) {
            this(objective, agent, dependsOn, null);
        }
    }

    /**
     * 计划内的受控工具身份。工具名必须是真实注册名（内置或当前用户已连接的 MCP），
     * args 只来自计划本身，不来自模型回复的自由文字。
     */
    public record ToolCall(String name, Map<String, Object> args) {
    }
}
