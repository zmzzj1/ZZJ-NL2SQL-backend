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
        String raw = LlmTextCollector.collect(
                llmClient.openChat(
                        AgentPrompts.SQL_SYSTEM,
                        AgentPrompts.sqlUser(objective, schema, previousSummaries, knowledge)),
                objectMapper);
        return sanitizeSql(raw);
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
