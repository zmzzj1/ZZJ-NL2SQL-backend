package com.queryforge.model;

import com.queryforge.integration.llm.LlmClient;
import com.queryforge.integration.llm.LlmProperties;
import com.queryforge.integration.llm.LlmStream;
import com.queryforge.integration.llm.ModelTransport;
import com.queryforge.model.ModelEntities.Profile;
import com.queryforge.model.ModelEntities.Provider;
import com.queryforge.model.ModelRouter.CallRequirement;
import com.queryforge.model.ModelRouter.RouteDecision;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * P9/P11 统一模型调用入口（始终 {@code @Primary}，唯一 LlmClient）。
 * 通过 ModelRouter 选择实际 Provider/Model，带能力过滤、授权、熔断、有界重试与故障转移，
 * 每次尝试记录 attempt 并上报健康。复用现有 LlmClient 接口，业务调用方透明升级。
 *
 * <p>P11 修正：底层模型 I/O 委托给 {@link ModelTransport}（默认 {@code HttpModelTransport}；
 * 可选 {@code SpringAiModelTransport}）。Spring AI 只作底层执行引擎，**不替换、不绕过**
 * 本类与 P9 治理链路（路由/配额/审计/熔断/故障转移），符合指南 §5.1「不建第二套路由」。
 *
 * <p>兼容策略：当 ModelCatalog 中没有任何可用 profile 时，回退到 LlmProperties 的默认单 Provider，
 * 保证 P1-P8 既有行为不被破坏。
 *
 * <p>安全边界：不绕过 SQL Guard、审批、工具白名单；断流不拼接其他模型。
 */
