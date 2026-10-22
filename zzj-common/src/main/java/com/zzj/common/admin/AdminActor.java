package com.zzj.common.admin;

import java.io.Serializable;

/**
 * P13-M3A：管理 RPC 的调用主体上下文。
 *
 * <p>由 Admin 从已校验的服务端会话中取出（userId + 会话快照角色），经内部 RPC 通道传给 Provider；
 * Provider 必须以数据库里的持久化 role 为准重新判权，不得无条件信任本对象。
 *
 * <p>字段只含判权必需的最小集合：不携带密码、Token、API Key 或任何连接凭据。
 */
public final class AdminActor implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String role;

    public AdminActor() {
    }

    public AdminActor(Long userId, String role) {
        this.userId = userId;
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
