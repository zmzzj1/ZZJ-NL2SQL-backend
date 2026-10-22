package com.queryforge.infrastructure.persistence;

import com.queryforge.domain.model.ModelEntities.Profile;
import com.queryforge.domain.model.ModelEntities.Provider;
import com.queryforge.domain.model.ModelEntities.RoutePolicyVersion;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** P9 模型供应商 / 能力档案 / 授权 / 路由策略版本 / 调用尝试 / 配额 / 审计 持久化。 */
@Repository
public class ModelRepository {

    private static final String PROV_COLUMNS =
            "id, name, protocol, base_url, api_key_cipher, enabled, created_by, created_at, updated_at";

    private static final String PROFILE_COLUMNS =
            "id, provider_id, model_id, model_type, supports_streaming, supports_tool_calls, supports_json,"
                    + " context_limit, output_limit, embedding_dim, status, capabilities_verified, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public ModelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---- Provider ----

    public Long insertProvider(String name, String protocol, String baseUrl, String apiKeyCipher, Long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO model_providers (name, protocol, base_url, api_key_cipher, enabled, created_by)
                    VALUES (?, ?, ?, ?, 1, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, protocol);
            statement.setString(3, baseUrl);
            statement.setString(4, apiKeyCipher);
            statement.setLong(5, createdBy);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public Optional<Provider> findProvider(Long id) {
        List<Provider> rows = jdbcTemplate.query(
                "SELECT " + PROV_COLUMNS + " FROM model_providers WHERE id = ?",
                (rs, rowNum) -> mapProvider(rs), id);
        return rows.stream().findFirst();
    }

    public List<Provider> listProviders() {
        return jdbcTemplate.query(
                "SELECT " + PROV_COLUMNS + " FROM model_providers ORDER BY id",
                (rs, rowNum) -> mapProvider(rs));
    }

    public int updateProvider(Long id, String name, String baseUrl, boolean enabled) {
        return jdbcTemplate.update("""
                        UPDATE model_providers SET name = ?, base_url = ?, enabled = ?, updated_at = ?
                        WHERE id = ?
                        """,
                name, baseUrl, enabled, LocalDateTime.now(), id);
    }

    public int rotateProviderKey(Long id, String apiKeyCipher) {
        return jdbcTemplate.update("""
                        UPDATE model_providers SET api_key_cipher = ?, updated_at = ? WHERE id = ?
                        """,
                apiKeyCipher, LocalDateTime.now(), id);
    }

    // ---- Profile ----

    public Long insertProfile(Long providerId, String modelId, String modelType, boolean streaming,
                              boolean toolCalls, boolean json, int contextLimit, int outputLimit, Integer embeddingDim) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO model_profiles
                        (provider_id, model_id, model_type, supports_streaming, supports_tool_calls, supports_json,
                         context_limit, output_limit, embedding_dim, status, capabilities_verified)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, providerId);
            statement.setString(2, modelId);
            statement.setString(3, modelType);
            statement.setBoolean(4, streaming);
            statement.setBoolean(5, toolCalls);
            statement.setBoolean(6, json);
            statement.setInt(7, contextLimit);
            statement.setInt(8, outputLimit);
            if (embeddingDim == null) {
                statement.setNull(9, Types.INTEGER);
            } else {
                statement.setInt(9, embeddingDim);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public Optional<Profile> findProfile(Long id) {
        List<Profile> rows = jdbcTemplate.query(
                "SELECT " + PROFILE_COLUMNS + " FROM model_profiles WHERE id = ?",
                (rs, rowNum) -> mapProfile(rs), id);
        return rows.stream().findFirst();
    }

    public List<Profile> listProfiles() {
        return jdbcTemplate.query(
                "SELECT " + PROFILE_COLUMNS + " FROM model_profiles ORDER BY provider_id, id",
                (rs, rowNum) -> mapProfile(rs));
    }

    public List<Profile> listProfilesByProvider(Long providerId) {
        return jdbcTemplate.query(
                "SELECT " + PROFILE_COLUMNS + " FROM model_profiles WHERE provider_id = ? ORDER BY id",
                (rs, rowNum) -> mapProfile(rs), providerId);
    }

    public int updateProfile(Long id, boolean streaming, boolean toolCalls, boolean json,
                             int contextLimit, int outputLimit, String status) {
        return jdbcTemplate.update("""
                        UPDATE model_profiles
                        SET supports_streaming = ?, supports_tool_calls = ?, supports_json = ?,
                            context_limit = ?, output_limit = ?, status = ?, updated_at = ?
                        WHERE id = ?
                        """,
                streaming, toolCalls, json, contextLimit, outputLimit, status, LocalDateTime.now(), id);
    }

    public int markVerified(Long id, boolean verified) {
        return jdbcTemplate.update("""
                        UPDATE model_profiles SET capabilities_verified = ?, updated_at = ? WHERE id = ?
                        """,
                verified, LocalDateTime.now(), id);
    }

    // ---- Grant ----

    public Long insertGrant(Long profileId, String grantType, Long granteeId, Long grantedBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO model_grants (profile_id, grant_type, grantee_id, granted_by, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, profileId);
            statement.setString(2, grantType);
            statement.setLong(3, granteeId);
            statement.setLong(4, grantedBy);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public int revokeGrant(Long profileId, String grantType, Long granteeId) {
        return jdbcTemplate.update("""
                        UPDATE model_grants SET status = 'REVOKED', revoked_at = ? WHERE profile_id = ? AND grant_type = ? AND grantee_id = ?
                        """,
                LocalDateTime.now(), profileId, grantType, granteeId);
    }

    public boolean isGranted(Long profileId, String grantType, Long granteeId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM model_grants WHERE profile_id = ? AND grant_type = ? AND grantee_id = ? AND status = 'ACTIVE'
                        """,
                Integer.class, profileId, grantType, granteeId);
        return count != null && count > 0;
    }

    // ---- Route Policy ----

    public Long insertPolicyVersion(Long policyId, int versionNo, String strategy, String candidates,
                                    String fallbackChain, String weights, int affinityTtlSeconds) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO route_policy_versions
                        (policy_id, version_no, strategy, candidates, fallback_chain, weights, affinity_ttl_s, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, policyId);
            statement.setInt(2, versionNo);
            statement.setString(3, strategy);
            statement.setString(4, candidates);
            statement.setString(5, fallbackChain);
            statement.setString(6, weights);
            statement.setInt(7, affinityTtlSeconds);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public Optional<RoutePolicyVersion> findPublishedPolicy(Long policyId) {
        List<RoutePolicyVersion> rows = jdbcTemplate.query("""
                        SELECT id, policy_id, version_no, strategy, candidates, fallback_chain, weights,
                               affinity_ttl_s, status, published_at, created_at
                        FROM route_policy_versions WHERE policy_id = ? AND status = 'PUBLISHED'
                        ORDER BY version_no DESC LIMIT 1
                        """,
                (rs, rowNum) -> mapPolicy(rs), policyId);
        return rows.stream().findFirst();
    }

    public Optional<RoutePolicyVersion> findPolicyVersion(Long versionId) {
        List<RoutePolicyVersion> rows = jdbcTemplate.query("""
                        SELECT id, policy_id, version_no, strategy, candidates, fallback_chain, weights,
                               affinity_ttl_s, status, published_at, created_at
                        FROM route_policy_versions WHERE id = ?
                        """,
                (rs, rowNum) -> mapPolicy(rs), versionId);
        return rows.stream().findFirst();
    }

    public List<RoutePolicyVersion> listPolicyVersions(Long policyId) {
        return jdbcTemplate.query("""
                        SELECT id, policy_id, version_no, strategy, candidates, fallback_chain, weights,
                               affinity_ttl_s, status, published_at, created_at
                        FROM route_policy_versions WHERE policy_id = ? ORDER BY version_no DESC
                        """,
                (rs, rowNum) -> mapPolicy(rs), policyId);
    }

    public Long publishVersion(Long versionId, int newVersionNo, String strategy, String candidates,
                               String fallbackChain, String weights, int affinityTtlSeconds) {
        // 发布为不可变新版本：保留历史，插入 PUBLISHED 快照。
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO route_policy_versions
                        (policy_id, version_no, strategy, candidates, fallback_chain, weights, affinity_ttl_s, status, published_at)
                    SELECT policy_id, ?, ?, ?, ?, ?, ?, 'PUBLISHED', ? FROM route_policy_versions WHERE id = ?
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, newVersionNo);
            statement.setString(2, strategy);
            statement.setString(3, candidates);
            statement.setString(4, fallbackChain);
            statement.setString(5, weights);
            statement.setInt(6, affinityTtlSeconds);
            statement.setObject(7, LocalDateTime.now());
            statement.setLong(8, versionId);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public int maxPolicyVersionNo(Long policyId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT COALESCE(MAX(version_no), 0) FROM route_policy_versions WHERE policy_id = ?",
                (rs, rowNum) -> rs.getInt(1), policyId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    // ---- Invocation attempt ----

    public Long insertAttempt(String callId, int attemptNo, Long runId, Long agentId, Long profileId,
                              String providerName, String modelId, String modelType, String routeReason,
                              String status, String errorClass, Integer httpStatus, Integer latencyMs,
                              Integer ttftMs, Integer promptTokens, Integer completionTokens,
                              Integer totalTokens, java.math.BigDecimal costEstimate, String costCurrency,
                              String costStatus) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO invocation_attempts
                        (call_id, attempt_no, run_id, agent_id, profile_id, provider_name, model_id, model_type,
                         route_reason, status, error_class, http_status, latency_ms, ttft_ms,
                         prompt_tokens, completion_tokens, total_tokens, cost_estimate, cost_currency, cost_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, callId);
            statement.setInt(2, attemptNo);
            setLongOrNull(statement, 3, runId);
            setLongOrNull(statement, 4, agentId);
            setLongOrNull(statement, 5, profileId);
            statement.setString(6, providerName);
            statement.setString(7, modelId);
            statement.setString(8, modelType);
            statement.setString(9, routeReason);
            statement.setString(10, status);
            statement.setString(11, errorClass);
            setIntOrNull(statement, 12, httpStatus);
            setIntOrNull(statement, 13, latencyMs);
            setIntOrNull(statement, 14, ttftMs);
            setIntOrNull(statement, 15, promptTokens);
            setIntOrNull(statement, 16, completionTokens);
            setIntOrNull(statement, 17, totalTokens);
            statement.setBigDecimal(18, costEstimate);
            statement.setString(19, costCurrency);
            statement.setString(20, costStatus);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public long countAttemptsSince(LocalDateTime since) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invocation_attempts WHERE created_at >= ?", Long.class, since);
        return count == null ? 0 : count;
    }

    // ---- Quota ----

    public void upsertQuota(String scopeType, Long scopeId, Integer requestsLimit, Integer concurrentLimit,
                            Long tokenLimit, java.math.BigDecimal costLimit, String costCurrency, Long updatedBy) {
        jdbcTemplate.update("""
                        INSERT INTO platform_quotas
                            (scope_type, scope_id, requests_limit, concurrent_limit, token_limit, cost_limit, cost_currency, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE requests_limit = VALUES(requests_limit), concurrent_limit = VALUES(concurrent_limit),
                            token_limit = VALUES(token_limit), cost_limit = VALUES(cost_limit), cost_currency = VALUES(cost_currency),
                            updated_by = VALUES(updated_by)
                        """,
                scopeType, scopeId, requestsLimit, concurrentLimit, tokenLimit, costLimit, costCurrency, updatedBy);
    }

    public record QuotaRow(Integer requestsLimit, Integer concurrentLimit, Long tokenLimit,
                           java.math.BigDecimal costLimit, String costCurrency) {
    }

    public Optional<QuotaRow> findQuota(String scopeType, Long scopeId) {
        List<QuotaRow> rows = jdbcTemplate.query("""
                        SELECT requests_limit, concurrent_limit, token_limit, cost_limit, cost_currency
                        FROM platform_quotas WHERE scope_type = ? AND scope_id = ?
                        """,
                (rs, rowNum) -> new QuotaRow(
                        rs.getInt("requests_limit"), rs.getInt("concurrent_limit"), rs.getLong("token_limit"),
                        rs.getBigDecimal("cost_limit"), rs.getString("cost_currency")),
                scopeType, scopeId);
        return rows.stream().findFirst();
    }

    /**
     * P13-M3B：批量读取 USER 维度 Token 配额（scope_type='USER'）。
     * 入参均为系统内部 Long 主键，非外部字符串，IN 子句无拼接注入面；空列表直接返回空 Map。
     */
    public java.util.Map<Long, Long> findUserTokenQuotas(java.util.List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return java.util.Map.of();
        }
        String in = userIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        List<java.util.Map.Entry<Long, Long>> rows = jdbcTemplate.query(
                "SELECT scope_id, token_limit FROM platform_quotas WHERE scope_type = 'USER' AND scope_id IN ("
                        + in + ")",
                (rs, rowNum) -> java.util.Map.entry(rs.getLong("scope_id"), rs.getLong("token_limit")));
        java.util.Map<Long, Long> map = new java.util.HashMap<>();
        for (java.util.Map.Entry<Long, Long> row : rows) {
            map.put(row.getKey(), row.getValue());
        }
        return map;
    }

    // ---- Audit ----

    public void audit(Long actorId, String action, String targetType, Long targetId, String detail, String result) {
        jdbcTemplate.update("""
                        INSERT INTO admin_audits (actor_id, action, target_type, target_id, detail, result)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                actorId, action, targetType, targetId, detail, result);
    }

    public record AuditRow(Long id, Long actorId, String action, String targetType, Long targetId,
                           String detail, String result, LocalDateTime createdAt) {
    }

    public List<AuditRow> listAudits(int limit) {
        return jdbcTemplate.query("""
                        SELECT id, actor_id, action, target_type, target_id, detail, result, created_at
                        FROM admin_audits ORDER BY id DESC LIMIT ?
                        """,
                (rs, rowNum) -> new AuditRow(
                        rs.getLong("id"), rs.getLong("actor_id"), rs.getString("action"),
                        rs.getString("target_type"), rs.getLong("target_id"), rs.getString("detail"),
                        rs.getString("result"), rs.getTimestamp("created_at").toLocalDateTime()),
                limit);
    }

    // ---- mapping helpers ----

    private static Provider mapProvider(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Provider(
                rs.getLong("id"), rs.getString("name"), rs.getString("protocol"), rs.getString("base_url"),
                rs.getString("api_key_cipher"), rs.getBoolean("enabled"), rs.getLong("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private static Profile mapProfile(java.sql.ResultSet rs) throws java.sql.SQLException {
        int embeddingDim = rs.getInt("embedding_dim");
        Integer nullableDim = rs.wasNull() ? null : embeddingDim;
        return new Profile(
                rs.getLong("id"), rs.getLong("provider_id"), rs.getString("model_id"), rs.getString("model_type"),
                rs.getBoolean("supports_streaming"), rs.getBoolean("supports_tool_calls"), rs.getBoolean("supports_json"),
                rs.getInt("context_limit"), rs.getInt("output_limit"), nullableDim, rs.getString("status"),
                rs.getBoolean("capabilities_verified"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private static RoutePolicyVersion mapPolicy(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp published = rs.getTimestamp("published_at");
        LocalDateTime publishedAt = published == null ? null : published.toLocalDateTime();
        return new RoutePolicyVersion(
                rs.getLong("id"), rs.getLong("policy_id"), rs.getInt("version_no"), rs.getString("strategy"),
                rs.getString("candidates"), rs.getString("fallback_chain"), rs.getString("weights"),
                rs.getInt("affinity_ttl_s"), rs.getString("status"), publishedAt,
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private static void setLongOrNull(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setIntOrNull(PreparedStatement statement, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }
}
