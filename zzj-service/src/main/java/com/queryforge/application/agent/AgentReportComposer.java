package com.queryforge.application.agent;

import com.queryforge.domain.model.LlmClient;
import com.queryforge.infrastructure.llm.LlmTextCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.AgentPrompts;

/**
 * P14-C：报告导语合成。材料只能来自同一次运行的真实工件（SQL 结果摘要、分析图表与摘要）；
 * 模型不可用或输出为空时返回 null，由调用方给出有明确标识的确定性描述，
 * 不得把“没有导语”伪装成“已生成报告结论”。
 *
 * <p>调用预算由调用方经 {@link AgentLlmCallLimiter} 占用；本类不自行计数。</p>
 */
@Service
public class AgentReportComposer {

    private static final Logger log = LoggerFactory.getLogger(AgentReportComposer.class);
    private static final int MAX_NARRATIVE_CHARS = 1200;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public AgentReportComposer(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /** 生成报告导语；失败/空输出返回 null（调用方走确定性降级）。 */
    public String compose(Long runId, String question, String sqlFacts, String analysisFacts, String chartDigest) {
        try {
            String raw = LlmTextCollector.collect(
                    llmClient.openChat(
                            AgentPrompts.REPORT_COMPOSER_SYSTEM,
                            AgentPrompts.reportComposerUser(question, sqlFacts, analysisFacts, chartDigest)),
                    objectMapper);
            String text = raw == null ? "" : raw.trim();
            if (text.isEmpty()) {
                return null;
            }
            return text.length() > MAX_NARRATIVE_CHARS ? text.substring(0, MAX_NARRATIVE_CHARS) : text;
        } catch (RuntimeException ex) {
            log.warn("agent_report_compose_failed runId={} message={}", runId, ex.getMessage());
            return null;
        }
    }
}
