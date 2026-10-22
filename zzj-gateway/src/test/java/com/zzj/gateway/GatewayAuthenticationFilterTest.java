package com.zzj.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 入口认证过滤器：匿名放行、默认保护、失败关闭与既有 JSON 错误契约。 */
class GatewayAuthenticationFilterTest {

    private static final String PROTECTED = "/api/v1/connections";

    private final GatewayErrorWriter errorWriter = new GatewayErrorWriter(new ObjectMapper());

    private static ServerWebExchange exchange(HttpMethod method, String path, String cookie) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(method, path);
        if (cookie != null) {
            builder = builder.cookie(new HttpCookie("satoken", cookie));
        }
        return MockServerWebExchange.from(builder.build());
    }

    /** 记录下游是否被触达：拒绝场景必须完全不进入业务。 */
    private static final class RecordingChain implements GatewayFilterChain {

        private final AtomicBoolean called = new AtomicBoolean(false);

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            called.set(true);
            return Mono.empty();
        }
    }

    private GatewayAuthenticationFilter filter(Function<String, Mono<AuthOutcome>> validator) {
        return new GatewayAuthenticationFilter(
                new SharedSessionValidationService(null, null) {
                    @Override
                    public Mono<AuthOutcome> validate(String tokenValue) {
                        return validator.apply(tokenValue);
                    }
                },
                errorWriter);
    }

    private String bodyOf(ServerWebExchange exchange) {
        return ((org.springframework.mock.http.server.reactive.MockServerHttpResponse) exchange.getResponse())
                .getBodyAsString().block();
    }

    @Test
    @DisplayName("匿名白名单命中时不查会话直接放行")
    void allowsAnonymousEndpointWithoutLookup() {
        GatewayAuthenticationFilter filter = filter(token -> {
            throw new IllegalStateException("匿名端点不应触发会话校验");
        });
        RecordingChain chain = new RecordingChain();

        filter.filter(exchange(HttpMethod.GET, "/api/health", null), chain).block();

        assertThat(chain.called).isTrue();
    }

    @Test
    @DisplayName("受保护路径无 Cookie：401 + UNAUTHENTICATED，且不下发到业务")
    void rejectsProtectedWithoutCookie() {
        GatewayAuthenticationFilter filter = filter(token -> Mono.just(AuthOutcome.UNAUTHENTICATED));
        RecordingChain chain = new RecordingChain();

        ServerWebExchange exchange = exchange(HttpMethod.GET, PROTECTED, null);
        filter.filter(exchange, chain).block();

        assertThat(chain.called).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(bodyOf(exchange)).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    @Test
    @DisplayName("会话无效/已撤销：401，不进入业务")
    void rejectsRevokedSession() {
        GatewayAuthenticationFilter filter = filter(token -> Mono.just(AuthOutcome.UNAUTHENTICATED));
        RecordingChain chain = new RecordingChain();

        ServerWebExchange exchange = exchange(HttpMethod.GET, PROTECTED, "revoked-token");
        filter.filter(exchange, chain).block();

        assertThat(chain.called).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("会话存储不可用：503 失败关闭，绝不静默放行")
    void failsClosedWhenSessionStoreUnavailable() {
        GatewayAuthenticationFilter filter = filter(token -> Mono.just(AuthOutcome.UNAVAILABLE));
        RecordingChain chain = new RecordingChain();

        ServerWebExchange exchange = exchange(HttpMethod.POST, "/api/v1/agent/runs/stream", "whatever");
        filter.filter(exchange, chain).block();

        assertThat(chain.called).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(bodyOf(exchange)).contains("\"code\":\"SERVICE_UNAVAILABLE\"");
    }

    @Test
    @DisplayName("会话校验抛错（Redis 故障）同样失败关闭为 503")
    void failsClosedOnError() {
        GatewayAuthenticationFilter filter =
                filter(token -> Mono.error(new IllegalStateException("redis down")));
        RecordingChain chain = new RecordingChain();

        ServerWebExchange exchange = exchange(HttpMethod.GET, PROTECTED, "token");
        filter.filter(exchange, chain).block();

        assertThat(chain.called).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("有效会话放行，且校验拿到的是 Cookie 中的 token")
    void forwardsWhenAuthenticated() {
        String[] seen = new String[1];
        GatewayAuthenticationFilter filter = filter(token -> {
            seen[0] = token;
            return Mono.just(AuthOutcome.AUTHENTICATED);
        });
        RecordingChain chain = new RecordingChain();

        filter.filter(exchange(HttpMethod.GET, PROTECTED, "live-token"), chain).block();

        assertThat(seen[0]).isEqualTo("live-token");
        assertThat(chain.called).isTrue();
    }

    @Test
    @DisplayName("OPTIONS 预检放行，但只放行预检本身")
    void allowsOptionsPreflight() {
        GatewayAuthenticationFilter filter = filter(token -> {
            throw new IllegalStateException("预检不应触发会话校验");
        });
        RecordingChain chain = new RecordingChain();

        filter.filter(exchange(HttpMethod.OPTIONS, PROTECTED, null), chain).block();

        assertThat(chain.called).isTrue();
    }

    @Test
    @DisplayName("非 /api 路径（健康检查/Actuator）沿用既有规则，不被认证过滤器改变")
    void leavesNonApiPathsAlone() {
        GatewayAuthenticationFilter filter = filter(token -> {
            throw new IllegalStateException("非 /api 不应触发会话校验");
        });
        RecordingChain chain = new RecordingChain();

        filter.filter(exchange(HttpMethod.GET, "/actuator/health", null), chain).block();

        assertThat(chain.called).isTrue();
    }

    @Test
    @DisplayName("拒绝响应不携带任何 CORS 放宽头")
    void rejectionHasNoCorsHeaders() {
        GatewayAuthenticationFilter filter = filter(token -> Mono.just(AuthOutcome.UNAUTHENTICATED));
        ServerWebExchange exchange = exchange(HttpMethod.GET, PROTECTED, null);

        filter.filter(exchange, new RecordingChain()).block();

        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(exchange.getResponse().getHeaders().containsKey("Access-Control-Allow-Credentials")).isFalse();
    }

    @Test
    @DisplayName("错误体 UTF-8 可被解析为 ApiErrorResponse 形状")
    void errorBodyShape() throws Exception {
        GatewayAuthenticationFilter filter = filter(token -> Mono.just(AuthOutcome.UNAUTHENTICATED));
        ServerWebExchange exchange = exchange(HttpMethod.GET, PROTECTED, null);
        filter.filter(exchange, new RecordingChain()).block();

        String body = bodyOf(exchange);
        assertThat(body).isNotNull();
        var node = new ObjectMapper().readTree(body.getBytes(StandardCharsets.UTF_8));
        assertThat(node.get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(node.get("message").asText()).isNotBlank();
    }

    @Test
    @DisplayName("过滤器顺序在路由转发之前")
    void orderedBeforeRouting() {
        GatewayAuthenticationFilter filter = filter(token -> Mono.just(AuthOutcome.AUTHENTICATED));
        assertThat(filter.getOrder()).isLessThan(0);
        assertThat(filter.getOrder()).isGreaterThan(new UntrustedIdentityHeaderFilter().getOrder());
    }
}
