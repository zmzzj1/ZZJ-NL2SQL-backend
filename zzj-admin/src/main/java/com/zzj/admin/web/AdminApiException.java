package com.zzj.admin.web;

import org.springframework.http.HttpStatus;

/**
 * P13-M3A：Admin 进程内的业务异常。
 *
 * <p>与管理服务的错误语义一一对应（401 未登录 / 403 非管理员 / 503 依赖不可用），
 * 由 {@code AdminExceptionHandler} 统一转为与核心服务同形状的 JSON 错误体。
 */
public class AdminApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final AdminErrorCode errorCode;
    private final HttpStatus status;

    public AdminApiException(AdminErrorCode errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public AdminErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
