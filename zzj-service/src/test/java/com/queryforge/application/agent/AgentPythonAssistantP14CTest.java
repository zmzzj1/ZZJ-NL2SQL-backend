package com.queryforge.application.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.analysis.AnalysisProperties;
import com.queryforge.domain.analysis.ChartSchema;
import com.queryforge.domain.model.LlmClient;
import com.queryforge.domain.model.LlmStream;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P14-C T03：Python 代码生成的确定性清洗与边界（模型侧只做清洗，不执行、不伪造）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P14-C Python 助手代码生成与边界")
class AgentPythonAssistantP14CTest {

    private static final int MAX_CODE_CHARS = 500;

    @Mock
    private LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentPythonAssistant assistant;

    @BeforeEach
    void setUp() {
        assistant = new AgentPythonAssistant(llmClient, objectMapper, properties(MAX_CODE_CHARS));
    }

    private static AnalysisProperties properties(int maxCodeChars) {
        return new AnalysisProperties(100, 1024, maxCodeChars, 20, 256L * 1024 * 1024, 1.0,
                "python:3.12-slim", "python");
    }

    private void stubModel(String payload) {
        when(llmClient.openChat(anyString(), anyString())).thenReturn(stream(payload));
    }

    private static LlmStream stream(String payload) {
        String quoted;
        try {
            quoted = objectMapper().writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return new LlmStream(Stream.of(
                "data: {\"choices\":[{\"delta\":{\"content\":" + quoted + "}}]}", "data: [DONE]"));
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Test
    @DisplayName("去掉 Markdown 围栏后返回纯代码")
    void generateCode_shouldStripFences() {
        stubModel("```python\nprint('ok')\n```");

        String code = assistant.generateCode(5L, "统计趋势", "region: TEXT", "region=华东", "上下文", "");

        assertThat(code).isEqualTo("print('ok')");
    }

    @Test
    @DisplayName("模型未给出代码：返回空串，由调用方降级而不是伪造执行")
    void generateCode_empty_shouldReturnBlank() {
        stubModel("   ");

        assertThat(assistant.generateCode(5L, "统计趋势", "region: TEXT", "row", "", "")).isEmpty();
    }

    @Test
    @DisplayName("超长代码直接拒绝，绝不送入沙箱")
    void generateCode_tooLong_shouldReturnBlank() {
        stubModel("x".repeat(MAX_CODE_CHARS + 10));

        assertThat(assistant.generateCode(5L, "统计趋势", "region: TEXT", "row", "", "")).isEmpty();
    }

    @Test
    @DisplayName("解释结果超长时截断到 600 字，不因长度失败")
    void interpretResult_shouldBoundLength() {
        stubModel("释".repeat(900));

        String text = assistant.interpretResult(5L, "问题", "目标",
                List.of(new ChartSchema.BarChart("标题", "x", "y",
                        List.of("华东"), List.of(1.0), null)), "摘要", "stdout");

        assertThat(text).hasSize(600);
    }

    @Test
    @DisplayName("解释失败（空输出）返回空串，调用方回退确定性摘要")
    void interpretResult_empty_shouldReturnBlank() {
        stubModel("");

        assertThat(assistant.interpretResult(5L, "问题", "目标", List.of(), "摘要", "")).isEmpty();
    }

    @Test
    @DisplayName("sanitizeCode 去掉 Python: 前缀与围栏（大小写不敏感）")
    void sanitizeCode_shouldHandlePrefixes() {
        assertThat(AgentPythonAssistant.sanitizeCode("Python:\nprint(1)")).isEqualTo("print(1)");
        assertThat(AgentPythonAssistant.sanitizeCode("```PYTHON\nprint(1)\n```")).isEqualTo("print(1)");
        assertThat(AgentPythonAssistant.sanitizeCode(null)).isEmpty();
    }
}
