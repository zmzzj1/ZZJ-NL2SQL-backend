package com.queryforge.application.agent;

import com.queryforge.domain.analysis.AnalysisProperties;
import com.queryforge.domain.analysis.ChartSchema;
import com.queryforge.domain.model.LlmClient;
import com.queryforge.infrastructure.llm.LlmTextCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.AgentPrompts;

/**
 * P14-C：Code Analyst 的模型助手：生成隔离沙箱内运行的 Python 代码、解释真实分析结果。
 *
 * <p>所有调用走既有 {@link LlmClient} + {@link LlmTextCollector}；调用预算由调用方经
 * {@link AgentLlmCallLimiter} 原子占用（本类不自行计数）。模型路由/用户上下文沿用请求线程的
 * {@link com.queryforge.domain.model.ModelRouteContextHolder}，不在新线程里丢失。</p>
 *
 * <p>输出在使用前仍需服务端校验：代码长度/空值、沙箱退出码与 output.json schema；
 * 本类只做“去围栏/去前缀”这类确定性清洗，不执行任何代码。</p>
 */
@Service
public class AgentPythonAssistant {

    private static final Logger log = LoggerFactory.getLogger(AgentPythonAssistant.class);
    private static final int MAX_INTERPRET_CHARS = 600;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final AnalysisProperties analysisProperties;

    public AgentPythonAssistant(LlmClient llmClient, ObjectMapper objectMapper,
                                AnalysisProperties analysisProperties) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.analysisProperties = analysisProperties;
    }

    /**
     * 生成受控 Python 代码。返回空串表示模型未给出可用代码（由调用方决定重试或失败），
     * 超长代码同样返回空串并记录，绝不把超限代码送进沙箱。
     */
    public String generateCode(Long runId, String objective, String columnsInfo, String realDataSummary,
                               String effectiveContext, String instructions) {
        String raw = LlmTextCollector.collect(
                llmClient.openChat(
                        AgentPrompts.PYTHON_SYSTEM,
                        AgentPrompts.pythonCodeUser(
                                objective, columnsInfo, realDataSummary, effectiveContext, instructions)),
                objectMapper);
        String code = sanitizeCode(raw);
        if (code.isEmpty()) {
            log.warn("agent_python_empty runId={} rawLength={}", runId, raw == null ? 0 : raw.length());
            return "";
        }
        if (code.length() > analysisProperties.maxCodeChars()) {
            log.warn("agent_python_too_long runId={} length={} limit={}",
                    runId, code.length(), analysisProperties.maxCodeChars());
            return "";
        }
        return code;
    }

    /**
     * 解释真实分析结果。模型不可用/空输出时返回空串，由调用方回退到确定性摘要，
     * 不得把“解释失败”伪装成“分析完成”。
     */
    public String interpretResult(Long runId, String question, String objective,
                                  List<ChartSchema.TypedChart> charts, String verifiedSummary,
                                  String boundedStdout) {
        String raw = LlmTextCollector.collect(
                llmClient.openChat(
                        AgentPrompts.PYTHON_INTERPRET_SYSTEM,
                        AgentPrompts.pythonInterpretUser(
                                question, objective, chartsJson(charts), verifiedSummary, boundedStdout)),
                objectMapper);
        String text = raw == null ? "" : raw.trim();
        return text.length() > MAX_INTERPRET_CHARS ? text.substring(0, MAX_INTERPRET_CHARS) : text;
    }

    /** 图表转确定性 JSON 文本（只含 schema 字段，不含任何运行时对象）。 */
    private String chartsJson(List<ChartSchema.TypedChart> charts) {
        if (charts == null || charts.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(charts);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.debug("agent_python_charts_json_failed runId message={}", ex.getMessage());
            return "[]";
        }
    }

    /** 去掉 Markdown Python 围栏与常见前缀；不做任何语义改写。 */
    static String sanitizeCode(String raw) {
        if (raw == null) {
            return "";
        }
        String code = raw.replace("```python", "").replace("```Python", "").replace("```PYTHON", "")
                .replace("```", "").trim();
        if (code.regionMatches(true, 0, "Python:", 0, 7)) {
            code = code.substring(7).trim();
        }
        return code;
    }
}
