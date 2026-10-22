package com.zzj.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * P13-M3A：管理服务统一错误体，形状与核心服务 {@code ApiErrorResponse} 一致。
 *
 * <p>只回传错误码与可展示文案：不含堆栈、SQL、Redis 键、会话值或用户隐私。
 */
@RestControllerAdvice
public class AdminExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminExceptionHandler.class);

    @ExceptionHandler(AdminApiException.class)
    public ResponseEntity<AdminErrorResponse> handleAdminApi(AdminApiException ex, HttpServletRequest request) {
        log.warn("admin_api_error code={} message={}", ex.getErrorCode(), ex.getMessage());
        return build(ex.getStatus(), ex.getErrorCode().name(), ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AdminErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("admin_unexpected_error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, AdminErrorCode.INTERNAL_ERROR.name(), "服务内部错误", request);
    }

    private ResponseEntity<AdminErrorResponse> build(HttpStatus status, String code, String message,
                                                     HttpServletRequest request) {
        Object requestId = request.getAttribute(AdminRequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return ResponseEntity.status(status)
                .body(new AdminErrorResponse(code, message, requestId == null ? null : requestId.toString()));
    }
}
