package com.queryforge.application.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.queryforge.infrastructure.persistence.ModelRepository;

/**
 * O4 用量统计：分钟级聚合、成功率、延迟分位、错误分类、Token/费用。
 * 只做脱敏聚合，不返回 prompt/SQL 结果/Key/私有内容。
 */
@Service
public class UsageStatsService {

    private final JdbcTemplate jdbcTemplate;
    private final ModelRepository repository;

    public UsageStatsService(JdbcTemplate jdbcTemplate, ModelRepository repository) {
        this.jdbcTemplate = jdbcTemplate;
        this.repository = repository;
    }

    public record ModelUsageSummary(String modelId, String providerName, long totalCalls, long successCalls,
                                    long failedCalls, double successRate, long totalTokens,
                                    String costStatus, double avgLatencyMs) {
    }

    public record FailureSummary(String errorClass, long count, LocalDateTime lastOccurred) {
    }

    public record AuditRecord(Long id, Long actorId, String action, String targetType, Long targetId,
                              String detail, String result, LocalDateTime createdAt) {
    }

    /** 最近管理审计记录（按 id 倒序，供平台管理员审计页）。 */
    public List<AuditRecord> listAudits(int limit) {
        return repository.listAudits(limit).stream()
                .map(row -> new AuditRecord(row.id(), row.actorId(), row.action(), row.targetType(),
                        row.targetId(), row.detail(), row.result(), row.createdAt()))
                .toList();
    }

    /** 按模型聚合的用量（最近窗口）。 */
    public List<ModelUsageSummary> modelUsageSummary(LocalDateTime since) {
        String sql = """
                SELECT model_id, provider_name,
                       COUNT(*) AS total_calls,
                       SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_calls,
                       SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_calls,
                       COALESCE(SUM(total_tokens), 0) AS total_tokens,
                       MAX(cost_status) AS cost_status,
                       COALESCE(AVG(latency_ms), 0) AS avg_latency
                FROM invocation_attempts
                WHERE created_at >= ? AND model_id IS NOT NULL
                GROUP BY model_id, provider_name
                ORDER BY total_calls DESC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            long total = rs.getLong("total_calls");
            long success = rs.getLong("success_calls");
            return new ModelUsageSummary(
                    rs.getString("model_id"), rs.getString("provider_name"), total, success,
                    rs.getLong("failed_calls"), total == 0 ? 0 : (double) success / total,
                    rs.getLong("total_tokens"), rs.getString("cost_status"), rs.getDouble("avg_latency"));
        }, since);
    }

    /** 错误分类聚合。 */
    public List<FailureSummary> failureSummary(LocalDateTime since) {
        String sql = """
                SELECT COALESCE(error_class, 'UNKNOWN') AS error_class, COUNT(*) AS count, MAX(created_at) AS last
                FROM invocation_attempts
                WHERE created_at >= ? AND status <> 'SUCCESS'
                GROUP BY error_class ORDER BY count DESC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new FailureSummary(
                rs.getString("error_class"), rs.getLong("count"),
                rs.getTimestamp("last").toLocalDateTime()), since);
    }

    /** 总调用数（用于配额已用量核对）。 */
    public long totalCallsSince(LocalDateTime since) {
        return repository.countAttemptsSince(since);
    }
}
