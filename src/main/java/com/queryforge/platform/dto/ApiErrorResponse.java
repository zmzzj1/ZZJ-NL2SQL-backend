package com.queryforge.platform.dto;

/**
 * 非流式接口的统一错误体，字段与协同文档 4.4 一致。
 */
public record ApiErrorResponse(String code, String message, String requestId) {
}
