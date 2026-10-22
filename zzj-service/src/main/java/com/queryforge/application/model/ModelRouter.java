package com.queryforge.application.model;

import com.queryforge.domain.model.ModelEntities.Profile;
import com.queryforge.domain.model.ModelEntities.Provider;
import com.queryforge.domain.model.ModelEntities.RoutePolicyVersion;
import com.queryforge.application.model.ModelHealthRegistry.CircuitState;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.queryforge.domain.model.JsonIds;
import com.queryforge.domain.model.RouteStrategy;
import com.queryforge.infrastructure.persistence.ModelRepository;

/**
 * O2 统一模型路由：按固定顺序过滤候选（能力 → 授权 → 启用状态 → 类型 → 工具/流式/JSON → 健康/熔断），
 * 再用策略选出实际模型。输出决策含过滤原因，供观测与定位。
 */
@Service
public class ModelRouter {

    private final ModelRepository repository;
    private final ModelCatalogService catalog;
    private final ModelHealthRegistry health;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public ModelRouter(ModelRepository repository, ModelCatalogService catalog, ModelHealthRegistry health) {
        this.repository = repository;
        this.catalog = catalog;
        this.health = health;
    }

    /** 调用要求：模型类型与能力。 */
    public record CallRequirement(String modelType, boolean needsStreaming, boolean needsToolCalls, boolean needsJson) {
        public static CallRequirement chat(boolean needsStreaming, boolean needsToolCalls, boolean needsJson) {
            return new CallRequirement("CHAT", needsStreaming, needsToolCalls, needsJson);
        }

        public static CallRequirement embedding() {
            return new CallRequirement("EMBEDDING", false, false, false);
        }
    }

    /** 路由决策：实际选中的 profile + fallback 链 + 过滤原因 + 策略。 */
    public record RouteDecision(Profile profile, Provider provider, RouteStrategy strategy, String filterReason,
                                List<Profile> fallback) {
        public RouteDecision(Profile profile, Provider provider, RouteStrategy strategy, String filterReason) {
            this(profile, provider, strategy, filterReason, List.of());
        }
    }

    /**
     * 根据已发布策略路由一次调用。
     * 无已发布策略时，回退到"任一可用的受信任 Chat 模型"（默认 Provider 兼容）。
     */
    public RouteDecision route(Long policyId, Long userId, Long workspaceId, CallRequirement requirement) {
        return routeWithAffinity(policyId, userId, workspaceId, requirement, null);
    }

    /**
     * P12 S4：带会话亲和性的路由。{@code affinityProfileId} 非空且仍满足完整过滤链
     * （类型/启用/授权/Provider/熔断）时优先复用该模型（策略记为 SESSION_AFFINITY）；
     * 否则按策略正常选择。亲和性不降低任何准入检查。
     */
    public RouteDecision routeWithAffinity(Long policyId, Long userId, Long workspaceId,
                                           CallRequirement requirement, Long affinityProfileId) {
        RoutePolicyVersion policy = policyId == null ? null : repository.findPublishedPolicy(policyId).orElse(null);

        List<Profile> candidates;
        if (policy != null) {
            candidates = resolveCandidates(policy);
        } else {
            candidates = allActiveProfiles();
        }

        // 过滤链（顺序固定）
        List<Profile> eligible = new ArrayList<>();
        StringBuilder filterReason = new StringBuilder();
        for (Profile profile : candidates) {
            String reject = rejectReason(profile, userId, workspaceId, requirement);
            if (reject == null) {
                eligible.add(profile);
            } else {
                appendReason(filterReason, profile.modelId() + ":" + reject);
            }
        }

        if (eligible.isEmpty()) {
            throw new ApiException(ErrorCode.MODEL_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "没有可用模型：" + filterReason);
        }

        RouteStrategy strategy = policy == null ? RouteStrategy.ROUND_ROBIN : RouteStrategy.from(policy.strategy());
        Profile selected = select(strategy, eligible, policy);
        if (affinityProfileId != null) {
            Profile bound = eligible.stream()
                    .filter(profile -> profile.id().equals(affinityProfileId))
                    .findFirst()
                    .orElse(null);
            if (bound != null) {
                // 绑定模型仍合格：复用（fallback 链按常规构建，首选替换为绑定模型）
                selected = bound;
                strategy = RouteStrategy.SESSION_AFFINITY;
                appendReason(filterReason, "session-affinity:" + bound.id());
            }
        }
        Provider provider = catalog.requireProvider(selected.providerId());
        // fallback 链：候选/回退链中除首选外仍合格且已授权的模型。
        List<Profile> fallback = buildFallback(policy, eligible, selected, userId, workspaceId, requirement);
        return new RouteDecision(selected, provider, strategy, filterReason.toString(), fallback);
    }

