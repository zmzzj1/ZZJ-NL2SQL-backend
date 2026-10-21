package com.queryforge.infrastructure.persistence;

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
import com.queryforge.domain.analysis.AnalysisStatus;

/**
 * 分析运行与工件持久化。读取一律带 user_id，越权返回空。状态流转用条件更新保证幂等。
 */
@Repository
public class AnalysisRepository {

    private static final String RUN_COLUMNS = """
            id, user_id, conversation_id, connection_id, agent_run_id, agent_step_id, query_history_id,
            source_sql, engine, status, input_row_count, input_byte_limit, error_code, error_message,
            stdout_tail, stderr_tail, exit_code, duration_ms, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public AnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insertRun(Long userId, Long conversationId, Long connectionId, Long agentRunId,
                          Long agentStepId, Long queryHistoryId, String sourceSql, String engine,
                          int inputRowCount, int inputByteLimit) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO analysis_runs
                        (user_id, conversation_id, connection_id, agent_run_id, agent_step_id, query_history_id,
                         source_sql, engine, status, input_row_count, input_byte_limit)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            setNullableLong(statement, 2, conversationId);
            setNullableLong(statement, 3, connectionId);
            setNullableLong(statement, 4, agentRunId);
            setNullableLong(statement, 5, agentStepId);
            setNullableLong(statement, 6, queryHistoryId);
            statement.setString(7, sourceSql);
            statement.setString(8, engine);
            statement.setString(9, AnalysisStatus.RUNNING.name());
            statement.setInt(10, inputRowCount);
            statement.setInt(11, inputByteLimit);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("分析运行创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<AnalysisRun> findOwned(Long id, Long userId) {
        List<AnalysisRun> rows = jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS + " FROM analysis_runs WHERE id = ? AND user_id = ?",
                (resultSet, rowNum) -> mapRun(resultSet),
                id,
                userId);
        return rows.stream().findFirst();
    }

    public void completeRun(Long id, String stdoutTail, String stderrTail, int exitCode, long durationMs) {
        jdbcTemplate.update("""
                        UPDATE analysis_runs
                        SET status = ?, stdout_tail = ?, stderr_tail = ?, exit_code = ?, duration_ms = ?, updated_at = ?
                        WHERE id = ?
                        """,
                AnalysisStatus.SUCCEEDED.name(), stdoutTail, stderrTail, exitCode, durationMs,
                LocalDateTime.now(), id);
    }

    public void failRun(Long id, String errorCode, String errorMessage, String stderrTail, long durationMs) {
        jdbcTemplate.update("""
                        UPDATE analysis_runs
                        SET status = ?, error_code = ?, error_message = ?, stderr_tail = ?, duration_ms = ?, updated_at = ?
                        WHERE id = ?
                        """,
                AnalysisStatus.FAILED.name(), errorCode, errorMessage, stderrTail, durationMs,
                LocalDateTime.now(), id);
    }

    /** 幂等取消：仅活动状态可被取消。 */
    public int cancelActiveRun(Long id, Long userId) {
        return jdbcTemplate.update("""
                        UPDATE analysis_runs SET status = ?, updated_at = ?
                        WHERE id = ? AND user_id = ? AND status IN (?, ?)
                        """,
                AnalysisStatus.CANCELED.name(), LocalDateTime.now(), id, userId,
                AnalysisStatus.RUNNING.name(), AnalysisStatus.QUEUED.name());
    }

    public void insertArtifact(Long analysisRunId, Long userId, String kind, String mimeType,
                               int sizeBytes, String content) {
        jdbcTemplate.update("""
                        INSERT INTO analysis_artifacts (analysis_run_id, user_id, kind, mime_type, size_bytes, content)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                analysisRunId, userId, kind, mimeType, sizeBytes, content);
    }

    public List<AnalysisArtifact> findArtifacts(Long analysisRunId, Long userId) {
        return jdbcTemplate.query("""
                        SELECT id, analysis_run_id, user_id, kind, mime_type, size_bytes, content, created_at
                        FROM analysis_artifacts WHERE analysis_run_id = ? AND user_id = ? ORDER BY id
                        """,
                (resultSet, rowNum) -> new AnalysisArtifact(
                        resultSet.getLong("id"),
                        resultSet.getLong("analysis_run_id"),
                        resultSet.getLong("user_id"),
                        resultSet.getString("kind"),
                        resultSet.getString("mime_type"),
                        resultSet.getInt("size_bytes"),
                        resultSet.getString("content"),
                        resultSet.getTimestamp("created_at").toLocalDateTime()),
                analysisRunId,
                userId);
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static AnalysisRun mapRun(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new AnalysisRun(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                nullable(resultSet, "conversation_id"),
                nullable(resultSet, "connection_id"),
                nullable(resultSet, "agent_run_id"),
                nullable(resultSet, "agent_step_id"),
                nullable(resultSet, "query_history_id"),
                resultSet.getString("source_sql"),
                resultSet.getString("engine"),
                AnalysisStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("input_row_count"),
                resultSet.getInt("input_byte_limit"),
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getString("stdout_tail"),
                resultSet.getString("stderr_tail"),
                resultSet.getInt("exit_code"),
                resultSet.getInt("duration_ms"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getTimestamp("updated_at").toLocalDateTime());
    }

    private static Long nullable(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    public record AnalysisRun(
            Long id,
            Long userId,
            Long conversationId,
            Long connectionId,
            Long agentRunId,
            Long agentStepId,
            Long queryHistoryId,
            String sourceSql,
            String engine,
            AnalysisStatus status,
            int inputRowCount,
            int inputByteLimit,
            String errorCode,
            String errorMessage,
            String stdoutTail,
            String stderrTail,
            int exitCode,
            int durationMs,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record AnalysisArtifact(
            Long id,
            Long analysisRunId,
            Long userId,
            String kind,
            String mimeType,
            int sizeBytes,
            String content,
            LocalDateTime createdAt
    ) {
    }
}
