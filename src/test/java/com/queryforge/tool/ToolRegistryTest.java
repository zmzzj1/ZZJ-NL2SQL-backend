package com.queryforge.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.agent.AgentRunRepository;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("工具注册表")
@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

    @Mock
    private ToolInvocationRepository auditRepository;

    @Mock
    private AgentRunRepository agentRunRepository;

    private static Tool echoTool() {
        return new Tool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "回显";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", List.of("text"));
            }

            @Override
            public Object execute(ToolInvocation invocation, Map<String, Object> args) {
                return Map.of("result", args.get("text"));
            }
        };
    }

    private ToolRegistry registry() {
        return new ToolRegistry(List.of(echoTool()), auditRepository, new ObjectMapper(), agentRunRepository);
    }

    @Test
    @DisplayName("目录返回工具定义")
    void catalog_shouldReturnTools() {
        List<ToolRegistry.ToolDescriptor> catalog = registry().catalog();
        assertThat(catalog).hasSize(1);
        assertThat(catalog.get(0).name()).isEqualTo("echo");
        assertThat(catalog.get(0).readOnly()).isTrue();
    }

    @Test
    @DisplayName("已知工具执行成功并写审计")
    void invoke_shouldExecuteKnownTool() {
        ToolRegistry registry = registry();
        ToolInvocation invocation = new ToolInvocation(1L, "echo", null, null, null, null, null);
        ToolInvocationResult result = registry.invoke(invocation, Map.of("text", "hi"));
        assertThat(result.toolName()).isEqualTo("echo");
        verify(auditRepository).insert(any(ToolInvocation.class), anyString(), any(), any());
    }

    @Test
    @DisplayName("未知工具被拒绝")
    void invoke_shouldRejectUnknownTool() {
        ToolRegistry registry = registry();
        ToolInvocation invocation = new ToolInvocation(1L, "nope", null, null, null, null, null);
        assertThatThrownBy(() -> registry.invoke(invocation, Map.of()))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOOL_NOT_FOUND));
    }

    @Test
    @DisplayName("缺少必填参数被拒绝")
    void invoke_shouldRejectMissingRequiredArg() {
        ToolRegistry registry = registry();
        ToolInvocation invocation = new ToolInvocation(1L, "echo", null, null, null, null, null);
        assertThatThrownBy(() -> registry.invoke(invocation, Map.of()))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOOL_INVALID_ARGUMENT));
    }
}
