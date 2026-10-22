package com.zzj.admin.web;

/**
 * P13-M3B：修改用户状态的请求体。仅接受合法状态值，非法值在 Service 层被拒绝。
 */
public record AdminUserStatusRequest(String status) {
}
