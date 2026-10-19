package com.queryforge.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.shared.web.RequestIdFilter;
import com.queryforge.shared.error.ApiErrorResponse;
import com.queryforge.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 过滤器链中的 401/403 也使用统一 JSON 错误体，避免返回 HTML 登录页。
 */
@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, ErrorCode code, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse body = new ApiErrorResponse(code.name(), message, RequestIdFilter.current(request));
        objectMapper.writeValue(response.getWriter(), body);
    }
}
