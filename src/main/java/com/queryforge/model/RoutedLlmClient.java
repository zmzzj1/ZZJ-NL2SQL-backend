package com.queryforge.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.queryforge.integration.llm.LlmClient;
import com.queryforge.integration.llm.LlmProperties;
import com.queryforge.integration.llm.LlmStream;
import com.queryforge.model.ModelEntities.Profile;
import com.queryforge.model.ModelEntities.Provider;
import com.queryforge.model.ModelRouter.CallRequirement;
import com.queryforge.model.ModelRouter.RouteDecision;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * P9 统一模型调用入口（@Primary 替换旧单 Provider 客户端）。
 * 通过 ModelRouter 选择实际 Provider/Model，带能力过滤、授权、熔断、有界重试与故障转移，
 * 每次尝试记录 attempt 并上报健康。复用现有 LlmClient 接口，业务调用方透明升级。
 *
 * 兼容策略：当 ModelCatalog 中没有任何可用 profile 时，回退到 LlmProperties 的默认单 Provider，
 * 保证 P1-P8 既有行为不被破坏。
 *
 * 安全边界：不绕过 SQL Guard、审批、工具白名单；断流不拼接其他模型。
 */
@Component
@Primary
public class RoutedLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(RoutedLlmClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final ModelRouter router;
    private final ModelCatalogService catalog;
    private final InvocationRecorder recorder;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RoutedLlmClient(ModelRouter router, ModelCatalogService catalog, InvocationRecorder recorder,
                           LlmProperties properties, ObjectMapper objectMapper) {
        this.router = router;
        this.catalog = catalog;
        this.recorder = recorder;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
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

        RouteDecision decision;
        try {
            decision = router.route(
                    context == null ? null : context.policyId(),
                    context == null ? null : context.userId(),
                    context == null ? null : context.workspaceId(),
                    requirement);
        } catch (ApiException ex) {
            // 目录为空或未授权时，回退到 LlmProperties 的默认单 Provider，保证 P1-P8 行为不变。
            if (ex.getErrorCode() == ErrorCode.MODEL_UNAVAILABLE) {
                return openDefault(systemPrompt, userPrompt);
            }
            throw ex;
        }

        String callId = recorder.newCallId();
        return openWithFallback(callId, decision, systemPrompt, userPrompt, context);
    }

    /** 默认单 Provider 回退（复用 LlmProperties），也记录 attempt。 */
    private LlmStream openDefault(String systemPrompt, String userPrompt) {
        String body = buildBody(properties.model(), systemPrompt, userPrompt);
        String callId = recorder.newCallId();
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/chat/completions"))
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        long start = System.currentTimeMillis();
        try {
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                FailureClass failureClass = FailureClass.fromHttpStatus(response.statusCode());
                recorder.recordDefaultAttempt(callId, 1, "default", properties.model(), "FAILED",
                        failureClass, response.statusCode(), System.currentTimeMillis() - start);
                throw new ApiException(ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "模型服务暂不可用");
            }
            recorder.recordDefaultAttempt(callId, 1, "default", properties.model(), "SUCCESS",
                    null, 200, System.currentTimeMillis() - start);
            return new LlmStream(response.body());
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
                HttpResponse<Stream<String>> response = send(provider, apiKey, profile.modelId(), systemPrompt, userPrompt);
                if (response.statusCode() == 200) {
                    recorder.recordAttempt(callId, attemptNo, context == null ? null : context.runId(),
                            context == null ? null : context.agentId(), profile, provider, decision.strategy().name(),
                            "SUCCESS", null, 200, System.currentTimeMillis() - start, null, null, null);
                    return new LlmStream(response.body());
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

    private HttpResponse<Stream<String>> send(Provider provider, String apiKey, String modelId,
                                              String systemPrompt, String userPrompt)
            throws IOException, InterruptedException {
        String body = buildBody(modelId, systemPrompt, userPrompt);
        HttpRequest request = HttpRequest.newBuilder(URI.create(provider.baseUrl() + "/chat/completions"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
    }

    private String buildBody(String modelId, String systemPrompt, String userPrompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelId);
        root.put("stream", true);
        root.put("temperature", 0);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt == null ? "" : systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        return root.toString();
    }
}
