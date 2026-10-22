package com.zzj.gateway;

import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * P13-M2：入口访问的匿名白名单判定——只有"精确 Method + Path"命中才放行。
 *
 * <p>白名单来自核心服务 {@code SecurityConfig} 的 permitAll 与真实 Controller 映射：
 * 健康检查、注册、登录、CSRF Token 获取。核心服务的 permitAll 是方法无关的，
 * 但真实业务映射只有下表几种 method + path；这里按更严格的精确组合放行，
 * 避免"同路径换方法"或"相似前缀"绕过入口认证。
 *
 * <p>{@code logout} 与 {@code me} 明确不在白名单：它们本就需要登录态语义。
 */
public final class AnonymousAccessPolicy {

    private static final List<Rule> RULES = List.of(
            new Rule(HttpMethod.GET, "/api/health"),
            new Rule(HttpMethod.POST, "/api/v1/auth/register"),
            new Rule(HttpMethod.POST, "/api/v1/auth/login"),
            new Rule(HttpMethod.GET, "/api/v1/auth/csrf"));

    private AnonymousAccessPolicy() {
    }

    /** 该请求是否为已核实的匿名接口。 */
    public static boolean isAnonymous(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        if (method == null) {
            return false;
        }
        String path = normalizedPath(request);
        for (Rule rule : RULES) {
            if (rule.matches(method, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取规范化路径：只使用已解码的 path（Spring 已按 RFC 处理 percent-encoding），
     * 统一去尾斜杠并保留大小写——不做小写归一，否则 {@code /API/health} 会被误判为匿名。
     */
    private static String normalizedPath(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        if (path.isEmpty()) {
            return path;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private record Rule(HttpMethod method, String path) {

        boolean matches(HttpMethod method, String path) {
            return this.method == method && this.path.equals(path);
        }
    }

    /** 仅用于测试可读性：返回当前白名单快照。 */
    static List<String> describe() {
        return RULES.stream()
                .map(rule -> rule.method().name().toLowerCase(Locale.ROOT) + " " + rule.path())
                .toList();
    }
}
