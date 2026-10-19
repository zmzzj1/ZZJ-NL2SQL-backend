package com.queryforge.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.platform.entity.AgentRoute;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Manager 任务路由：只输出结构化路由，不查询数据库、不直接生成 SQL。
 */
@Service
public class ManagerRouter {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public ManagerRouter(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public RouteDecision route(String question, String memory) {
        String raw = LlmTextCollector.collect(
                llmClient.openChat(AgentPrompts.MANAGER_SYSTEM, AgentPrompts.managerUser(question, memory)),
                objectMapper);
        return parse(raw);
    }

    /** 供单元测试直接校验解析规则。 */
    RouteDecision parse(String raw) {
        String json = extractJson(raw);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (IOException ex) {
            throw new ApiException(
                    ErrorCode.AGENT_PLAN_INVALID, HttpStatus.UNPROCESSABLE_ENTITY, "Manager 未返回合法 JSON");
        }
        String route = root.path("route").asText("").trim().toUpperCase();
        try {
            AgentRoute parsed = AgentRoute.valueOf(route);
            String reason = root.path("reasonCode").asText("").trim();
            return new RouteDecision(parsed, reason.isEmpty() ? defaultReason(parsed) : truncate(reason, 300));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(
                    ErrorCode.AGENT_PLAN_INVALID, HttpStatus.UNPROCESSABLE_ENTITY, "Manager 返回了未知路由");
        }
    }

    private static String extractJson(String raw) {
        if (raw == null) {
            throw new ApiException(ErrorCode.AGENT_PLAN_INVALID, HttpStatus.UNPROCESSABLE_ENTITY, "Manager 未返回内容");
        }
        String cleaned = raw.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ApiException(ErrorCode.AGENT_PLAN_INVALID, HttpStatus.UNPROCESSABLE_ENTITY, "Manager 未返回 JSON 对象");
        }
        return cleaned.substring(start, end + 1);
    }

    private static String defaultReason(AgentRoute route) {
        return switch (route) {
            case CHITCHAT -> "闲聊问题";
            case CLARIFY -> "信息不足，需要澄清";
            case KNOWLEDGE -> "业务知识解释";
            case SIMPLE -> "单条 SQL 即可";
            case COMPLEX -> "需要多步分析";
        };
    }

    private static String truncate(String value, int maxChars) {
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }

    public record RouteDecision(AgentRoute route, String reason) {
    }
}
