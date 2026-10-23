package com.queryforge.application.agent;

import com.queryforge.domain.agent.AgentPlan;
import com.queryforge.domain.agent.AgentPrompts;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.model.LlmClient;
import com.queryforge.infrastructure.llm.LlmTextCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * P14-D：计划级风险审核 Gate。
 *
 * <p>只负责从"已通过后端合法性校验的计划"与"用户原始问题"构造模型请求，判断是否需要人工审核；
 * 不运行 SQL、不派发 Python、不调用工具、不修改计划内容，也绝不向模型输出赋予任何权限。
 * 评估失败时如实降级（{@code modelEvaluated=false}），不伪造"已审核"以放行任何授权。
 */
@Service
public class PlanReviewGate {

    private static final Logger log = LoggerFactory.getLogger(PlanReviewGate.class);
    private static final int MAX_REASON_CHARS = 300;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;
    private final AgentLlmCallLimiter llmLimiter;

    public PlanReviewGate(LlmClient llmClient, ObjectMapper objectMapper, AgentProperties properties,
                          AgentLlmCallLimiter llmLimiter) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.llmLimiter = llmLimiter;
    }

    /**
     * 评估计划是否需人工审核。{@code effectiveContext} 为已授权的有限业务上下文（仅作参考，不授予权限）。
     * 模型调用失败/配额不足/解析非法时返回降级决策，调用方照常继续原执行链的安全边界。
     */
    public PlanReviewDecision evaluate(AgentRun run, String effectiveContext, AgentPlan plan) {
        try {
            String planText = renderPlan(plan);
            String raw = llmLimiter.count(run.id(), () -> LlmTextCollector.collect(
                    llmClient.openChat(AgentPrompts.PLAN_REVIEW_SYSTEM,
                            AgentPrompts.planReviewUser(run.question(), planText, effectiveContext)),
                    objectMapper));
            return parse(raw);
        } catch (RuntimeException ex) {
            log.warn("plan_review_failed runId={} message={}", run.id(), ex.getMessage());
            return PlanReviewDecision.unavailable("GATE_UNAVAILABLE");
        }
    }

    private PlanReviewDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PlanReviewDecision.unavailable("GATE_UNPARSEABLE");
        }
        String cleaned = raw.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return PlanReviewDecision.unavailable("GATE_UNPARSEABLE");
        }
        try {
            JsonNode root = objectMapper.readTree(cleaned.substring(start, end + 1));
            boolean needsReview = root.path("needs_review").asBoolean(false);
            String reason = root.path("reason").asText("").trim();
            return new PlanReviewDecision(needsReview, truncate(reason),
                    needsReview ? "NEEDS_REVIEW" : "NOT_REQUIRED", true);
        } catch (IOException ex) {
            log.warn("plan_review_parse_failed length={}", raw.length());
            return PlanReviewDecision.unavailable("GATE_UNPARSEABLE");
        }
    }

    /** 把已校验计划渲染为 Gate 可读的文本（仅步骤/角色/依赖/序号，不含 SQL 候选或敏感内容）。 */
    private static String renderPlan(AgentPlan plan) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (AgentPlan.Step step : plan.steps()) {
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

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_REASON_CHARS ? value.substring(0, MAX_REASON_CHARS) : value;
    }
}
