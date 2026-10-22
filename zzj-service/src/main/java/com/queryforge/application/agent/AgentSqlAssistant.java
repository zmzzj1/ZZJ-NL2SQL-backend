package com.queryforge.application.agent;
import com.queryforge.domain.model.LlmClient;
import com.queryforge.infrastructure.llm.LlmTextCollector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.AgentPrompts;
import java.util.List;

/**
 * Agent 的 SQL 生成、修复与结果总结。所有输出在使用前仍要经过 ReadOnlySqlGuard。
 */
@Service
public class AgentSqlAssistant {

    private static final int MAX_SUMMARY_CHARS = 1200;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public AgentSqlAssistant(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public String generateSql(String objective, String schema, String previousSummaries, String knowledge) {
        return generateSql(objective, schema, previousSummaries, knowledge, "", "");
    }

    /**
     * 生成 SQL，支持 Agent 自定义系统提示词（追加在平台硬性安全指令之后，不覆盖）与
     * 已选中的 Skill 指引（作为受控上下文注入，不授予任何权限）。
     */
    public String generateSql(String objective, String schema, String previousSummaries, String knowledge,
                              String customSystemPrompt, String skillGuidance) {
        return generateSql(objective, schema, previousSummaries, knowledge, customSystemPrompt, skillGuidance, "");
    }

    /**
     * 生成 SQL。{@code effectiveContext} 是本次运行真实构造并生效的分层上下文（L1–L4），
     * 与 Manager/Planner 消费同一条受控上下文；为空时不影响既有行为。
     */
    public String generateSql(String objective, String schema, String previousSummaries, String knowledge,
                              String customSystemPrompt, String skillGuidance, String effectiveContext) {
        String system = buildSqlSystem(customSystemPrompt);
        String raw = LlmTextCollector.collect(
                llmClient.openChat(
                        system,
                        AgentPrompts.sqlUserWithGuidance(
                                objective, schema, previousSummaries, knowledge, skillGuidance, effectiveContext)),
                objectMapper);
        return sanitizeSql(raw);
    }

    /**
     * P14-B：生成第 {@code candidateIndex} 个候选 SQL。与单路径共用同一份受控上下文与
     * 平台硬性安全指令；{@code strategy} 只改变表达策略。输出仍需 Guard 校验。
     */
    public String generateCandidateSql(
            String objective, String schema, String previousSummaries, String knowledge,
            String customSystemPrompt, String skillGuidance, String effectiveContext,
            int candidateIndex, String strategy, String dialect) {
        String system = buildCandidateSystem(customSystemPrompt);
        String raw = LlmTextCollector.collect(
                llmClient.openChat(
                        system,
                        AgentPrompts.sqlCandidateUser(
                                objective, schema, previousSummaries, knowledge, skillGuidance,
                                effectiveContext, candidateIndex, strategy, dialect)),
                objectMapper);
        return sanitizeSql(raw);
    }

    /**
     * P14-B：候选评分原文（JSON 数组文本）。解析、越界/重复拒绝与规则降级由
     * {@code SqlCandidateScorer} 负责；本方法不做任何兜底改写。
     */
    public String scoreCandidates(String objective, String schema, List<String> candidates) {
        return LlmTextCollector.collect(
                llmClient.openChat(
                        AgentPrompts.SQL_SCORER_SYSTEM,
                        AgentPrompts.sqlScorerUser(objective, schema, candidates)),
                objectMapper);
    }

    /**
     * P14-A：闲聊自然语言回答。使用受控 system 提示，不生成 SQL/Python/JSON/报告，
     * 不访问数据库或工具。空响应返回空串，由调用方决定兜底策略。
     */
    public String chitchatAnswer(String question, String conversationContext) {
        String raw = LlmTextCollector.collect(
                llmClient.openChat(
                        AgentPrompts.CHITCHAT_SYSTEM,
                        AgentPrompts.chitchatUser(question, conversationContext)),
                objectMapper);
        String answer = raw == null ? "" : raw.trim();
        if (answer.length() > MAX_SUMMARY_CHARS) {
            return answer.substring(0, MAX_SUMMARY_CHARS);
        }
        return answer;
    }

    /** 平台安全指令始终保留；自定义提示词只作为补充，禁止覆盖只读/审批/工具权限约束。 */
    private static String buildSqlSystem(String customSystemPrompt) {
        if (customSystemPrompt == null || customSystemPrompt.isBlank()) {
            return AgentPrompts.SQL_SYSTEM;
        }
        return AgentPrompts.SQL_SYSTEM + "\n\n补充要求（不得违反上述硬性安全约束）：\n" + customSystemPrompt;
    }

    /** P14-B：候选生成的系统提示同样保留平台硬性约束，自定义提示词只作补充。 */
    private static String buildCandidateSystem(String customSystemPrompt) {
        if (customSystemPrompt == null || customSystemPrompt.isBlank()) {
            return AgentPrompts.SQL_CANDIDATE_SYSTEM;
        }
        return AgentPrompts.SQL_CANDIDATE_SYSTEM
                + "\n\n补充要求（不得违反上述硬性安全约束）：\n" + customSystemPrompt;
    }

    public String knowledgeAnswer(String question, String knowledge) {
        String raw = LlmTextCollector.collect(
                llmClient.openChat(AgentPrompts.KNOWLEDGE_SYSTEM, AgentPrompts.knowledgeUser(question, knowledge)),
                objectMapper);
        String answer = raw == null ? "" : raw.trim();
        return answer.isEmpty() ? "根据已有业务知识，暂无法给出可靠解释。" : answer;
    }

    public String memorySummary(String previousSummary, String question, String answer) {
        String raw = LlmTextCollector.collect(
                llmClient.openChat(
                        AgentPrompts.MEMORY_SYSTEM,
                        AgentPrompts.memoryUser(previousSummary, question, answer)),
                objectMapper);
        String summary = raw == null ? "" : raw.trim();
        return summary.length() > MAX_SUMMARY_CHARS ? summary.substring(0, MAX_SUMMARY_CHARS) : summary;
    }

    public String fixSql(String sql, String errorMessage, String objective, String schema) {
        String raw = LlmTextCollector.collect(
                llmClient.openChat(
                        AgentPrompts.FIXER_SYSTEM,
                        AgentPrompts.fixerUser(sql, errorMessage, objective, schema)),
                objectMapper);
        return sanitizeSql(raw);
    }

    public String summarize(String question, String stepResults) {
        String raw = LlmTextCollector.collect(
                llmClient.openChat(AgentPrompts.SUMMARY_SYSTEM, AgentPrompts.summaryUser(question, stepResults)),
                objectMapper);
        String summary = raw == null ? "" : raw.trim();
        if (summary.isEmpty()) {
            return "查询已完成，但模型未返回结论。";
        }
        return summary.length() > MAX_SUMMARY_CHARS ? summary.substring(0, MAX_SUMMARY_CHARS) : summary;
    }

    /** 去掉代码围栏、前缀与结尾分号；内容仍需 Guard 校验。 */
    static String sanitizeSql(String raw) {
        if (raw == null) {
            return "";
        }
        String sql = raw.replace("```sql", "").replace("```SQL", "").replace("```", "").trim();
        if (sql.regionMatches(true, 0, "SQL:", 0, 4)) {
            sql = sql.substring(4).trim();
        }
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }
}
