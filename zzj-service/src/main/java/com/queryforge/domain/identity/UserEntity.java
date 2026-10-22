package com.queryforge.domain.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * P12 S1：管理库 users 表的 MyBatis-Plus 实体（平台管理元数据，非业务库数据）。
 *
 * <p>与 {@link AppUserPrincipal} 的分工：本实体负责持久化读写（BaseMapper/Wrapper），
 * Principal 负责认证主体（含 passwordHash 校验与角色）。created_at 由数据库默认值维护，
 * 实体不映射；role 由 DB 默认 'USER'，管理端提权走专门路径，不接受前端参数。
 */
@TableName("users")
public class UserEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String email;
    private String displayName;
    private String passwordHash;
    private String role;
    /** P13-M3B：账号状态（ACTIVE / DISABLED），管理员可修改；默认 ACTIVE。 */
    private String status;
    /** 注册时间由数据库默认值维护，实体只读（管理端展示需要，不参与写入）。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    public UserEntity() {
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

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
