package com.queryforge.application.agent;

import com.queryforge.domain.model.LlmClient;
import com.queryforge.infrastructure.llm.LlmTextCollector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.agent.AgentRoute;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.AgentPrompts;

/**
 * Manager 任务路由：只输出结构化路由，不查询数据库、不直接生成 SQL。
 *
 * <p>P14-A：分类使用真实授权上下文（原问题 / Schema 摘要 / 授权知识概要 / 压缩会话历史），
 * 模型异常、超时、空串或不合法结构一律降级为 {@link AgentRoute#MEDIUM} 并记录安全原因，
 * 由后端继续走 Planner，不静默放行未验证 SQL，也不把 Run 直接判失败。
 */
@Service
public class ManagerRouter {

    private static final Logger log = LoggerFactory.getLogger(ManagerRouter.class);
    private static final int MAX_REASON_CHARS = 300;
    private static final int MAX_DEGRADE_REASON_CHARS = 200;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public ManagerRouter(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 分类入口。任何模型侧失败都返回 {@link AgentRoute#MEDIUM}（安全降级），不抛出。
     */
    public RouteDecision route(RoutingContext context) {
        try {
            String raw = LlmTextCollector.collect(
                    llmClient.openChat(
                            AgentPrompts.MANAGER_SYSTEM,
                            AgentPrompts.managerUser(
                                    context.originalQuestion(),
                                    context.conversationHistory(),
                                    context.schemaSummary(),
                                    context.evidenceSummary())),
                    objectMapper);
            return parse(raw);
        } catch (RuntimeException ex) {
            log.info("manager_route_degraded reason={}", ex.getClass().getSimpleName());
            return new RouteDecision(AgentRoute.MEDIUM, degradeReason(ex));
        }
    }

    /** 兼容入口：仅有问题与记忆时分类（无 Schema/知识摘要）。 */
    public RouteDecision route(String question, String memory) {
        return route(new RoutingContext(question, "", "", memory == null ? "" : memory));
    }

    /** 供单元测试直接校验解析规则；非法输入按契约抛出，由 {@link #route} 兜底降级。 */
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
            if (parsed == AgentRoute.TOOL_INVOCATION) {
                // 显式工具只能由用户选择触发，模型返回该值一律拒绝。
                throw new ApiException(
                        ErrorCode.AGENT_PLAN_INVALID, HttpStatus.UNPROCESSABLE_ENTITY,
                        "Manager 不得返回显式工具路由");
            }
            String reason = root.path("reasonCode").asText("").trim();
            return new RouteDecision(parsed, reason.isEmpty() ? defaultReason(parsed) : truncate(reason, MAX_REASON_CHARS));
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
            case MEDIUM -> "分类不确定，按规划降级";
            case TOOL_INVOCATION -> "用户显式选择工具";
        };
    }

    /** 降级原因只暴露类别，不泄露模型原文或内部细节。 */
    private static String degradeReason(RuntimeException ex) {
        String code = ex instanceof ApiException api ? api.getErrorCode().name() : ex.getClass().getSimpleName();
        String reason = "分类不可用（" + code + "），已按 Planner 安全降级";
        return truncate(reason, MAX_DEGRADE_REASON_CHARS);
    }

    private static String truncate(String value, int maxChars) {
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }

    /**
     * 分类所需的真实上下文。所有字段都必须是服务端已授权、已裁剪的摘要，
     * 不得包含其他用户的 Schema 或未授权知识。
     */
    public record RoutingContext(
            String originalQuestion,
            String schemaSummary,
            String evidenceSummary,
            String conversationHistory
    ) {
        public RoutingContext {
            originalQuestion = originalQuestion == null ? "" : originalQuestion;
            schemaSummary = schemaSummary == null ? "" : schemaSummary;
            evidenceSummary = evidenceSummary == null ? "" : evidenceSummary;
            conversationHistory = conversationHistory == null ? "" : conversationHistory;
        }
    }

    public record RouteDecision(AgentRoute route, String reason) {
    }
}
