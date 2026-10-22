package com.zzj.admin.web;

/**
 * P13-M3B：修改用户 Token 配额的请求体。tokenQuota 必须为非负整数。
 */
public record AdminUserQuotaRequest(Long tokenQuota) {
}
