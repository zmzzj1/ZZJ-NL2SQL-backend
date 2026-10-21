package com.queryforge.application.agent;

import com.queryforge.domain.agent.ResolvedAgentConfig;

/**
 * 已通过连接与配置校验的待执行运行。
 */
public record PreparedRun(
        Long runId,
        Long userId,
        Long conversationId,
        Long connectionId,
        String database,
        String question,
        ResolvedAgentConfig config,
        Long workspaceId
) {
    /** 兼容旧构造：无配置时用默认配置，无空间上下文。 */
    public PreparedRun(Long runId, Long userId, Long conversationId, Long connectionId,
                       String database, String question) {
        this(runId, userId, conversationId, connectionId, database, question, ResolvedAgentConfig.defaults(), null);
    }

    public PreparedRun(Long runId, Long userId, Long conversationId, Long connectionId,
                       String database, String question, ResolvedAgentConfig config) {
        this(runId, userId, conversationId, connectionId, database, question, config, null);
    }
}
