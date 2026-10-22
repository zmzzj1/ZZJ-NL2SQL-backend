package com.queryforge.application.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryforge.domain.agent.CreateAgentRunRequest.ToolInvocationRequest;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P14-A T4：显式工具请求的服务端边界校验。
 */
@DisplayName("显式工具请求边界校验")
class ToolInvocationRequestGuardTest {

    private final ToolInvocationRequestGuard guard = new ToolInvocationRequestGuard();

    @Test
    @DisplayName("合法工具名与对象参数通过并trim工具名")
    void validate_shouldAcceptValidRequest() {
        ToolInvocationRequest result = guard.validate(
                new ToolInvocationRequest("  describe_schema  ", Map.of("table", "orders")));

        assertThat(result.toolName()).isEqualTo("describe_schema");
        assertThat(result.args()).containsEntry("table", "orders");
    }

    @Test
    @DisplayName("空参数按空对象处理")
    void validate_shouldAcceptNullArgs() {
        assertThat(guard.validate(new ToolInvocationRequest("echo", null)).args()).isEmpty();
    }

    @Test
    @DisplayName("空工具名、超长工具名与非法字符都拒绝")
    void validate_shouldRejectBadToolName() {
        assertThatThrownBy(() -> guard.validate(new ToolInvocationRequest("  ", Map.of())))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> guard.validate(
                new ToolInvocationRequest("x".repeat(101), Map.of())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.validate(
                new ToolInvocationRequest("describe schema; drop table", Map.of())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("参数个数、嵌套深度与体积超限都拒绝")
    void validate_shouldRejectOversizedArgs() {
        Map<String, Object> tooManyKeys = new LinkedHashMap<>();
        for (int index = 0; index < 33; index++) {
            tooManyKeys.put("k" + index, index);
        }
        assertThatThrownBy(() -> guard.validate(new ToolInvocationRequest("echo", tooManyKeys)))
                .isInstanceOf(ApiException.class);

        Map<String, Object> deep = Map.of("a", Map.of("b", Map.of("c", Map.of("d", Map.of("e", 1)))));
        assertThatThrownBy(() -> guard.validate(new ToolInvocationRequest("echo", deep)))
                .isInstanceOf(ApiException.class);

        Map<String, Object> huge = Map.of("blob", "y".repeat(40 * 1024));
        assertThatThrownBy(() -> guard.validate(new ToolInvocationRequest("echo", huge)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("null 请求直接通过（表示没有显式工具意图）")
    void validate_shouldPassNull() {
        assertThat(guard.validate(null)).isNull();
    }
}
