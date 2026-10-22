package com.queryforge.application.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Agent SQL 文本清理")
class AgentSqlAssistantTest {

    @Test
    @DisplayName("去掉代码围栏、前缀与结尾分号")
    void sanitize_shouldStripFencesAndPrefix() {
        String sanitized = AgentSqlAssistant.sanitizeSql("""
                ```sql
                SQL: SELECT COUNT(*) FROM shop_orders;
                ```
                """);

        assertThat(sanitized).isEqualTo("SELECT COUNT(*) FROM shop_orders");
    }

    @Test
    @DisplayName("空输入返回空串，交由 Guard 拒绝")
    void sanitize_shouldReturnBlankForNull() {
        assertThat(AgentSqlAssistant.sanitizeSql(null)).isEmpty();
        assertThat(AgentSqlAssistant.sanitizeSql("  ```  ")).isEmpty();
    }
}
