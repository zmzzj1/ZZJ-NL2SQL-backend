package com.queryforge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.servlet.util.SaTokenContextJakartaServletUtil;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * P12 S2：SaTokenAuthFilter 单元验证（fail-closed 与上下文生命周期）。
 *
 * <p>不连 Redis：身份解析用 mock 的服务；重点验证过滤器契约——
 * 解析成功时构建 Spring Authentication（含 ROLE_ADMIN/ROLE_USER 权限），
 * 解析失败（Redis 故障）时按未登录继续链且不泄露异常，finally 清理上下文。
 */
class SaTokenAuthFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        SaTokenContextJakartaServletUtil.clearContext();
    }

    private final SaTokenIdentityService identityService = mock(SaTokenIdentityService.class);
    private final SaTokenAuthFilter filter = new SaTokenAuthFilter(identityService);

    @Test
    @DisplayName("解析到 ADMIN 主体时构建 ROLE_ADMIN Authentication，请求结束后清理")
    void setsAuthentication_whenPrincipalResolved() throws Exception {
        AppUserPrincipal principal =
                new AppUserPrincipal(7L, "alice@example.com", "Alice", "hash", "ADMIN");
        when(identityService.resolveCurrentRequest()).thenReturn(Optional.of(principal));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/connections");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        // finally 已清理：请求结束后不残留身份。
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("权限来自服务端角色，且解析前已绑定 Sa-Token servlet 上下文")
    void authoritiesFollowServerSideRole_andContextBound() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/connections");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AppUserPrincipal principal =
                new AppUserPrincipal(7L, "alice@example.com", "Alice", "hash", "ADMIN");
        when(identityService.resolveCurrentRequest()).thenAnswer((Answer<Optional<AppUserPrincipal>>) invocation -> {
            // 过滤器在解析前已绑定 servlet 上下文，Sa-Token 的 cookie 读写才可用。
            assertThat(SaTokenContextJakartaServletUtil.getRequest()).isSameAs(request);
            assertThat(SaTokenContextJakartaServletUtil.getResponse()).isSameAs(response);
            return Optional.of(principal);
        });
        FilterChain chain = mock(FilterChain.class);
        doAnswer(invocation -> {
            // Authentication 在 resolve 返回之后才写入 SecurityContext，链执行时可见。
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNotNull();
            assertThat(authentication.getName()).isEqualTo("alice@example.com");
            assertThat(authentication.getAuthorities())
                    .extracting("authority")
                    .containsExactly("ROLE_ADMIN");
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);
    }

    @Test
    @DisplayName("未登录时继续过滤器链，不设置任何身份")
    void passesThrough_whenAnonymous() throws Exception {
        when(identityService.resolveCurrentRequest()).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/connections");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Redis 故障时 fail-closed：按未登录继续链，不向上抛异常、不发放身份")
    void failsClosed_whenRedisUnavailable() throws Exception {
        when(identityService.resolveCurrentRequest())
                .thenThrow(new IllegalStateException("redis unavailable"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/connections");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // 请求结束后 servlet 上下文同样被清理，避免 ThreadLocal 泄漏（清理后再取即抛未初始化）。
        assertThatThrownBy(() -> SaTokenContextJakartaServletUtil.getRequest())
                .isInstanceOf(cn.dev33.satoken.exception.SaTokenContextException.class);
    }
}
