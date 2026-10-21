package com.queryforge.domain.model;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型服务配置。密钥只从环境变量或本地未提交配置加载，不进入响应与日志。
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String model,
        Duration connectTimeout,
        Duration requestTimeout
) {

    public LlmProperties {
        baseUrl = normalizeBaseUrl(baseUrl);
        model = (model == null || model.isBlank()) ? "deepseek-chat" : model;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    private static String normalizeBaseUrl(String value) {
        String candidate = (value == null || value.isBlank()) ? "https://api.deepseek.com/v1" : value.trim();
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }
}