@Component
@Primary
public class RoutedLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(RoutedLlmClient.class);
    private static final int MAX_ATTEMPTS = 3;

    private final ModelRouter router;
    private final ModelCatalogService catalog;
    private final InvocationRecorder recorder;
    private final LlmProperties properties;
    private final ModelTransport transport;
    private final ModelSessionAffinity sessionAffinity;

    public RoutedLlmClient(ModelRouter router, ModelCatalogService catalog, InvocationRecorder recorder,
                           LlmProperties properties, ModelTransport transport,
                           ModelSessionAffinity sessionAffinity) {
        this.router = router;
        this.catalog = catalog;
        this.recorder = recorder;
        this.properties = properties;
        this.transport = transport;
        this.sessionAffinity = sessionAffinity;
    }

    /** 路由策略 id / 授权上下文由 ModelRouteContextHolder 提供（业务层设置）。 */
    @Override
    public LlmStream open(String userInput, String schemaContext) {
        return openChat(null, userInput);
    }

    @Override
    public LlmStream openChat(String systemPrompt, String userPrompt) {
        ModelRouteContextHolder.Context context = ModelRouteContextHolder.get();
        CallRequirement requirement = CallRequirement.chat(true, false, false);

        // P12 S4：会话亲和性——解析已绑定模型（仍走完整过滤链，见 ModelRouter）。
        Long affinityProfileId = context == null ? null
                : sessionAffinity.resolve(context.userId(), context.conversationId()).orElse(null);

        RouteDecision decision;
        try {
            decision = router.routeWithAffinity(
                    context == null ? null : context.policyId(),
                    context == null ? null : context.userId(),
                    context == null ? null : context.workspaceId(),
                    requirement,
                    affinityProfileId);
        } catch (ApiException ex) {
            // 目录为空或未授权时，回退到 LlmProperties 的默认单 Provider，保证 P1-P8 行为不变。
            if (ex.getErrorCode() == ErrorCode.MODEL_UNAVAILABLE) {
                return openDefault(systemPrompt, userPrompt);
            }
            throw ex;
        }

        // 路由成功后刷新会话绑定（滑动 TTL）；默认 Provider 回退不绑定（非目录 profile）。
        if (context != null) {
            sessionAffinity.bind(context.userId(), context.conversationId(), decision.profile().id());
        }

        String callId = recorder.newCallId();
        return openWithFallback(callId, decision, systemPrompt, userPrompt, context);
    }

    /** 默认单 Provider 回退（复用 LlmProperties），也记录 attempt。 */
    private LlmStream openDefault(String systemPrompt, String userPrompt) {
        String callId = recorder.newCallId();
        long start = System.currentTimeMillis();
        try {
            ModelTransport.Response response = transport.stream(
                    properties.baseUrl(), properties.apiKey(), properties.model(), systemPrompt, userPrompt);
            if (response.statusCode() != 200) {
                FailureClass failureClass = FailureClass.fromHttpStatus(response.statusCode());
                recorder.recordDefaultAttempt(callId, 1, "default", properties.model(), "FAILED",
                        failureClass, response.statusCode(), System.currentTimeMillis() - start);
                throw new ApiException(ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "模型服务暂不可用");
            }
            recorder.recordDefaultAttempt(callId, 1, "default", properties.model(), "SUCCESS",
                    null, 200, System.currentTimeMillis() - start);
            return new LlmStream(response.lines());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recorder.recordDefaultAttempt(callId, 1, "default", properties.model(), "INTERRUPTED",
                    FailureClass.TIMEOUT, null, System.currentTimeMillis() - start);
            throw new ApiException(ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "模型请求被中断", ex);
        } catch (IOException ex) {
            recorder.recordDefaultAttempt(callId, 1, "default", properties.model(), "FAILED",
                    FailureClass.TIMEOUT, null, System.currentTimeMillis() - start);
            throw new ApiException(ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "无法连接模型服务", ex);
        }
    }

    /**
     * 有界重试 + 故障转移。候选链：首选决策 + fallbackChain。
     * 只在模型尚未输出、且错误可重试（429/5xx/超时）时尝试下一个。
     */
    private LlmStream openWithFallback(String callId, RouteDecision decision, String systemPrompt,
                                       String userPrompt, ModelRouteContextHolder.Context context) {
        Profile[] candidates = buildCandidateChain(decision);
        ApiException lastError = null;

        for (int attempt = 0; attempt < Math.min(candidates.length, MAX_ATTEMPTS); attempt++) {
            Profile profile = candidates[attempt];
            Provider provider = catalog.requireProvider(profile.providerId());
            String apiKey = catalog.decryptKey(provider);
            int attemptNo = attempt + 1;
            long start = System.currentTimeMillis();

            try {
                ModelTransport.Response response = transport.stream(
                        provider.baseUrl(), apiKey, profile.modelId(), systemPrompt, userPrompt);
                if (response.statusCode() == 200) {
                    recorder.recordAttempt(callId, attemptNo, context == null ? null : context.runId(),
                            context == null ? null : context.agentId(), profile, provider, decision.strategy().name(),
                            "SUCCESS", null, 200, System.currentTimeMillis() - start, null, null, null);
                    return new LlmStream(response.lines());
                }
                FailureClass failureClass = FailureClass.fromHttpStatus(response.statusCode());
                recorder.recordAttempt(callId, attemptNo, context == null ? null : context.runId(),
                        context == null ? null : context.agentId(), profile, provider, decision.strategy().name(),
                        "FAILED", failureClass, response.statusCode(), System.currentTimeMillis() - start,
                        null, null, null);
                lastError = new ApiException(ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY,
                        "模型服务暂不可用（" + response.statusCode() + "）");
                if (!failureClass.retryable()) {
                    break; // 401/403/400 不重试
                }
            } catch (IOException ex) {
                recorder.recordAttempt(callId, attemptNo, context == null ? null : context.runId(),
                        context == null ? null : context.agentId(), profile, provider, decision.strategy().name(),
                        "FAILED", FailureClass.TIMEOUT, null, System.currentTimeMillis() - start, null, null, null);
                lastError = new ApiException(ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "无法连接模型服务");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                recorder.recordAttempt(callId, attemptNo, context == null ? null : context.runId(),
                        context == null ? null : context.agentId(), profile, provider, decision.strategy().name(),
                        "INTERRUPTED", FailureClass.TIMEOUT, null, System.currentTimeMillis() - start, null, null, null);
                throw new ApiException(ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "模型请求被中断", ex);
            }
        }
        throw lastError == null
                ? new ApiException(ErrorCode.MODEL_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "没有可用模型")
                : lastError;
    }

    /** 候选链：首选 + fallbackChain（去重）。 */
    private Profile[] buildCandidateChain(RouteDecision decision) {
        var list = new java.util.ArrayList<Profile>();
        list.add(decision.profile());
        for (Profile fallback : decision.fallback()) {
            if (!list.contains(fallback)) {
                list.add(fallback);
            }
        }
        return list.toArray(new Profile[0]);
    }
}
