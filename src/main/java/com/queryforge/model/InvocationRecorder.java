package com.queryforge.model;

import com.queryforge.model.ModelEntities.Profile;
import com.queryforge.model.ModelEntities.Provider;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * O3/O4 调用尝试记录：每个尝试有唯一 callId + attemptNo，记录实际 Provider/Model/状态/耗时/Token/错误分类。
 * 同时上报健康注册表供路由与熔断使用。不记录 prompt / SQL 结果 / Key / 私有内容。
 */
@Component
public class InvocationRecorder {

    private final ModelRepository repository;
    private final ModelHealthRegistry health;

    public InvocationRecorder(ModelRepository repository, ModelHealthRegistry health) {
        this.repository = repository;
        this.health = health;
    }

    public String newCallId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /** 记录默认单 Provider 回退调用（无目录 profile）。 */
    public void recordDefaultAttempt(String callId, int attemptNo, String providerName, String modelId,
                                     String status, FailureClass failureClass, Integer httpStatus, long latencyMs) {
        repository.insertAttempt(callId, attemptNo, null, null, null, providerName, modelId, "CHAT",
                "DEFAULT_PROVIDER", status, failureClass == null ? null : failureClass.name(), httpStatus,
                (int) latencyMs, null, null, null, null, null, null, "UNKNOWN");
    }

    public void recordAttempt(String callId, int attemptNo, Long runId, Long agentId, Profile profile,
                              Provider provider, String routeReason, String status, FailureClass failureClass,
                              Integer httpStatus, long latencyMs, Integer ttftMs, Integer promptTokens,
                              Integer completionTokens) {
        int total = (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
        repository.insertAttempt(callId, attemptNo, runId, agentId, profile.id(), provider.name(),
                profile.modelId(), profile.modelType(), routeReason, status,
                failureClass == null ? null : failureClass.name(), httpStatus, (int) latencyMs, ttftMs,
                promptTokens, completionTokens, total, null, null, "UNKNOWN");

        if ("SUCCESS".equals(status)) {
            health.recordSuccess(profile.id(), latencyMs);
        } else if ("FAILED".equals(status)) {
            health.recordFailure(profile.id(), latencyMs);
        }
    }

    /** 记录 Token 与费用（来源未知时 costStatus=UNKNOWN）。 */
    public void recordUsage(String callId, int attemptNo, Integer promptTokens, Integer completionTokens,
                            BigDecimal costEstimate, String currency, String costStatus) {
        // 简化：usage 已在 recordAttempt 记录 token；费用单独更新留待后续。
    }
}
