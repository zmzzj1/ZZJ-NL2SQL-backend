package com.queryforge.application.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.tool.ToolInvocationResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P14-A T4：工具输出摘要脱敏与截断。工具返回一律视为不可信数据。
 */
@DisplayName("工具输出摘要")
class ToolOutputSanitizerTest {

    private final ToolOutputSanitizer sanitizer = new ToolOutputSanitizer(new ObjectMapper());

    @Test
    @DisplayName("敏感字段被掩码，普通字段保留")
    void summarize_shouldMaskSensitiveFields() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("table", "orders");
        output.put("password", "p@ssw0rd");
        output.put("apiKey", "sk-123");
        output.put("access_token", "t-1");

        String summary = sanitizer.summarize(new ToolInvocationResult("echo", output, 1));

        assertThat(summary).contains("orders");
        assertThat(summary).contains("***");
        assertThat(summary).doesNotContain("p@ssw0rd");
        assertThat(summary).doesNotContain("sk-123");
        assertThat(summary).doesNotContain("t-1");
    }

    @Test
    @DisplayName("嵌套对象、列表与数组中的敏感字段同样被掩码")
    void summarize_shouldMaskNestedSensitiveFields() {
        Map<String, Object> output = Map.of(
                "profile", Map.of("password", "nested-password", "name", "alice"),
                "items", List.of(Map.of("accessToken", "nested-token")),
                "array", new Object[]{Map.of("api_key", "nested-key")});

        String summary = sanitizer.summarize(new ToolInvocationResult("echo", output, 1));

        assertThat(summary).contains("alice", "***");
        assertThat(summary).doesNotContain("nested-password", "nested-token", "nested-key");
    }

    @Test
    @DisplayName("超长输出被截断并标注")
    void summarize_shouldTruncateLongOutput() {
        String summary = sanitizer.summarize(
                new ToolInvocationResult("echo", Map.of("blob", "y".repeat(5000)), 1));

        assertThat(summary.length()).isLessThan(810);
        assertThat(summary).endsWith("…（已截断）");
    }

    @Test
    @DisplayName("空输出给出明确占位，不冒充成功内容")
    void summarize_shouldHandleEmptyOutput() {
        assertThat(sanitizer.summarize(new ToolInvocationResult("echo", Map.of(), 1)))
                .isEqualTo("工具未返回结果");
        assertThat(sanitizer.summarize(null)).isEqualTo("工具未返回结果");
    }

    @Test
    @DisplayName("非 Map 输出原样摘要（截断可控）")
    void summarize_shouldHandleScalarOutput() {
        assertThat(sanitizer.summarize(new ToolInvocationResult("echo", "ok", 1))).isEqualTo("\"ok\"");
    }
}
