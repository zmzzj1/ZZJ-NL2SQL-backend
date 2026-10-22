package com.zzj.common.admin;

import java.io.Serializable;

/**
 * P13-M3B：用户状态/配额变更后的回显结果。
 *
 * <p>只承载变更后的真实状态与配额（从持久化读回），不含密码、Token、API Key 等敏感字段。
 * {@code tokenQuota} 为 null 或被禁用时表示"未单独设置"，前端按"未限制"展示。
 */
public final class AdminUserOperationResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String status;
    private Long tokenQuota;
    private String role;

    public AdminUserOperationResult() {
    }

    public AdminUserOperationResult(Long userId, String status, Long tokenQuota, String role) {
        this.userId = userId;
        this.status = status;
        this.tokenQuota = tokenQuota;
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getTokenQuota() {
        return tokenQuota;
    }

    public void setTokenQuota(Long tokenQuota) {
        this.tokenQuota = tokenQuota;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
