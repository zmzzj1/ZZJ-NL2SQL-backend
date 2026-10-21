package com.queryforge.domain.model;

import java.time.LocalDateTime;

/** P9 模型治理实体（record 风格，与既有模块一致）。 */
public final class ModelEntities {

    private ModelEntities() {
    }

    /** 模型供应商。apiKey 只存密文，明文永不返回。 */
    public record Provider(
            Long id,
            String name,
            String protocol,
            String baseUrl,
            String apiKeyCipher,
            boolean enabled,
            Long createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 模型能力档案。 */
    public record Profile(
            Long id,
            Long providerId,
            String modelId,
            String modelType,
            boolean supportsStreaming,
            boolean supportsToolCalls,
            boolean supportsJson,
            int contextLimit,
            int outputLimit,
            Integer embeddingDim,
            String status,
            boolean capabilitiesVerified,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public boolean isActive() {
            return "ACTIVE".equalsIgnoreCase(status);
        }

        public boolean isChat() {
            return "CHAT".equalsIgnoreCase(modelType);
        }

        public boolean isEmbedding() {
            return "EMBEDDING".equalsIgnoreCase(modelType);
        }
    }

    /** 模型使用授权。 */
    public record Grant(
            Long id,
            Long profileId,
            String grantType,
            Long granteeId,
            Long grantedBy,
            LocalDateTime grantedAt,
            LocalDateTime revokedAt,
            String status) {

        public boolean isActive() {
            return "ACTIVE".equalsIgnoreCase(status);
        }
    }

    /** 路由策略不可变版本。 */
    public record RoutePolicyVersion(
            Long id,
            Long policyId,
            int versionNo,
            String strategy,
            String candidates,
            String fallbackChain,
            String weights,
            int affinityTtlSeconds,
            String status,
            LocalDateTime publishedAt,
            LocalDateTime createdAt) {

        public boolean isPublished() {
            return "PUBLISHED".equalsIgnoreCase(status);
        }
    }
}
