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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.AgentPlan;
import com.queryforge.domain.agent.AgentPrompts;

/**
 * 可行性判断与规划。模型只输出 JSON 计划，服务端负责解析、步数上限与合法性校验。
 */
@Service
public class AgentPlanner {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanner.class);
    private static final int MAX_OBJECTIVE_CHARS = 300;

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
        for (JsonNode node : root.path("steps")) {
            String objective = firstNonBlank(
                    node.path("objective").asText(""),
                    node.path("description").asText(""),
                    node.path("question").asText(""));
            if (!objective.isBlank()) {
                steps.add(new AgentPlan.Step(truncate(objective, MAX_OBJECTIVE_CHARS)));
            }
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
