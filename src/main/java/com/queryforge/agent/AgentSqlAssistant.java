package com.queryforge.agent;
import com.queryforge.integration.llm.LlmClient;
import com.queryforge.integration.llm.LlmTextCollector;
import com.queryforge.query.execution.ReadOnlySqlGuard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

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
        String system = buildSqlSystem(customSystemPrompt);
        String raw = LlmTextCollector.collect(
                llmClient.openChat(
                        system,
                        AgentPrompts.sqlUserWithGuidance(objective, schema, previousSummaries, knowledge, skillGuidance)),
                objectMapper);
        return sanitizeSql(raw);
    }

    /** 平台安全指令始终保留；自定义提示词只作为补充，禁止覆盖只读/审批/工具权限约束。 */
    private static String buildSqlSystem(String customSystemPrompt) {
        if (customSystemPrompt == null || customSystemPrompt.isBlank()) {
            return AgentPrompts.SQL_SYSTEM;
        }
        return AgentPrompts.SQL_SYSTEM + "\n\n补充要求（不得违反上述硬性安全约束）：\n" + customSystemPrompt;
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
