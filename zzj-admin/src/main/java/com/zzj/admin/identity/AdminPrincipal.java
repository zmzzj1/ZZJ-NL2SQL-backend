package com.zzj.admin.identity;

/**
 * P13-M3A：管理服务解析出的登录主体。
 *
 * <p>一律来自 Redis 中的核心服务会话快照：浏览器传入的角色头、URL 参数都不参与构造。
 * 只保留判权与展示必需字段，不含密码、Token 或任何凭据。
 */
public record AdminPrincipal(long userId, String email, String displayName, String role) {

    public boolean isPlatformAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
