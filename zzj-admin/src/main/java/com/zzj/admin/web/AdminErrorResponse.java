package com.zzj.admin.web;

/**
 * P13-M3A：错误响应体，与核心服务 {@code ApiErrorResponse} 字段一致（code/message/requestId）。
 */
public record AdminErrorResponse(String code, String message, String requestId) {
}
