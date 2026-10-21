package com.queryforge.application.agent;

import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.ResolvedAgentConfig;

/**
 * 一次审批执行的上下文。
 * replay=true 表示幂等重放既有结果，不重复执行 SQL。
 */
public record PreparedApproval(
        Long userId,
        AgentRun run,
        AgentStep step,
        boolean replay,
        String database,
        ResolvedAgentConfig config
) {
}
