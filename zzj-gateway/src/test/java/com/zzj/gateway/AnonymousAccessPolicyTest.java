package com.zzj.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

/** 匿名白名单：必须精确 Method + Path 命中，路径/方法变体一律按受保护处理。 */
class AnonymousAccessPolicyTest {

    private static boolean anonymous(HttpMethod method, String path) {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, path).build());
        return AnonymousAccessPolicy.isAnonymous(exchange.getRequest());
    }

    @Test
    @DisplayName("已核实的四个匿名端点按精确 method+path 放行")
    void allowsVerifiedAnonymousEndpoints() {
        assertThat(anonymous(HttpMethod.GET, "/api/health")).isTrue();
        assertThat(anonymous(HttpMethod.POST, "/api/v1/auth/register")).isTrue();
        assertThat(anonymous(HttpMethod.POST, "/api/v1/auth/login")).isTrue();
        assertThat(anonymous(HttpMethod.GET, "/api/v1/auth/csrf")).isTrue();
    }

    @Test
    @DisplayName("logout 与 me 不在白名单：不得假设匿名")
    void protectsLogoutAndMe() {
        assertThat(anonymous(HttpMethod.GET, "/api/v1/auth/me")).isFalse();
        assertThat(anonymous(HttpMethod.POST, "/api/v1/auth/logout")).isFalse();
    }

    @Test
    @DisplayName("同一路径换方法不属于匿名（方法必须精确）")
    void methodMustMatchExactly() {
        assertThat(anonymous(HttpMethod.GET, "/api/v1/auth/login")).isFalse();
        assertThat(anonymous(HttpMethod.POST, "/api/health")).isFalse();
        assertThat(anonymous(HttpMethod.DELETE, "/api/health")).isFalse();
    }

    @Test
    @DisplayName("大小写不同即不算命中：不做小写归一")
    void caseSensitive() {
        assertThat(anonymous(HttpMethod.GET, "/API/health")).isFalse();
        assertThat(anonymous(HttpMethod.GET, "/api/v1/Auth/csrf")).isFalse();
    }

    @Test
    @DisplayName("尾斜杠归一后仍按同一规则判定，且不会放宽到其它路径")
    void trailingSlash() {
        assertThat(anonymous(HttpMethod.GET, "/api/health/")).isTrue();
        assertThat(anonymous(HttpMethod.POST, "/api/v1/auth/login/")).isTrue();
        assertThat(anonymous(HttpMethod.GET, "/api/v1/auth/me/")).isFalse();
    }

    @Test
    @DisplayName("相似前缀与编码路径不会被误判为匿名")
    void similarPrefixAndEncoding() {
        assertThat(anonymous(HttpMethod.GET, "/api/healthcheck")).isFalse();
        assertThat(anonymous(HttpMethod.GET, "/api/v1/auth/csrf/../me")).isFalse();
        assertThat(anonymous(HttpMethod.GET, "/api/v1/auth/login/../csrf")).isFalse();
        assertThat(anonymous(HttpMethod.POST, "/api/v1/auth/register/admin")).isFalse();
    }

    @Test
    @DisplayName("默认保护：任意其它受保护业务路径一律非匿名")
    void defaultProtected() {
        assertThat(anonymous(HttpMethod.GET, "/api/v1/connections")).isFalse();
        assertThat(anonymous(HttpMethod.POST, "/api/v1/agent/runs/stream")).isFalse();
        assertThat(anonymous(HttpMethod.GET, "/api/v1/workflows")).isFalse();
    }

    @Test
    @DisplayName("白名单内容可被测试枚举（便于与 Service permitAll 对照）")
    void whitelistIsMinimal() {
        assertThat(AnonymousAccessPolicy.describe()).containsExactlyInAnyOrder(
                "get /api/health", "post /api/v1/auth/register",
                "post /api/v1/auth/login", "get /api/v1/auth/csrf");
    }
}
