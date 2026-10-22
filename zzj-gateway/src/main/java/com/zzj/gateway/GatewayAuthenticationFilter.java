package com.zzj.gateway;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * P13-M2：网关入口认证——只裁决"这次入口请求能否继续"，不裁决任何资源授权。
 *
 * <p>规则（{@code /api/**} 默认保护）：
 * <ol>
 *   <li>非 {@code /api} 请求（健康检查等）沿用既有规则，不因本过滤器改变；</li>
 *   <li>{@code OPTIONS} 预检直接放行，但这只意味着预检本身不携带业务语义，
 *       对应的真实业务请求仍按白名单/登录态判定；</li>
 *   <li>命中精确匿名白名单的请求直接放行；</li>
 *   <li>其余请求必须持有核心服务建立的真实会话；未登录 401、会话存储不可用 503。</li>
 * </ol>
 *
 * <p>不在网关构造"已认证用户"头：核心服务仍按自己的 Cookie/会话与资源权限独立判断，
 * 网关通过只代表"有登录态"，不代表可以访问某个连接、Workspace 或管理功能。
 */
@Component
public class GatewayAuthenticationFilter implements GlobalFilter, Ordered {

    /** Sa-Token 的 cookie 名，与核心服务 sa-token.token-name 一致。 */
    static final String TOKEN_COOKIE = "satoken";

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthenticationFilter.class);

    private final SharedSessionValidationService sessionService;
    private final GatewayErrorWriter errorWriter;

    public GatewayAuthenticationFilter(SharedSessionValidationService sessionService,
                                       GatewayErrorWriter errorWriter) {
        this.sessionService = sessionService;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!request.getURI().getPath().startsWith("/api")) {
            return chain.filter(exchange);
        }
        // 预检放行不等于业务请求免认证：OPTIONS 不承载状态变更，真实请求仍走下面判定。
        if (request.getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }
        if (AnonymousAccessPolicy.isAnonymous(request)) {
            return chain.filter(exchange);
        }
        // 只覆盖"会话校验"这一段：下游转发/流式写入的错误（如客户端提前断开）必须原样传播，
        // 不能被吞成 503，否则会把正常的连接取消伪装成认证故障。
        Mono<AuthOutcome> outcome = sessionService.validate(tokenFrom(request))
                .onErrorResume(ex -> {
                    log.warn("gateway_auth_failed_closed path={} type={}",
                            request.getURI().getPath(), ex.getClass().getSimpleName());
                    return Mono.just(AuthOutcome.UNAVAILABLE);
                });
        return outcome.flatMap(result -> switch (result) {
            case AUTHENTICATED -> chain.filter(exchange);
            case UNAUTHENTICATED -> errorWriter.write(exchange, HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED", "请先登录");
            case UNAVAILABLE -> errorWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "SERVICE_UNAVAILABLE", "会话服务不可用，请稍后重试");
        });
    }

    /** 取 cookie 中的 token；不读取任何客户端自填的身份头。 */
    private String tokenFrom(ServerHttpRequest request) {
        List<HttpCookie> cookies = request.getCookies().get(TOKEN_COOKIE);
        if (cookies != null && !cookies.isEmpty()) {
            String value = cookies.get(0).getValue();
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        // 与核心服务一致：cookie 之外还接受同名 header（Sa-Token 默认同样读取 header）。
        return request.getHeaders().getFirst(TOKEN_COOKIE);
    }

    /** 头清洗之后、路由转发之前执行。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
