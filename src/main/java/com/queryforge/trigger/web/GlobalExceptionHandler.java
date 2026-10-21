package com.queryforge.trigger.web;

import com.queryforge.shared.error.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.queryforge.infrastructure.web.RequestIdFilter;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;

/**
 * 统一非流式错误响应。响应体绝不包含堆栈、JDBC URL、账号或密钥。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        log.warn("api_error code={} message={}", ex.getErrorCode(), ex.getMessage());
        return build(ex.getStatus(), ex.getErrorCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "请求参数不合法";
        }
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "请求体不是合法的 JSON", request);
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<ApiErrorResponse> handleSql(SQLException ex, HttpServletRequest request) {
        log.warn("sql_error state={} code={}", ex.getSQLState(), ex.getErrorCode());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.SQL_EXECUTION_ERROR, "SQL 执行失败，请检查表名、列名与语法", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unexpected_error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "服务内部错误", request);
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, ErrorCode code, String message, HttpServletRequest request) {
        String requestId = RequestIdFilter.current(request);
        // 显式指定 JSON：调用方可能带 Accept: text/event-stream，不能让错误体被内容协商拒绝。
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(code.name(), message, requestId));
    }
}
