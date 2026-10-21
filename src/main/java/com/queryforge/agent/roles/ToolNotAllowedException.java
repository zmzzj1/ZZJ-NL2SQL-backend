package com.queryforge.agent.roles;

/** 工具不在角色白名单内，真实拒绝调用（P1113）。 */
public class ToolNotAllowedException extends RuntimeException {
    public ToolNotAllowedException(AgentRole role, String toolId) {
        super("角色 " + role.display() + " 无权调用工具：" + toolId);
    }
}
