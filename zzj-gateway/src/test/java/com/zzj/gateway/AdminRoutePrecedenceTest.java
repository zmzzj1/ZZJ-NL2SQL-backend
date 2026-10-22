package com.zzj.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.yaml.snakeyaml.Yaml;

/**
 * P13-M3A：管理路由优先级与匹配范围。
 *
 * <p>直接读取网关真实配置，并用路由谓词工厂做真实匹配（不是断言字符串）：
 * 两条管理查询必须指向 {@code lb://zzj-admin} 且排在 {@code /api/**} 兜底路由之前，
 * 其余 {@code /api/v1/admin/**}（overview、model-usage、quotas）仍落回核心服务。
 */
class AdminRoutePrecedenceTest {

    @Test
    void adminRoutes_shouldBeDeclaredBeforeCoreFallback() {
        List<String> ids = routes().stream().map(route -> String.valueOf(route.get("id"))).toList();

        assertThat(ids).contains("admin-users", "admin-dashboard", "core-service-api");
        assertThat(ids.indexOf("admin-users")).isLessThan(ids.indexOf("core-service-api"));
        assertThat(ids.indexOf("admin-dashboard")).isLessThan(ids.indexOf("core-service-api"));
    }

    @Test
    void adminRoutes_shouldTargetAdminServiceWithGetOnly() {
        assertThat(routeById("admin-users").get("uri")).isEqualTo("lb://zzj-admin");
        assertThat(routeById("admin-dashboard").get("uri")).isEqualTo("lb://zzj-admin");
        assertThat(predicates(routeById("admin-users"))).contains("Method=GET");
        assertThat(predicates(routeById("admin-dashboard"))).contains("Method=GET");
        assertThat(routeById("core-service-api").get("uri")).isEqualTo("lb://zzj-service");
    }

    @Test
    void adminPathPredicates_shouldNotCaptureOtherAdminEndpoints() {
        String usersPath = pathPattern("admin-users");
        String dashboardPath = pathPattern("admin-dashboard");

        assertThat(pathMatches(usersPath, "/api/v1/admin/users")).isTrue();
        assertThat(pathMatches(dashboardPath, "/api/v1/admin/dashboard")).isTrue();
        // 既有管理接口必须被这两条路由放过，继续走核心服务。
        assertThat(pathMatches(usersPath, "/api/v1/admin/overview")).isFalse();
        assertThat(pathMatches(dashboardPath, "/api/v1/admin/model-usage")).isFalse();
        assertThat(pathMatches(dashboardPath, "/api/v1/admin/quotas")).isFalse();
    }

    @Test
    void adminRoutes_shouldAcceptGetOnly() {
        assertThat(methodMatches("admin-users", HttpMethod.GET)).isTrue();
        assertThat(methodMatches("admin-users", HttpMethod.POST)).isFalse();
        assertThat(methodMatches("admin-dashboard", HttpMethod.POST)).isFalse();
    }

    /** 用路由配置里声明的方法集合判定真实请求方法是否被接受。 */
    private static boolean methodMatches(String routeId, HttpMethod method) {
        String declared = predicates(routeById(routeId)).stream()
                .filter(value -> value.startsWith("Method="))
                .map(value -> value.substring("Method=".length()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("路由缺少 Method 谓词：" + routeId));
        MethodRoutePredicateFactory.Config config = new MethodRoutePredicateFactory().newConfig();
        config.setMethods(HttpMethod.valueOf(declared));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, "/api/v1/admin/users").build());
        return new MethodRoutePredicateFactory().apply(config).test(exchange);
    }

    private static boolean pathMatches(String pattern, String uri) {
        PathRoutePredicateFactory.Config config = new PathRoutePredicateFactory().newConfig();
        config.setPatterns(List.of(pattern));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(uri).build());
        return new PathRoutePredicateFactory().apply(config).test(exchange);
    }

    private static String pathPattern(String routeId) {
        return predicates(routeById(routeId)).stream()
                .filter(value -> value.startsWith("Path="))
                .map(value -> value.substring("Path=".length()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("路由缺少 Path 谓词：" + routeId));
    }

    @SuppressWarnings("unchecked")
    private static List<String> predicates(Map<String, Object> route) {
        return (List<String>) route.get("predicates");
    }

    private static Map<String, Object> routeById(String routeId) {
        return routes().stream()
                .filter(route -> routeId.equals(route.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到路由：" + routeId));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routes() {
        try (InputStream in = AdminRoutePrecedenceTest.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("网关配置").isNotNull();
            Map<String, Object> yaml = new Yaml().load(in);
            Map<String, Object> spring = (Map<String, Object>) yaml.get("spring");
            Map<String, Object> cloud = (Map<String, Object>) spring.get("cloud");
            Map<String, Object> gateway = (Map<String, Object>) cloud.get("gateway");
            return (List<Map<String, Object>>) gateway.get("routes");
        } catch (IOException ex) {
            throw new IllegalStateException("读取网关配置失败", ex);
        }
    }
}
