package com.zzj.admin.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * P13-M3A：为每次请求生成追踪 ID（仅内存/响应体携带，不落盘、不关联用户身份）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminRequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = "adminRequestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(REQUEST_ID_ATTRIBUTE, UUID.randomUUID().toString());
        chain.doFilter(request, response);
    }
}
