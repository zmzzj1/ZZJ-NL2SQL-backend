package com.queryforge.application.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.queryforge.infrastructure.datasource.CredentialCipher;
import com.queryforge.domain.model.ModelEntities.Profile;
import com.queryforge.domain.model.ModelEntities.Provider;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.queryforge.infrastructure.persistence.ModelRepository;

/**
 * O1 模型目录与供应商管理。
 * 平台管理员维护受信任 Provider 与模型能力档案；Key 用 CredentialCipher 加密，明文永不回传。
 */
@Service
public class ModelCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogService.class);

    private final ModelRepository repository;
    private final CredentialCipher cipher;
    private final OutboundUrlGuard urlGuard;
    private final ObjectMapper objectMapper;

    public ModelCatalogService(ModelRepository repository, CredentialCipher cipher,
                               OutboundUrlGuard urlGuard, ObjectMapper objectMapper) {
        this.repository = repository;
        this.cipher = cipher;
        this.urlGuard = urlGuard;
        this.objectMapper = objectMapper;
    }

    // ---- Provider ----

    public Provider createProvider(Long adminId, String name, String protocol, String baseUrl, String apiKey) {
        urlGuard.validate(baseUrl);
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "API Key 不能为空");
        }
        Long id;
        try {
            id = repository.insertProvider(name, protocol, normalizeBaseUrl(baseUrl), cipher.encrypt(apiKey), adminId);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.CONFLICT, "供应商名称已存在");
        }
        repository.audit(adminId, "CREATE_PROVIDER", "PROVIDER", id, "name=" + name, "SUCCESS");
        return repository.findProvider(id).orElseThrow();
    }

    public List<Provider> listProviders() {
        return repository.listProviders();
    }

    public Provider updateProvider(Long adminId, Long id, String name, String baseUrl, boolean enabled) {
        Provider existing = requireProvider(id);
        if (baseUrl != null && !baseUrl.isBlank()) {
            urlGuard.validate(baseUrl);
            baseUrl = normalizeBaseUrl(baseUrl);
        } else {
            baseUrl = existing.baseUrl();
        }
        repository.updateProvider(id, name, baseUrl, enabled);
        repository.audit(adminId, "UPDATE_PROVIDER", "PROVIDER", id, "enabled=" + enabled, "SUCCESS");
        return repository.findProvider(id).orElseThrow();
    }

    public Provider rotateKey(Long adminId, Long id, String newApiKey) {
        requireProvider(id);
        if (newApiKey == null || newApiKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "新 Key 不能为空");
        }
        repository.rotateProviderKey(id, cipher.encrypt(newApiKey));
        repository.audit(adminId, "ROTATE_KEY", "PROVIDER", id, "key rotated", "SUCCESS");
        return repository.findProvider(id).orElseThrow();
    }

    /** 真实连接测试：向受信任地址发起一次最小请求，记录真实故障状态。 */
    public TestResult testProvider(Long adminId, Long id) {
        Provider provider = requireProvider(id);
        urlGuard.validate(provider.baseUrl());
        String apiKey = cipher.decrypt(provider.apiKeyCipher());
        long start = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder(URI.create(provider.baseUrl() + "/models"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            boolean ok = response.statusCode() == 200;
            repository.audit(adminId, "TEST_PROVIDER", "PROVIDER", id,
                    "status=" + response.statusCode() + " latency=" + latency, ok ? "SUCCESS" : "FAILED");
            return new TestResult(ok, response.statusCode(), latency);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            repository.audit(adminId, "TEST_PROVIDER", "PROVIDER", id, "error=" + ex.getMessage(), "FAILED");
            return new TestResult(false, null, System.currentTimeMillis() - start);
        }
    }

    // ---- Profile ----

    public Profile createProfile(Long adminId, Long providerId, String modelId, String modelType,
                                 boolean streaming, boolean toolCalls, boolean json, int contextLimit,
                                 int outputLimit, Integer embeddingDim) {
        requireProvider(providerId);
        Long id;
        try {
            id = repository.insertProfile(providerId, modelId, modelType.toUpperCase(), streaming, toolCalls,
                    json, contextLimit, outputLimit, embeddingDim);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.CONFLICT, "该供应商下模型已存在");
        }
        repository.audit(adminId, "CREATE_PROFILE", "PROFILE", id, "model=" + modelId, "SUCCESS");
        return repository.findProfile(id).orElseThrow();
    }

    public List<Profile> listProfiles() {
        return repository.listProfiles();
    }

    public Profile updateProfile(Long adminId, Long id, boolean streaming, boolean toolCalls, boolean json,
                                 int contextLimit, int outputLimit, String status) {
        requireProfile(id);
        repository.updateProfile(id, streaming, toolCalls, json, contextLimit, outputLimit, status.toUpperCase());
        repository.audit(adminId, "UPDATE_PROFILE", "PROFILE", id, "status=" + status, "SUCCESS");
        return repository.findProfile(id).orElseThrow();
    }

    // ---- Grant ----

    public void grant(Long adminId, Long profileId, String grantType, Long granteeId) {
        requireProfile(profileId);
        repository.insertGrant(profileId, grantType.toUpperCase(), granteeId, adminId);
        repository.audit(adminId, "GRANT_MODEL", "PROFILE", profileId,
                "type=" + grantType + " grantee=" + granteeId, "SUCCESS");
    }

    public void revoke(Long adminId, Long profileId, String grantType, Long granteeId) {
        requireProfile(profileId);
        repository.revokeGrant(profileId, grantType.toUpperCase(), granteeId);
        repository.audit(adminId, "REVOKE_MODEL", "PROFILE", profileId,
                "type=" + grantType + " grantee=" + granteeId, "SUCCESS");
    }

    // ---- accessors for router ----

    public Provider requireProvider(Long id) {
        return repository.findProvider(id)
                .orElseThrow(() -> new ApiException(ErrorCode.PROVIDER_NOT_FOUND, HttpStatus.NOT_FOUND, "供应商不存在"));
    }

    public Profile requireProfile(Long id) {
        return repository.findProfile(id)
                .orElseThrow(() -> new ApiException(ErrorCode.MODEL_PROFILE_NOT_FOUND, HttpStatus.NOT_FOUND, "模型不存在"));
    }

    public String decryptKey(Provider provider) {
        return cipher.decrypt(provider.apiKeyCipher());
    }

    public record TestResult(boolean ok, Integer httpStatus, long latencyMs) {
    }

    private static String normalizeBaseUrl(String value) {
        String candidate = value.trim();
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }
}
