package com.queryforge.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryforge.domain.identity.AppUserPrincipal;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("平台管理员判权与出站地址防护")
class AdminGuardAndUrlGuardTest {

    @Test
    @DisplayName("非管理员被拒绝")
    void nonAdminRejected() {
        AdminGuard guard = new AdminGuard();
        AppUserPrincipal user = new AppUserPrincipal(1L, "a@b.com", "A", "hash", "USER");
        assertThatThrownBy(() -> guard.requireAdmin(user))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ADMIN_FORBIDDEN);
    }

    @Test
    @DisplayName("平台管理员通过")
    void adminAllowed() {
        AdminGuard guard = new AdminGuard();
        AppUserPrincipal admin = new AppUserPrincipal(1L, "a@b.com", "A", "hash", "ADMIN");
        guard.requireAdmin(admin);
    }

    @Test
    @DisplayName("isPlatformAdmin 由持久化 role 驱动")
    void roleDrivenAdmin() {
        assertThat(new AppUserPrincipal(1L, "a", "A", "h", "ADMIN").isPlatformAdmin()).isTrue();
        assertThat(new AppUserPrincipal(1L, "a", "A", "h", "USER").isPlatformAdmin()).isFalse();
        assertThat(new AppUserPrincipal(1L, "a", "A", "h").isPlatformAdmin()).isFalse();
    }

    @Test
    @DisplayName("SSRF：拒绝回环地址")
    void rejectsLoopback() {
        OutboundUrlGuard guard = new OutboundUrlGuard();
        assertThatThrownBy(() -> guard.validate("http://127.0.0.1:8080/v1"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.validate("http://localhost/v1"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("SSRF：拒绝内网网段")
    void rejectsPrivateRanges() {
        OutboundUrlGuard guard = new OutboundUrlGuard();
        assertThatThrownBy(() -> guard.validate("http://192.168.1.1/v1"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.validate("http://10.0.0.1/v1"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.validate("http://169.254.169.254/v1"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("SSRF：拒绝非 http/https 协议")
    void rejectsNonHttpProtocol() {
        OutboundUrlGuard guard = new OutboundUrlGuard();
        assertThatThrownBy(() -> guard.validate("file:///etc/passwd"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.validate("gopher://evil.com"))
                .isInstanceOf(ApiException.class);
    }
}
