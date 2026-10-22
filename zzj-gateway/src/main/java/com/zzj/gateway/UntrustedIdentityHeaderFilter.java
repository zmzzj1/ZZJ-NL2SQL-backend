package com.zzj.gateway;

import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * P13-M1：可信边界清洗——移除客户端自填的身份/权限提升请求头。
 *
 * <p>本轮 Gateway 不是认证中心（ADR-M1-002）：业务身份仍由核心服务的 Sa-Token + Redis 会话裁决。
 * 若放任客户端自填 {@code X-User-Id} / {@code X-Role} / {@code X-Workspace} 等头透传到 Service，
 * 一旦后续任何内部过滤器或代码读取它们，就等价于把身份判断暴露给外部。因此在网关边界直接删除。
 *
 * <p>删除而非报错：这些头在正常浏览器链路中不应出现，直接丢弃不改变既有成功路径，
 * 同时避免攻击者通过响应差异探测内部头名语义。
 */
@Component
public class UntrustedIdentityHeaderFilter implements GlobalFilter, Ordered {

    /** 一律小写比较（HTTP 头名大小写不敏感）。 */
    private static final List<String> STRIPPED_HEADERS = List.of(
            "x-user-id",
            "x-user-email",
            "x-user-name",
            "x-role",
            "x-roles",
            "x-workspace",
            "x-workspace-id",
            "x-authenticated",
            "x-admin",
            "x-authorities");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        boolean hasForged = request.getHeaders().keySet().stream()
                .anyMatch(name -> STRIPPED_HEADERS.contains(name.toLowerCase()));
        if (!hasForged) {
            return chain.filter(exchange);
        }
        ServerHttpRequest cleaned = request.mutate()
                .headers(headers -> STRIPPED_HEADERS.forEach(headers::remove))
                .build();
        return chain.filter(exchange.mutate().request(cleaned).build());
    }

    /** 早于路由与转发执行。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
