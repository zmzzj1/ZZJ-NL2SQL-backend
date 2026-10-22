package com.queryforge.infrastructure.identity;

import cn.dev33.satoken.servlet.util.SaTokenContextJakartaServletUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import com.queryforge.domain.identity.AppUserPrincipal;

/**
 * P12 S2：每请求从 Sa-Token 服务端会话构建 Spring Security 认证主体。
 *
 * <p>替代原 HttpSessionSecurityContextRepository 的身份恢复路径：不再有服务端 Session，
 * 身份完全来自 Redis 中的 Sa-Token 会话（{@link SaTokenIdentityService}）。授权判断仍由
 * Spring Security 过滤器链完成，Sa-Token 只做"唯一身份来源"，不引入第二套鉴权。
 *
 * <p>fail-closed 语义：解析过程抛异常（Redis 不可达等）时不放行、不沿用任何本地缓存身份，
 * 按未登录继续走过滤器链——受保护接口由授权阶段统一 401。
 */
public class SaTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SaTokenAuthFilter.class);

    private final SaTokenIdentityService identityService;

    public SaTokenAuthFilter(SaTokenIdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // Sa-Token 的 cookie 读写依赖 servlet 上下文；本过滤器在 DispatcherServlet 之前执行，
        // 需要显式绑定， finally 中释放（登录响应写 satoken cookie 也依赖该上下文存活至此）。
        SaTokenContextJakartaServletUtil.setContext(request, response);
        try {
            Optional<AppUserPrincipal> principal;
            try {
                principal = identityService.resolveCurrentRequest();
            } catch (RuntimeException ex) {
                // Redis 故障等：拒绝发放身份（fail-closed），交由授权阶段返回 401。
                log.warn("sa_token_identity_resolve_failed path={}", request.getRequestURI(), ex);
                principal = Optional.empty();
            }
            if (principal.isPresent()) {
                Authentication authentication = new UsernamePasswordAuthenticationToken(
                        principal.get(), null, principal.get().getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            SaTokenContextJakartaServletUtil.clearContext();
        }
    }
}
