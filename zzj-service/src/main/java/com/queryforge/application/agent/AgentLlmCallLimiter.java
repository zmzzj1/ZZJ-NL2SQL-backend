package com.queryforge.application.agent;

import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Agent 运行的 LLM 调用预算：每次调用前计数，超限以 409（AGENT_STATE_CONFLICT）终止。
 */
@Service
public class AgentLlmCallLimiter {

    private final AgentRunRepository repository;
    private final AgentProperties properties;

    public AgentLlmCallLimiter(AgentRunRepository repository, AgentProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public <T> T count(Long runId, Supplier<T> call) {
        if (repository.llmCallCount(runId) >= properties.maxLlmCalls()) {
            throw new ApiException(
                    ErrorCode.AGENT_STATE_CONFLICT, HttpStatus.TOO_MANY_REQUESTS, "本次运行的模型调用次数已达上限");
        }
        repository.incrementLlmCalls(runId);
        return call.get();
    }
}
