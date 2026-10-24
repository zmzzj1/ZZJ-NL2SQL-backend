package com.queryforge.application.agent;
import com.queryforge.domain.model.LlmClient;
import com.queryforge.infrastructure.llm.LlmTextCollector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.AgentPlan;
import com.queryforge.domain.agent.AgentPrompts;

/**
 * 可行性判断与规划。模型只输出 JSON 计划，服务端负责解析、步数上限与合法性校验。
 *
 * <p>P14-C：步骤可带受控角色（{@code agent}）与依赖（{@code dependsOn}）。
 * 角色必须是 {@link #PLAN_ROLES} 内的受控枚举；依赖只能引用已经出现的步骤，
 * 不允许自环/越界/未支持类型。LLM 未返回角色时置 null，由 RoleAssigner 推断。
 */
@Service
public class AgentPlanner {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanner.class);
    private static final int MAX_OBJECTIVE_CHARS = 300;
    private static final int MAX_TOOL_NAME_CHARS = 120;
    private static final int MAX_TOOL_ARGS_CHARS = 4096;
    /**
     * 计划内允许的角色。P14-E 允许计划显式携带工具步骤（{@code TOOL_ASSISTANT}），
     * 但工具身份必须由计划本身给出，服务端不会代为挑选。
     */
    static final List<String> PLAN_ROLES =
            List.of("DATA_ANALYST", "CODE_ANALYST", "REPORT_ASSISTANT", "TOOL_ASSISTANT");

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;

    public AgentPlanner(LlmClient llmClient, ObjectMapper objectMapper, AgentProperties properties) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public AgentPlan plan(String question, String schema) {
        return plan(question, schema, "");
    }

    /**
     * 规划入口。{@code effectiveContext} 是本次运行真实生效的分层上下文（相关历史与知识约束），
     * 仅作为口径参考，不授予任何权限，也不被视为授权指令。
     */
    public AgentPlan plan(String question, String schema, String effectiveContext) {
        String raw = LlmTextCollector.collect(
                llmClient.openChat(
                        AgentPrompts.PLANNER_SYSTEM,
                        AgentPrompts.plannerUser(question, schema, effectiveContext)),
                objectMapper);
        return parse(raw);
    }

    /**
     * P14-D：计划被用户拒绝后的重新规划。复用 {@link #PLANNER_SYSTEM} 的合法结构化契约，
     * 注入用户原始问题、被拒绝计划的受控摘要、用户修改意见与拒绝次数。
     *
     * <p>用户修改意见只影响任务意图与计划步骤，不得覆盖系统安全约束/角色白名单/数据源授权；
     * 解析与校验（角色/依赖/步骤预算）复用既有逻辑，非法计划会抛 {@code ApiException}，
     * 由调用方按失败处理，绝不回用已被拒绝的计划。
     *
     * @throws ApiException 模型返回的重新规划不合法（角色/依赖越界或步骤超限）
     */
    public AgentPlan replan(Long runId, String originalQuestion, String authorizedSchema, String effectiveContext,
                           List<AgentPlan.Step> rejectedSteps, String feedback, int rejectionCount) {
        String rejectedPlanText = renderRejected(rejectedSteps);
        String raw = LlmTextCollector.collect(
                llmClient.openChat(
                        AgentPrompts.PLANNER_SYSTEM,
                        AgentPrompts.planReplanUser(originalQuestion, rejectedPlanText, feedback,
                                rejectionCount, effectiveContext, properties.maxSteps())),
                objectMapper);
        return parse(raw);
    }

    /** 把被拒绝计划渲染为 Gate/Planner 可读的受控摘要（仅步骤/角色/依赖，不含任何执行产物）。 */
    private static String renderRejected(List<AgentPlan.Step> rejectedSteps) {
        if (rejectedSteps == null || rejectedSteps.isEmpty()) {
            return "（无计划内容）";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (AgentPlan.Step step : rejectedSteps) {
            i++;
            sb.append(i).append(". [").append(step.agent() == null ? "SQL_AGENT" : step.agent())
                    .append("] ").append(step.objective());
            if (step.dependsOn() != null) {
                sb.append(" (依赖步骤 ").append(step.dependsOn()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 供单元测试直接校验解析规则。 */
    AgentPlan parse(String raw) {
        String json = extractJson(raw);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (IOException ex) {
            log.warn("agent_plan_parse_failed length={}", raw == null ? 0 : raw.length());
            throw invalid("模型未返回合法的 JSON 计划");
        }

        boolean answerable = root.path("answerable").asBoolean(true);
        String reason = root.path("reason").asText("").trim();
        List<AgentPlan.Step> steps = new ArrayList<>();
        int appeared = 0;
        for (JsonNode node : root.path("steps")) {
            String objective = firstNonBlank(
                    node.path("objective").asText(""),
                    node.path("description").asText(""),
                    node.path("question").asText(""));
            if (objective.isBlank()) {
                continue;
            }
            appeared++;
            String role = parseRole(node.path("agent").asText(""));
            AgentPlan.ToolCall tool = parseToolCall(node.path("tool"));
            if (tool == null && "TOOL_ASSISTANT".equals(role)) {
                // 角色声明为工具步骤却没有真实工具身份：不猜、不静默降级为 SQL 步骤。
                throw invalid("计划中的工具步骤缺少可执行工具信息");
            }
            steps.add(new AgentPlan.Step(
                    truncate(objective, MAX_OBJECTIVE_CHARS),
                    role,
                    parseDependsOn(node.path("dependsOn"), appeared),
                    tool));
        }

        if (!answerable) {
            return new AgentPlan(false, reason.isEmpty() ? "当前数据源无法回答该问题" : truncate(reason, 300), List.of());
        }
        if (steps.isEmpty()) {
            throw invalid("计划没有任何可执行步骤");
        }
        if (steps.size() > properties.maxSteps()) {
            throw invalid("计划步骤数超过上限 " + properties.maxSteps());
        }
        return new AgentPlan(true, "", List.copyOf(steps));
    }

    /**
     * 解析计划内受控工具身份。缺失/空返回 null；名称只做形态与长度校验，
     * 是否真实注册、是否在 Agent 白名单、是否属于当前用户由执行侧二次核验。
     */
    private AgentPlan.ToolCall parseToolCall(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw invalid("计划中的工具声明必须是对象");
        }
        String name = node.path("name").asText("").trim();
        if (name.isEmpty()) {
            throw invalid("计划中的工具步骤缺少工具名");
        }
        if (name.length() > MAX_TOOL_NAME_CHARS) {
            throw invalid("计划中的工具名超长");
        }
        JsonNode argsNode = node.path("args");
        Map<String, Object> args = new LinkedHashMap<>();
        if (!argsNode.isMissingNode() && !argsNode.isNull()) {
            if (!argsNode.isObject()) {
                throw invalid("计划中的工具参数必须是 JSON 对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.convertValue(argsNode, Map.class);
            args.putAll(parsed);
        }
        try {
            if (objectMapper.writeValueAsString(args).length() > MAX_TOOL_ARGS_CHARS) {
                throw invalid("计划中的工具参数超出长度上限");
            }
        } catch (IOException ex) {
            throw invalid("计划中的工具参数不合法");
        }
        return new AgentPlan.ToolCall(name, Map.copyOf(args));
    }

    /** 角色必须落在受控枚举内；缺失/空返回 null（服务端推断），未知值拒绝整个计划。 */
    private static String parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String role = raw.trim().toUpperCase(Locale.ROOT);
        if (!PLAN_ROLES.contains(role)) {
            throw invalid("计划包含不受支持的角色：" + truncate(raw.trim(), 40));
        }
        return role;
    }

    /** 依赖只能引用已经出现的步骤（1-based），禁止自环与越界。 */
    private static Integer parseDependsOn(JsonNode node, int appeared) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        int value;
        if (node.isInt()) {
            value = node.asInt();
        } else {
            String text = node.asText("").trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                value = Integer.parseInt(text);
            } catch (NumberFormatException ex) {
                throw invalid("计划依赖步骤编号不合法");
            }
        }
        if (value < 1 || value >= appeared) {
            throw invalid("计划依赖步骤编号越界或尚未出现：" + value);
        }
        return value;
    }

    private static String extractJson(String raw) {
        if (raw == null) {
            throw invalid("模型未返回内容");
        }
        String cleaned = raw.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw invalid("模型未返回 JSON 对象");
        }
        return cleaned.substring(start, end + 1);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String truncate(String value, int maxChars) {
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.AGENT_PLAN_INVALID, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
