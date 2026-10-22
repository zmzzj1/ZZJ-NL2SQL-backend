package com.queryforge.infrastructure.identity;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.config.SaCookieConfig;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.servlet.util.SaTokenContextJakartaServletUtil;
import com.queryforge.testutil.RedissonFixtures;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.queryforge.domain.identity.AppUserPrincipal;

/**
 * P12 S2：Sa-Token 登录态端到端验证（P1205/P1206/P1207，真实 Redis + 真实 Sa-Token）。
 *
 * <p>覆盖：登录建立服务端会话并下发 satoken cookie → 凭 cookie 解析出唯一 Principal →
 * 注销后旧 token 立即失效 → TTL 到期后同样拒绝。Redis 不可达时整体跳过，不冒充成功。
 */
class SaTokenIdentityServiceTest {

    private static RedissonClient redisson;
    private static SaTokenIdentityService identityService;

    @BeforeAll
    static void setUpAll() {
        redisson = RedissonFixtures.liveRedissonOrSkip();
        // 必须在任何 StpUtil 使用前完成全局装配：配置 + Redis Dao。
        SaManagerTestHook.install(SaManagerTestHook.productionLikeConfig(), new RedissonSaTokenDao(redisson));
        identityService = new SaTokenIdentityService();
    }

    @AfterEach
    void tearDown() {
        SaTokenContextJakartaServletUtil.clearContext();
        if (redisson != null) {
            redisson.getKeys().deleteByPattern("satoken:*");
        }
    }

    private static MockHttpServletResponse loginAndCaptureCookie(AppUserPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        SaTokenContextJakartaServletUtil.setContext(request, response);
        try {
            identityService.establish(principal);
        } finally {
            SaTokenContextJakartaServletUtil.clearContext();
        }
        return response;
    }