    private List<Profile> buildFallback(RoutePolicyVersion policy, List<Profile> eligible, Profile selected,
                                        Long userId, Long workspaceId, CallRequirement requirement) {
        List<Long> fallbackIds = policy == null ? List.of() : RouteStrategy.parseIds(policy.fallbackChain());
        List<Profile> fallback = new ArrayList<>();
        for (Long id : fallbackIds) {
            repository.findProfile(id).ifPresent(profile -> {
                if (!profile.id().equals(selected.id()) && profile.isActive()
                        && rejectReason(profile, userId, workspaceId, requirement) == null) {
                    fallback.add(profile);
                }
            });
        }
        // 若未显式配置 fallback，用 eligible 中其余模型作为备选。
        if (fallback.isEmpty() && eligible.size() > 1) {
            for (Profile profile : eligible) {
                if (!profile.id().equals(selected.id())) {
                    fallback.add(profile);
                }
            }
        }
        return fallback;
    }

    private List<Profile> resolveCandidates(RoutePolicyVersion policy) {
        List<Long> ids = RouteStrategy.parseIds(policy.candidates());
        List<Profile> profiles = new ArrayList<>();
        for (Long id : ids) {
            repository.findProfile(id).filter(Profile::isActive).ifPresent(profiles::add);
        }
        return profiles;
    }

    private List<Profile> allActiveProfiles() {
        return repository.listProfiles().stream().filter(Profile::isActive).toList();
    }

    private String rejectReason(Profile profile, Long userId, Long workspaceId, CallRequirement requirement) {
        if (!profile.modelType().equalsIgnoreCase(requirement.modelType())) {
            return "type-mismatch";
        }
        if (!profile.isActive()) {
            return "profile-disabled";
        }
        if (requirement.needsStreaming() && !profile.supportsStreaming()) {
            return "no-streaming";
        }
        if (requirement.needsToolCalls() && !profile.supportsToolCalls()) {
            return "no-tool-calls";
        }
        if (requirement.needsJson() && !profile.supportsJson()) {
            return "no-json";
        }
        if (!isAuthorized(profile, userId, workspaceId)) {
            return "unauthorized";
        }
        Provider provider = catalog.requireProvider(profile.providerId());
        if (!provider.enabled()) {
            return "provider-disabled";
        }
        CircuitState state = health.circuitState(profile.id());
        if (state == CircuitState.OPEN) {
            return "circuit-open";
        }
        if (state == CircuitState.HALF_OPEN && !health.allowProbe(profile.id())) {
            return "circuit-half-open";
        }
        return null;
    }

    /** 授权：用户级 USER 授权，或 workspace 级 WORKSPACE 授权（profile 需被授予该空间）。 */
    private boolean isAuthorized(Profile profile, Long userId, Long workspaceId) {
        if (repository.isGranted(profile.id(), "USER", userId)) {
            return true;
        }
        if (workspaceId != null && repository.isGranted(profile.id(), "WORKSPACE", workspaceId)) {
            return true;
        }
        // 无任何授权记录时，视为"未配置准入"，拒绝（默认不开放）。
        return false;
    }

    private Profile select(RouteStrategy strategy, List<Profile> eligible, RoutePolicyVersion policy) {
        return switch (strategy) {
            case ROUND_ROBIN -> roundRobin(eligible);
            case LATENCY_FIRST -> latencyFirst(eligible);
            case SUCCESS_RATE_FIRST -> successRateFirst(eligible);
            case SMART_WEIGHTED -> smartWeighted(eligible, policy);
            // SESSION_AFFINITY 由 routeWithAffinity 在 select 之前直接命中；此处仅作编译完备分支。
            case SESSION_AFFINITY -> roundRobin(eligible);
        };
    }

    private Profile roundRobin(List<Profile> eligible) {
        int index = Math.floorMod(roundRobinCounter.getAndIncrement(), eligible.size());
        return eligible.get(index);
    }

    private Profile latencyFirst(List<Profile> eligible) {
        Profile best = eligible.get(0);
        double bestLatency = Double.MAX_VALUE;
        boolean anySample = false;
        for (Profile profile : eligible) {
            double latency = health.recentAvgLatency(profile.id());
            if (latency > 0) {
                anySample = true;
                if (latency < bestLatency) {
                    bestLatency = latency;
                    best = profile;
                }
            }
        }
        // 小样本回退基线（轮询）
        return anySample ? best : roundRobin(eligible);
    }

    private Profile successRateFirst(List<Profile> eligible) {
        Profile best = eligible.get(0);
        double bestRate = -1;
        boolean anySample = false;
        for (Profile profile : eligible) {
            double rate = health.recentSuccessRate(profile.id());
            if (rate >= 0) {
                anySample = true;
                if (rate > bestRate) {
                    bestRate = rate;
                    best = profile;
                }
            }
        }
        return anySample ? best : roundRobin(eligible);
    }

    private Profile smartWeighted(List<Profile> eligible, RoutePolicyVersion policy) {
        List<Double> weights = JsonIds.parseWeights(policy == null ? null : policy.weights());
        if (weights.size() != eligible.size()) {
            return roundRobin(eligible);
        }
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) {
            return roundRobin(eligible);
        }
        double r = Math.random() * total;
        double acc = 0;
        for (int i = 0; i < eligible.size(); i++) {
            acc += weights.get(i);
            if (r < acc) {
                return eligible.get(i);
            }
        }
        return eligible.get(eligible.size() - 1);
    }

    private static void appendReason(StringBuilder builder, String reason) {
        if (!builder.isEmpty()) {
            builder.append("; ");
        }
        builder.append(reason);
    }
}
