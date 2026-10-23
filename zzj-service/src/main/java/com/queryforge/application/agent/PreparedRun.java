package com.queryforge.application.agent;

import com.queryforge.domain.agent.CreateAgentRunRequest.ToolInvocationRequest;
import com.queryforge.domain.agent.ResolvedAgentConfig;

/**
 * 已通过连接与配置校验的待执行运行。
 *
 * <p>P14-A：{@code toolInvocation} 非空表示用户显式选择了工具，启动时跳过
 * Manager 分类与 Planner，走单次受控工具执行链。
 */
public record PreparedRun(
        Long runId,
        Long userId,
        Long conversationId,
        Long connectionId,
        String database,
        String question,
        ResolvedAgentConfig config,
        Long workspaceId,
        ToolInvocationRequest toolInvocation,
        /**
         * P14-D：本次运行创建时固定的计划级审核模式。true=跳过计划级风险评估与人工审核；
         * false=复杂计划生成后由 Gate 判断是否需人工审核。继续执行时不读取前端新值。
         */
        Boolean planAutoConfirm
) {

    /** 兼容旧构造：无配置时用默认配置，无空间上下文，无显式工具，计划级审核默认跳过（等同旧行为）。 */
    public PreparedRun(Long runId, Long userId, Long conversationId, Long connectionId,
                       String database, String question) {
        this(runId, userId, conversationId, connectionId, database, question,
                ResolvedAgentConfig.defaults(), null, null, Boolean.TRUE);
    }

    public PreparedRun(Long runId, Long userId, Long conversationId, Long connectionId,
                       String database, String question, ResolvedAgentConfig config) {
        this(runId, userId, conversationId, connectionId, database, question, config, null, null,
                Boolean.TRUE);
    }

    public PreparedRun(Long runId, Long userId, Long conversationId, Long connectionId,
                       String database, String question, ResolvedAgentConfig config, Long workspaceId) {
        this(runId, userId, conversationId, connectionId, database, question, config, workspaceId, null,
                Boolean.TRUE);
    }

    /** 是否为用户显式选择工具的快捷运行。 */
    public boolean hasToolInvocation() {
        return toolInvocation != null;
    }
}
