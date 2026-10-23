package com.queryforge.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 修复模型：提示词必须携带 Schema 与校验错误，解析必须严格（单一 JSON object、容忍代码围栏）。 */
class LlmArgumentRepairModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesPlainJsonObject() {
        Map<String, Object> parsed = LlmArgumentRepairModel.parseStrict("{\"city\":\"Beijing\"}", objectMapper);
        assertEquals("Beijing", parsed.get("city"));
    }

    @Test
    void stripsJsonCodeFence() {
        Map<String, Object> parsed =
                LlmArgumentRepairModel.parseStrict("```json\n{\"city\":\"Beijing\"}\n```", objectMapper);
        assertEquals("Beijing", parsed.get("city"));
    }

    @Test
    void stripsBareCodeFence() {
        Map<String, Object> parsed =
                LlmArgumentRepairModel.parseStrict("```\n{\"city\":\"Beijing\"}\n```", objectMapper);
        assertEquals("Beijing", parsed.get("city"));
    }

    @Test
    void rejectsNonObjectJson() {
        ApiException ex = assertThrows(
                ApiException.class, () -> LlmArgumentRepairModel.parseStrict("[1,2]", objectMapper));
        assertEquals(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, ex.getErrorCode());
    }

    @Test
    void rejectsMalformedJson() {
        ApiException ex = assertThrows(
                ApiException.class, () -> LlmArgumentRepairModel.parseStrict("{not json", objectMapper));
        assertEquals(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, ex.getErrorCode());
    }

    @Test
    void rejectsBlankModelOutput() {
        ApiException ex = assertThrows(
                ApiException.class, () -> LlmArgumentRepairModel.parseStrict("   ", objectMapper));
        assertEquals(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, ex.getErrorCode());
    }

    @Test
    void promptsCarryToolSchemaAndValidationError() {
        String user = LlmArgumentRepairModel.userPrompt(
                "调用天气工具", "weather", "查询天气",
                Map.of("properties", Map.of("city", Map.of("type", "string"))),
                Map.of("city", 123), "参数 city 类型应为 string");
        assertTrue(user.contains("weather"));
        assertTrue(user.contains("city"));
        assertTrue(user.contains("参数 city 类型应为 string"));
        assertTrue(LlmArgumentRepairModel.systemPrompt().contains("JSON"));
    }
}