    @Test
    @DisplayName("登录建立 Redis 会话并下发 HttpOnly satoken cookie")
    void establish_writesServerSessionAndCookie() {
        AppUserPrincipal principal =
                new AppUserPrincipal(4242L, "sa-token-e2e@local.test", "会话测试", "hash", "ADMIN");

        MockHttpServletResponse response = loginAndCaptureCookie(principal);

        Cookie cookie = response.getCookie("satoken");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getValue()).isNotBlank();
        // 身份落在 Redis：token→loginId 映射存在。
        assertThat(redisson.getBucket("satoken:login:token:" + cookie.getValue()).get())
                .isNotNull();
    }

    @Test
    @DisplayName("凭 cookie 从服务端会话解析出唯一 Principal（含角色）")
    void resolve_readsPrincipalFromServerSession() {
        AppUserPrincipal principal =
                new AppUserPrincipal(4343L, "sa-token-resolve@local.test", "解析测试", "hash", "USER");
        MockHttpServletResponse loginResponse = loginAndCaptureCookie(principal);
        String tokenValue = loginResponse.getCookie("satoken").getValue();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/connections");
        request.setCookies(new Cookie("satoken", tokenValue));
        SaTokenContextJakartaServletUtil.setContext(request, new MockHttpServletResponse());
        try {
            var resolved = identityService.resolveCurrentRequest();

            assertThat(resolved).isPresent();
            AppUserPrincipal fromSession = resolved.get();
            assertThat(fromSession.id()).isEqualTo(4343L);
            assertThat(fromSession.email()).isEqualTo("sa-token-resolve@local.test");
            assertThat(fromSession.displayName()).isEqualTo("解析测试");
            assertThat(fromSession.isPlatformAdmin()).isFalse();
        } finally {
            SaTokenContextJakartaServletUtil.clearContext();
        }
    }

    @Test
    @DisplayName("注销后旧 token 立即失效（旧会话不能绕过权限链）")
    void invalidate_killsTokenImmediately() {
        AppUserPrincipal principal =
                new AppUserPrincipal(4444L, "sa-token-logout@local.test", "注销测试", "hash", "USER");
        MockHttpServletResponse loginResponse = loginAndCaptureCookie(principal);
        String tokenValue = loginResponse.getCookie("satoken").getValue();

        MockHttpServletRequest logoutRequest = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
        logoutRequest.setCookies(new Cookie("satoken", tokenValue));
        SaTokenContextJakartaServletUtil.setContext(logoutRequest, new MockHttpServletResponse());
        try {
            identityService.invalidate();
        } finally {
            SaTokenContextJakartaServletUtil.clearContext();
        }

        // 旧 cookie 再次请求：服务端已无该 token 的映射。
        MockHttpServletRequest replay = new MockHttpServletRequest("GET", "/api/v1/connections");
        replay.setCookies(new Cookie("satoken", tokenValue));
        SaTokenContextJakartaServletUtil.setContext(replay, new MockHttpServletResponse());
        try {
            assertThat(identityService.resolveCurrentRequest()).isEmpty();
        } finally {
            SaTokenContextJakartaServletUtil.clearContext();
        }
    }

    @Test
    @DisplayName("无 token / 伪造 token 均不发放身份")
    void resolve_rejectsMissingOrForgedToken() {
        MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/api/v1/connections");
        SaTokenContextJakartaServletUtil.setContext(anonymous, new MockHttpServletResponse());
        try {
            assertThat(identityService.resolveCurrentRequest()).isEmpty();
        } finally {
            SaTokenContextJakartaServletUtil.clearContext();
        }

        MockHttpServletRequest forged = new MockHttpServletRequest("GET", "/api/v1/connections");
        forged.setCookies(new Cookie("satoken", "forged-token-value"));
        SaTokenContextJakartaServletUtil.setContext(forged, new MockHttpServletResponse());
        try {
            assertThat(identityService.resolveCurrentRequest()).isEmpty();
        } finally {
            SaTokenContextJakartaServletUtil.clearContext();
        }
    }

    @Test
    @DisplayName("Redis TTL 到期后登录态失效")
    void resolve_rejectsExpiredSession() throws Exception {
        SaManagerTestHook.config().setTimeout(1);
        try {
            AppUserPrincipal principal =
                    new AppUserPrincipal(4545L, "sa-token-ttl@local.test", "TTL测试", "hash", "USER");
            MockHttpServletResponse loginResponse = loginAndCaptureCookie(principal);
            String tokenValue = loginResponse.getCookie("satoken").getValue();

            Thread.sleep(1500);

            MockHttpServletRequest expired = new MockHttpServletRequest("GET", "/api/v1/connections");
            expired.setCookies(new Cookie("satoken", tokenValue));
            SaTokenContextJakartaServletUtil.setContext(expired, new MockHttpServletResponse());
            try {
                assertThat(identityService.resolveCurrentRequest()).isEmpty();
            } finally {
                SaTokenContextJakartaServletUtil.clearContext();
            }
        } finally {
            SaManagerTestHook.config().setTimeout(7200);
        }
    }

    /** SaManager 是进程级静态单例，测试内集中装配，避免散落的静态副作用。 */
    static final class SaManagerTestHook {
        private SaManagerTestHook() {
        }

        static void install(SaTokenConfig config, RedissonSaTokenDao dao) {
            cn.dev33.satoken.SaManager.setConfig(config);
            cn.dev33.satoken.SaManager.setSaTokenDao(dao);
            // 生产由 sa-token Spring 自动配置（SaBeanInject）触发插件加载；
            // 纯 JUnit 环境手动初始化 SPI 插件（sa-token-jackson 的 JSON 序列化器）。
            cn.dev33.satoken.plugin.SaTokenPluginHolder.instance.init();
        }

        /** 与 application.yml 的 sa-token 段保持一致的配置（含 cookie 安全属性）。 */
        static SaTokenConfig productionLikeConfig() {
            SaTokenConfig config = new SaTokenConfig();
            config.setTokenName("satoken");
            config.setTimeout(7200);
            config.setIsConcurrent(true);
            config.setIsShare(false);
            config.setTokenStyle("uuid");
            SaCookieConfig cookie = new SaCookieConfig();
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            cookie.setSameSite("Lax");
            cookie.setSecure(false);
            config.setCookie(cookie);
            return config;
        }

        static SaTokenConfig config() {
            return cn.dev33.satoken.SaManager.getConfig();
        }
    }
}
