package com.queryforge.platform.repository;

import com.queryforge.platform.entity.QueryHistoryRecord;
import com.queryforge.platform.entity.QueryStatus;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 查询历史只存元数据，不保存结果行；读取与删除均带 user_id。
 */
@Repository
public class QueryHistoryRepository {

    private static final String COLUMNS = """
            id, user_id, run_id, step_id, conversation_id, connection_id, connection_name_snapshot, sql_text,
            status, executed_at, duration_ms, row_count, truncated, error_code, error_message
            """;

    private final JdbcTemplate jdbcTemplate;

    public QueryHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(QueryHistoryRecord record) {
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO query_history
                        (user_id, run_id, step_id, conversation_id, connection_id, connection_name_snapshot,
                         sql_text, status, executed_at, duration_ms, row_count, truncated, error_code, error_message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setLong(1, record.userId());
            setNullableLong(statement, 2, record.runId());
            setNullableLong(statement, 3, record.stepId());
            setNullableLong(statement, 4, record.conversationId());
            setNullableLong(statement, 5, record.connectionId());
            statement.setString(6, record.connectionName());
            statement.setString(7, record.sql());
            statement.setString(8, record.status().name());
            statement.setObject(9, record.executedAt());
            statement.setInt(10, record.durationMs());
            if (record.rowCount() == null) {
                statement.setNull(11, Types.INTEGER);
            } else {
                statement.setInt(11, record.rowCount());
            }
            if (record.truncated() == null) {
                statement.setNull(12, Types.BOOLEAN);
            } else {
                statement.setBoolean(12, record.truncated());
            }
            statement.setString(13, record.errorCode());
            statement.setString(14, record.errorMessage());
            return statement;
        });
    }

    /** 按执行时间倒序；cursor 存在时使用 (executed_at, id) 键集分页。 */
    public List<QueryHistoryRecord> findPage(Long userId, int limitPlusOne, Long connectionId,
                                             LocalDateTime before, Long beforeId) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM query_history WHERE user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (connectionId != null) {
            sql.append(" AND connection_id = ?");
            args.add(connectionId);
        }
        if (before != null) {
            sql.append(" AND (executed_at < ? OR (executed_at = ? AND id < ?))");
            args.add(before);
            args.add(before);
            args.add(beforeId);
        }
        sql.append(" ORDER BY executed_at DESC, id DESC LIMIT ?");
        args.add(limitPlusOne);
        return jdbcTemplate.query(sql.toString(),
                (resultSet, rowNum) -> map(resultSet),
                args.toArray());
    }

    public Optional<QueryHistoryRecord> findOwned(Long id, Long userId) {
        List<QueryHistoryRecord> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM query_history WHERE id = ? AND user_id = ?",
                (resultSet, rowNum) -> map(resultSet),
                id,
                userId);
        return rows.stream().findFirst();
    }

    public int delete(Long id, Long userId) {
        return jdbcTemplate.update("DELETE FROM query_history WHERE id = ? AND user_id = ?", id, userId);
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static QueryHistoryRecord map(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        long runId = resultSet.getLong("run_id");
        Long nullableRunId = resultSet.wasNull() ? null : runId;
        long stepId = resultSet.getLong("step_id");
        Long nullableStepId = resultSet.wasNull() ? null : stepId;
        long conversationId = resultSet.getLong("conversation_id");
        Long nullableConversationId = resultSet.wasNull() ? null : conversationId;
        long connectionId = resultSet.getLong("connection_id");
        Long nullableConnectionId = resultSet.wasNull() ? null : connectionId;
        int rowCount = resultSet.getInt("row_count");
        Integer nullableRowCount = resultSet.wasNull() ? null : rowCount;
        boolean truncated = resultSet.getBoolean("truncated");
        Boolean nullableTruncated = resultSet.wasNull() ? null : truncated;
        return new QueryHistoryRecord(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                nullableRunId,
                nullableStepId,
                nullableConversationId,
                nullableConnectionId,
                resultSet.getString("connection_name_snapshot"),
                resultSet.getString("sql_text"),
                QueryStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("executed_at").toLocalDateTime(),
                resultSet.getInt("duration_ms"),
                nullableRowCount,
                nullableTruncated,
                resultSet.getString("error_code"),
                resultSet.getString("error_message"));
    }
}
