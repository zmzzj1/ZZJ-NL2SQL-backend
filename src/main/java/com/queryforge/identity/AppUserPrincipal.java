package com.queryforge.identity;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 认证主体：携带用户 ID 供连接归属校验使用，避免从客户端接收 userId。
 */
public final class AppUserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String displayName;
    private final String passwordHash;
    private final String role;

    public AppUserPrincipal(Long id, String email, String displayName, String passwordHash) {
        this(id, email, displayName, passwordHash, "USER");
    }

    public AppUserPrincipal(Long id, String email, String displayName, String passwordHash, String role) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = (role == null || role.isBlank()) ? "USER" : role;
    }

    public Long id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    /** 平台管理员由服务端持久化的 role 字段判定，前端传入 role 无效。 */
    public boolean isPlatformAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }

    /** 服务端持久化的角色（Sa-Token 会话快照需要原样带回，不做前端可见的二次推导）。 */
    public String role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(isPlatformAdmin() ? "ROLE_ADMIN" : "ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
