package com.queryforge.model;

import com.queryforge.model.ModelEntities.RoutePolicyVersion;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * O2 路由策略管理：候选/策略/fallback 链/权重，发布为不可变版本，支持回滚（生成新版本）。
 */
@Service
public class RoutePolicyService {

    private final ModelRepository repository;

    public RoutePolicyService(ModelRepository repository) {
        this.repository = repository;
    }

    /** 创建草稿策略版本（policyId 相同则累加 version_no）。 */
    public RoutePolicyVersion saveDraft(Long adminId, Long policyId, String strategy, List<Long> candidates,
                                        List<Long> fallbackChain, List<Double> weights, int affinityTtlSeconds) {
        validateStrategy(strategy);
        if (candidates == null || candidates.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "候选模型不能为空");
        }
        for (Long profileId : candidates) {
            repository.findProfile(profileId)
                    .orElseThrow(() -> new ApiException(ErrorCode.MODEL_PROFILE_NOT_FOUND, HttpStatus.NOT_FOUND,
                            "候选模型不存在: " + profileId));
        }
        int nextVersion = repository.maxPolicyVersionNo(policyId) + 1;
        Long id = repository.insertPolicyVersion(policyId, nextVersion, strategy.toUpperCase(),
                JsonIds.write(candidates), JsonIds.write(fallbackChain),
                weights == null ? null : weights.toString(), affinityTtlSeconds);
        repository.audit(adminId, "SAVE_POLICY", "ROUTE_POLICY", id, "strategy=" + strategy, "SUCCESS");
        return repository.findPolicyVersion(id).orElseThrow();
    }

    /** 原子发布：把草稿不可变地复制为 PUBLISHED 新版本。 */
    public RoutePolicyVersion publish(Long adminId, Long versionId) {
        RoutePolicyVersion draft = repository.findPolicyVersion(versionId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROUTE_POLICY_NOT_FOUND, HttpStatus.NOT_FOUND, "策略版本不存在"));
        int nextVersion = repository.maxPolicyVersionNo(draft.policyId()) + 1;
        Long publishedId = repository.publishVersion(draft.id(), nextVersion, draft.strategy(), draft.candidates(),
                draft.fallbackChain(), draft.weights(), draft.affinityTtlSeconds());
        repository.audit(adminId, "PUBLISH_POLICY", "ROUTE_POLICY", publishedId,
                "version=" + nextVersion, "SUCCESS");
        return repository.findPolicyVersion(publishedId).orElseThrow();
    }

    /** 回滚：以指定历史版本为基准，发布一个新的 PUBLISHED 版本（保留历史）。 */
    public RoutePolicyVersion rollback(Long adminId, Long versionId) {
        RoutePolicyVersion base = repository.findPolicyVersion(versionId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROUTE_POLICY_NOT_FOUND, HttpStatus.NOT_FOUND, "回滚目标版本不存在"));
        int nextVersion = repository.maxPolicyVersionNo(base.policyId()) + 1;
        Long publishedId = repository.publishVersion(base.id(), nextVersion, base.strategy(), base.candidates(),
                base.fallbackChain(), base.weights(), base.affinityTtlSeconds());
        repository.audit(adminId, "ROLLBACK_POLICY", "ROUTE_POLICY", publishedId,
                "from=" + base.versionNo() + " to=" + nextVersion, "SUCCESS");
        return repository.findPolicyVersion(publishedId).orElseThrow();
    }

    public List<RoutePolicyVersion> listVersions(Long policyId) {
        return repository.listPolicyVersions(policyId);
    }

    public RoutePolicyVersion currentPublished(Long policyId) {
        return repository.findPublishedPolicy(policyId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROUTE_POLICY_NOT_PUBLISHED,
                        HttpStatus.NOT_FOUND, "策略尚未发布"));
    }

    private static void validateStrategy(String strategy) {
        try {
            RouteStrategy.from(strategy);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "未知路由策略: " + strategy);
        }
    }
}
