package com.zzj.admin.web;

/**
 * P13-M3B：平台管理权限自检响应。仅返回是否管理员，不含任何身份细节。
 */
public record AdminCheckResponse(boolean isAdmin, String role) {
}
