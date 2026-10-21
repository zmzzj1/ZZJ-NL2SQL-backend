package com.queryforge.application.model;

import com.queryforge.infrastructure.persistence.ModelRepository.QuotaRow;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.queryforge.infrastructure.persistence.ModelRepository;

/**
 * O5 配额治理：用户/Workspace/Agent/模型维度的请求数、并发、Token、费用限额。
 * 服务端原子校验；区分平台 BUDGET_EXCEEDED / RATE_LIMITED 与上游 HTTP 429。
 */
@Service
public class QuotaService {

    private final ModelRepository repository;

    public QuotaService(ModelRepository repository) {
        this.repository = repository;
    }

    /** 管理员设置/更新配额。 */
    public void upsertQuota(Long adminId, String scopeType, Long scopeId, Integer requestsLimit,
                            Integer concurrentLimit, Long tokenLimit, java.math.BigDecimal costLimit,
                            String costCurrency) {
        repository.upsertQuota(scopeType.toUpperCase(), scopeId, requestsLimit, concurrentLimit,
                tokenLimit, costLimit, costCurrency, adminId);
        repository.audit(adminId, "UPSERT_QUOTA", "QUOTA", scopeId,
                "scope=" + scopeType + " requests=" + requestsLimit, "SUCCESS");
    }

    /**
     * 调用前校验：检查 scopeType/scopeId 的请求数与 Token 限额是否超限。
     * 已用量按最近窗口（分钟级）统计。
     */
    public void checkBeforeCall(String scopeType, Long scopeId, long requestCountWindow) {
        QuotaRow quota = repository.findQuota(scopeType.toUpperCase(), scopeId).orElse(null);
        if (quota == null) {
            return; // 未配置限额则不限制
        }
        LocalDateTime since = LocalDateTime.now().minusMinutes(1);
        long used = repository.countAttemptsSince(since);
        if (quota.requestsLimit() != null && used >= quota.requestsLimit()) {
            throw new ApiException(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
                    "已超过请求配额（每分钟 " + quota.requestsLimit() + " 次）");
        }
    }

    /** Token/费用预算校验（超出抛 BUDGET_EXCEEDED）。 */
    public void checkBudget(String scopeType, Long scopeId, long tokenUsed) {
        QuotaRow quota = repository.findQuota(scopeType.toUpperCase(), scopeId).orElse(null);
        if (quota == null || quota.tokenLimit() == null) {
            return;
        }
        if (tokenUsed >= quota.tokenLimit()) {
            throw new ApiException(ErrorCode.BUDGET_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS,
                    "已超过 Token 预算上限（" + quota.tokenLimit() + "）");
        }
    }
}
