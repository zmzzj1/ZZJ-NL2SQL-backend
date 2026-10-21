package com.queryforge.domain.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryforge.domain.datasource.ConnectionPolicyProperties;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("连接目标白名单")
class ConnectionTargetPolicyTest {

    private final ConnectionTargetPolicy policy = new ConnectionTargetPolicy(
            new ConnectionPolicyProperties(List.of("127.0.0.1", "localhost"), List.of(3306, 3307), 3000, 10000));

    @Test
    @DisplayName("本地白名单主机与端口通过")
    void normalizeHost_shouldAllowWhitelistedHost() {
        assertThat(policy.normalizeHost(" LOCALHOST ")).isEqualTo("localhost");
        assertThat(policy.validatePort(3306)).isEqualTo(3306);
        assertThat(policy.validateIdentifier("sql_demo_a", "数据库名")).isEqualTo("sql_demo_a");
    }

    @Test
    @DisplayName("云元数据地址与非白名单端口被拒绝")
    void shouldRejectForeignHostAndPort() {
        assertThatThrownBy(() -> policy.normalizeHost("169.254.169.254"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> policy.validatePort(6379))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("数据库名与用户名只允许安全标识符")
    void shouldRejectIdentifierInjection() {
        assertThatThrownBy(() -> policy.validateIdentifier("sql_demo;DROP", "数据库名"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> policy.validateIdentifier("user?allowMultiQueries=true", "用户名"))
                .isInstanceOf(ApiException.class);
    }
}
