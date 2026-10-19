package com.queryforge.rag;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding 服务配置。默认复用 LLM 的 base-url 与 api-key（同一边界 DashScope），
 * 可独立覆盖。密钥只从环境变量或本地未提交配置加载，不进入响应与日志。
 */
@ConfigurationProperties(prefix = "app.embedding")
public record EmbeddingProperties(
        String baseUrl,
        String apiKey,
        String model,
        int dimensions,
        Duration requestTimeout
) {

    public EmbeddingProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.deepseek.com/v1" : baseUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        model = (model == null || model.isBlank()) ? "qwen3.7-text-embedding-flash" : model.trim();
        dimensions = dimensions <= 0 ? 1024 : dimensions;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
