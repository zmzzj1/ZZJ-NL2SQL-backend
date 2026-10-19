package com.queryforge.shared.web;

/**
 * 契约 4.1 的健康检查响应，字段固定为 status。
 */
public record HealthResponse(String status) {
}
