package com.queryforge.application.agent;

import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Agent 运行的 LLM 调用预算：调用前原子占用一次额度，超限以 409（AGENT_STATE_CONFLICT）终止。
 *
 * <p>P14-B：多候选并行生成时“先查询计数、再递增”的两步会超额，改为仓库条件更新
 * （{@code llm_call_count < max} 才 +1），保证并发候选与评分调用同样受
 * {@code app.agent.maxLlmCalls} 约束。
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
        if (!acquire(runId)) {
            throw new ApiException(
                    ErrorCode.AGENT_STATE_CONFLICT, HttpStatus.TOO_MANY_REQUESTS, "本次运行的模型调用次数已达上限");
        }
        return call.get();
    }

    /** 原子占用一次预算；不足返回 false（调用方自行降级，不抛错）。 */
    public boolean tryAcquire(Long runId) {
        return acquire(runId);
    }

    /** 原子占用一次预算并执行；预算不足返回 null，供评分等可降级调用使用。 */
    public <T> T tryCount(Long runId, Supplier<T> call) {
        return acquire(runId) ? call.get() : null;
    }

    private boolean acquire(Long runId) {
        return repository.tryIncrementLlmCalls(runId, properties.maxLlmCalls()) == 1;
    }
}
