package com.zzj.common.admin;

import java.io.Serializable;

/**
 * P13-M3A：管理端用户列表条目。
 *
 * <p>只包含管理展示必需的账号元数据；不含 passwordHash、Token、API Key、
 * 数据源凭据或任何私有业务数据。createdAt 以 {@code yyyy-MM-dd HH:mm:ss} 字符串传递，
 * 避免跨服务序列化时区/类型差异。
 */
public final class AdminUserSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String email;
    private String displayName;
    private String role;
    private String createdAt;
    /** P13-M3B：用户账号状态（ACTIVE / DISABLED），管理员可修改。 */
    private String status;
    /** P13-M3B：用户级 Token 配额（来自 platform_quotas 的 USER 维度 token_limit），未设置时为 null。 */
    private Long tokenQuota;

    public AdminUserSummary() {
    }

    public AdminUserSummary(Long id, String email, String displayName, String role, String createdAt) {
        this(id, email, displayName, role, createdAt, null, null);
    }

    public AdminUserSummary(Long id, String email, String displayName, String role, String createdAt,
                            String status, Long tokenQuota) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.role = role;
        this.createdAt = createdAt;
        this.status = status;
        this.tokenQuota = tokenQuota;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
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
}
