package com.queryforge.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 参数 Schema 校验：确定性参数错误的判定依据（也是唯一允许进入 LLM 修复的错误类型）。 */
class McpToolArgumentValidatorTest {

    @Test
    void emptySchemaIsNotAnError() {
        assertTrue(McpToolArgumentValidator.validate(Map.of(), Map.of("a", 1)).isEmpty());
        assertTrue(McpToolArgumentValidator.validate(null, Map.of("a", 1)).isEmpty());
    }

    @Test
    void validArgsPass() {
        Map<String, Object> schema = schema(Map.of("city", Map.of("type", "string")), List.of("city"));
        assertTrue(McpToolArgumentValidator.validate(schema, Map.of("city", "Beijing")).isEmpty());
    }

    @Test
    void missingRequiredIsReported() {
        Map<String, Object> schema = schema(Map.of("city", Map.of("type", "string")), List.of("city"));
        List<String> errors = McpToolArgumentValidator.validate(schema, Map.of());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("city"));
    }

    @Test
    void nullValueForRequiredIsReported() {
        Map<String, Object> schema = schema(Map.of("city", Map.of("type", "string")), List.of("city"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("city", null);
        assertFalse(McpToolArgumentValidator.validate(schema, args).isEmpty());
    }

    @Test
    void wrongTypeIsReported() {
        Map<String, Object> schema = schema(Map.of("limit", Map.of("type", "integer")), List.of("limit"));
        List<String> errors = McpToolArgumentValidator.validate(schema, Map.of("limit", "5"));
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("integer"));
    }

    @Test
    void enumViolationIsReported() {
        Map<String, Object> schema = schema(
                Map.of("unit", Map.of("type", "string", "enum", List.of("C", "F"))), List.of("unit"));
        assertTrue(McpToolArgumentValidator.validate(schema, Map.of("unit", "K"))
                .stream().anyMatch(e -> e.contains("取值")));
        assertTrue(McpToolArgumentValidator.validate(schema, Map.of("unit", "C")).isEmpty());
    }

    @Test
    void extraPropertyRejectedWhenAdditionalPropertiesFalse() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("city", Map.of("type", "string")));
        schema.put("additionalProperties", Boolean.FALSE);
        assertTrue(McpToolArgumentValidator.validate(schema, Map.of("city", "BJ", "extra", 1))
                .stream().anyMatch(e -> e.contains("额外")));
    }

    @Test
    void nonObjectSchemaTypeIsReported() {
        assertTrue(McpToolArgumentValidator.validate(Map.of("type", "array"), Map.of())
                .stream().anyMatch(e -> e.contains("object")));
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }
}
