package com.zzj.admin.web;

/**
 * P13-M3A：Admin 对外错误码。取值与核心服务 ErrorCode 中对应项保持一致，
 * 前端可复用既有文案分支，不必为管理服务新增一套错误语义。
 */
public enum AdminErrorCode {
    UNAUTHENTICATED,
    ADMIN_FORBIDDEN,
    VALIDATION_ERROR,
    RESOURCE_NOT_FOUND,
    SERVICE_UNAVAILABLE,
    INTERNAL_ERROR
}
