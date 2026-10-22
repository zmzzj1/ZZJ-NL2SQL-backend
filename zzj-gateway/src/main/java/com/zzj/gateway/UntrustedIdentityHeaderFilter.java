package com.zzj.gateway;

import java.util.List;
import java.util.Locale;
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

    /** P13-M2：内部约定头前缀，任何以它们开头的头都不允许来自外部（避免被当作内部凭据）。 */
    private static final List<String> STRIPPED_PREFIXES = List.of("x-internal-", "x-authenticated-", "x-trusted-");

    private static boolean isForged(String lowerName) {
        return STRIPPED_HEADERS.contains(lowerName)
                || STRIPPED_PREFIXES.stream().anyMatch(lowerName::startsWith);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        List<String> forged = request.getHeaders().keySet().stream()
                .filter(name -> isForged(name.toLowerCase(Locale.ROOT)))
                .toList();
        if (forged.isEmpty()) {
            return chain.filter(exchange);
        }
        ServerHttpRequest cleaned = request.mutate()
                .headers(headers -> forged.forEach(headers::remove))
                .build();
        return chain.filter(exchange.mutate().request(cleaned).build());
    }

    /** 早于路由与转发执行。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
