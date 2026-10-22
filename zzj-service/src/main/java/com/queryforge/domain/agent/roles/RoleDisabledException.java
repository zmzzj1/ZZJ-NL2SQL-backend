package com.queryforge.domain.agent.roles;

/** 角色被禁用（配置或权限撤销），运行时真实拒绝（P1113 / P1114）。 */
public class RoleDisabledException extends RuntimeException {
    public RoleDisabledException(AgentRole role) {
        super("角色已被禁用或撤销：" + role.display());
    }
}
