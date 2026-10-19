package com.queryforge.report;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 报告持久化。读取与删除均带 user_id；不暴露物理文件路径（内容存受控列）。
 */
@Repository
public class ReportRepository {

    private static final String COLUMNS = """
            id, user_id, connection_id, agent_run_id, analysis_run_id, title, status,
            html_content, size_bytes, revision, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public ReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insert(Long userId, Long connectionId, Long agentRunId, Long analysisRunId,
                       String title, String htmlContent) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO reports
                        (user_id, connection_id, agent_run_id, analysis_run_id, title, status, html_content, size_bytes)
                    VALUES (?, ?, ?, ?, ?, 'READY', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            setNullableLong(statement, 2, connectionId);
            setNullableLong(statement, 3, agentRunId);
            setNullableLong(statement, 4, analysisRunId);
            statement.setString(5, title);
            statement.setString(6, htmlContent);
            statement.setInt(7, htmlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("报告创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<Report> findOwned(Long id, Long userId) {
        List<Report> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM reports WHERE id = ? AND user_id = ?",
                (resultSet, rowNum) -> map(resultSet),
                id,
                userId);
        return rows.stream().findFirst();
    }

    public List<Report> findByUser(Long userId, int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM reports WHERE user_id = ? ORDER BY id DESC LIMIT ?",
                (resultSet, rowNum) -> map(resultSet),
                userId,
                limit);
    }

    public int delete(Long id, Long userId) {
        return jdbcTemplate.update("DELETE FROM reports WHERE id = ? AND user_id = ?", id, userId);
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Report map(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new Report(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                nullable(resultSet, "connection_id"),
                nullable(resultSet, "agent_run_id"),
                nullable(resultSet, "analysis_run_id"),
                resultSet.getString("title"),
                resultSet.getString("status"),
                resultSet.getString("html_content"),
                resultSet.getInt("size_bytes"),
                resultSet.getInt("revision"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getTimestamp("updated_at").toLocalDateTime());
    }

    private static Long nullable(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    public record Report(
            Long id,
            Long userId,
            Long connectionId,
            Long agentRunId,
            Long analysisRunId,
            String title,
            String status,
            String htmlContent,
            int sizeBytes,
            int revision,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt
    ) {
    }
}
