package com.zzj.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Gateway 可信边界：客户端自填身份头必须在转发前被移除。 */
class UntrustedIdentityHeaderFilterTest {

    private final UntrustedIdentityHeaderFilter filter = new UntrustedIdentityHeaderFilter();

    private static ServerWebExchange exchange(HttpHeaders headers) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/agent/runs/stream")
                .headers(headers)
                .build());
    }

    @Test
    @DisplayName("伪造的身份/权限头在转发前被移除")
    void stripsForgedIdentityHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "1");
        headers.set("X-Role", "ADMIN");
        headers.set("X-Workspace-Id", "9");
        headers.set("X-Admin", "true");
        headers.set("Cookie", "satoken=abc");

        ServerWebExchange source = exchange(headers);
        ServerHttpRequest[] forwarded = new ServerHttpRequest[1];

        filter.filter(source, ex -> {
            forwarded[0] = ex.getRequest();
            return Mono.empty();
        }).block();

        assertThat(forwarded[0]).isNotNull();
        assertThat(forwarded[0].getHeaders().keySet()).doesNotContain(
                "X-User-Id", "X-Role", "X-Workspace-Id", "X-Admin");
        // 正常链路头必须保留，否则登录/CSRF 会断
        assertThat(forwarded[0].getHeaders().getFirst("Cookie")).isEqualTo("satoken=abc");
    }

    @Test
    @DisplayName("大小写不同的同名头同样被移除")
    void stripsCaseInsensitive() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-USER-id", "42");

        ServerWebExchange source = exchange(headers);
        ServerHttpRequest[] forwarded = new ServerHttpRequest[1];
        filter.filter(source, ex -> {
            forwarded[0] = ex.getRequest();
            return Mono.empty();
        }).block();

        assertThat(forwarded[0].getHeaders().keySet()).doesNotContain("x-USER-id");
    }

    @Test
    @DisplayName("无伪造头时不改写请求对象（零开销透传）")
    void passesThroughWhenClean() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-XSRF-TOKEN", "csrf-token");

        ServerWebExchange source = exchange(headers);
        ServerHttpRequest[] forwarded = new ServerHttpRequest[1];
        filter.filter(source, ex -> {
            forwarded[0] = ex.getRequest();
            return Mono.empty();
        }).block();

        assertThat(forwarded[0]).isSameAs(source.getRequest());
        assertThat(forwarded[0].getHeaders().getFirst("X-XSRF-TOKEN")).isEqualTo("csrf-token");
    }

    @Test
    @DisplayName("过滤器早于路由/转发执行")
    void orderedBeforeRouting() {
        assertThat(filter.getOrder()).isLessThan(0);
    }

    @Test
    @DisplayName("清洗清单覆盖常见身份/权限提升头")
    void coversCommonIdentityHeaders() {
        List<String> names = List.of("X-User-Id", "X-User-Email", "X-User-Name", "X-Role", "X-Roles",
                "X-Workspace", "X-Workspace-Id", "X-Authenticated", "X-Admin", "X-Authorities");
        HttpHeaders headers = new HttpHeaders();
        names.forEach(name -> headers.set(name, "forged"));

        ServerWebExchange source = exchange(headers);
        ServerHttpRequest[] forwarded = new ServerHttpRequest[1];
        filter.filter(source, ex -> {
            forwarded[0] = ex.getRequest();
            return Mono.empty();
        }).block();

        assertThat(forwarded[0].getHeaders().keySet()).doesNotContainAnyElementsOf(names);
    }
}
