package com.queryforge.platform.repository;

import com.queryforge.platform.entity.Conversation;
import com.queryforge.platform.entity.ConversationMessage;
import com.queryforge.platform.entity.MessageRole;
import com.queryforge.platform.entity.MessageStatus;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 会话与消息持久化。所有会话查询都带 user_id，避免仅凭 ID 越权。
 */
@Repository
public class ConversationRepository {

    private static final String COLUMNS = """
            id, user_id, connection_id, connection_name_snapshot, title, created_at, last_activity_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insert(Long userId, Long connectionId, String connectionName, String title) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO conversations
                        (user_id, connection_id, connection_name_snapshot, title)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            if (connectionId == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, connectionId);
            }
            statement.setString(3, connectionName);
            statement.setString(4, title);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("会话创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<Conversation> findOwned(Long id, Long userId) {
        List<Conversation> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM conversations WHERE id = ? AND user_id = ?",
                (resultSet, rowNum) -> mapConversation(resultSet),
                id,
                userId);
        return rows.stream().findFirst();
    }

    /** 按最近活动时间倒序；cursor 存在时使用 (last_activity_at, id) 键集分页。 */
    public List<Conversation> findPage(Long userId, int limitPlusOne, LocalDateTime before, Long beforeId) {
        String base = "SELECT " + COLUMNS + " FROM conversations WHERE user_id = ?";
        if (before == null) {
            return jdbcTemplate.query(
                    base + " ORDER BY last_activity_at DESC, id DESC LIMIT ?",
                    (resultSet, rowNum) -> mapConversation(resultSet),
                    userId,
                    limitPlusOne);
        }
        return jdbcTemplate.query(
                base + """
                         AND (last_activity_at < ? OR (last_activity_at = ? AND id < ?))
                         ORDER BY last_activity_at DESC, id DESC LIMIT ?
                        """,
                (resultSet, rowNum) -> mapConversation(resultSet),
                userId,
                before,
                before,
                beforeId,
                limitPlusOne);
    }

    public int delete(Long id, Long userId) {
        return jdbcTemplate.update("DELETE FROM conversations WHERE id = ? AND user_id = ?", id, userId);
    }

    public Long insertMessage(Long conversationId, MessageRole role, String content,
                              MessageStatus status, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO conversation_messages (conversation_id, role, content, status, request_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, conversationId);
            statement.setString(2, role.name());
            statement.setString(3, content);
            statement.setString(4, status.name());
            statement.setString(5, requestId);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("消息创建后未返回主键");
        }
        return key.longValue();
    }

    /**
     * 终态只写一次：仅 IN_PROGRESS 可转为 COMPLETED/FAILED/CANCELED，
     * 避免取消与流结束竞态时互相覆盖。
     */
    public void finishMessage(Long messageId, String content, MessageStatus status) {
        jdbcTemplate.update(
                """
                        UPDATE conversation_messages
                        SET content = ?, status = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                content,
                status.name(),
                LocalDateTime.now(),
                messageId,
                MessageStatus.IN_PROGRESS.name());
    }

    public void touch(Long conversationId) {
        jdbcTemplate.update(
                "UPDATE conversations SET last_activity_at = ? WHERE id = ?",
                LocalDateTime.now(),
                conversationId);
    }

    /** 逆序取最近 limitPlusOne 条；调用方负责判断是否截断并恢复正序。 */
    public List<ConversationMessage> findRecentMessages(Long conversationId, int limitPlusOne) {
        return jdbcTemplate.query("""
                        SELECT id, conversation_id, role, content, status, request_id, created_at
                        FROM conversation_messages
                        WHERE conversation_id = ?
                        ORDER BY id DESC
                        LIMIT ?
                        """,
                (resultSet, rowNum) -> new ConversationMessage(
                        resultSet.getLong("id"),
                        resultSet.getLong("conversation_id"),
                        MessageRole.valueOf(resultSet.getString("role")),
                        resultSet.getString("content"),
                        MessageStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("request_id"),
                        resultSet.getTimestamp("created_at").toLocalDateTime()),
                conversationId,
                limitPlusOne);
    }

    /** 客户端主动取消：按 requestId 幂等收敛，只有本人且仍在生成中的消息会被更新。 */
    public int cancelPendingByRequestId(Long userId, String requestId) {
        return jdbcTemplate.update("""
                        UPDATE conversation_messages m
                        JOIN conversations c ON c.id = m.conversation_id
                        SET m.status = ?, m.updated_at = ?
                        WHERE m.request_id = ? AND m.status = ? AND c.user_id = ?
                        """,
                MessageStatus.CANCELED.name(),
                LocalDateTime.now(),
                requestId,
                MessageStatus.IN_PROGRESS.name(),
                userId);
    }

    public void updateMemorySummary(Long conversationId, String summary) {
        jdbcTemplate.update(
                "UPDATE conversations SET memory_summary = ?, memory_updated_at = ? WHERE id = ?",
                summary, LocalDateTime.now(), conversationId);
    }

    public String findMemorySummary(Long conversationId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT memory_summary FROM conversations WHERE id = ?",
                (resultSet, rowNum) -> resultSet.getString(1),
                conversationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 服务端崩溃或取消信号丢失时，把超时未完成的消息收敛为失败。 */
    public int failStaleMessages(Long conversationId, LocalDateTime before) {
        return jdbcTemplate.update("""
                        UPDATE conversation_messages
                        SET status = ?, updated_at = ?
                        WHERE conversation_id = ? AND status = ? AND created_at < ?
                        """,
                MessageStatus.FAILED.name(),
                LocalDateTime.now(),
                conversationId,
                MessageStatus.IN_PROGRESS.name(),
                before);
    }

    private static Conversation mapConversation(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        long connectionId = resultSet.getLong("connection_id");
        Long nullableConnectionId = resultSet.wasNull() ? null : connectionId;
        return new Conversation(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                nullableConnectionId,
                resultSet.getString("connection_name_snapshot"),
                resultSet.getString("title"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getTimestamp("last_activity_at").toLocalDateTime());
    }
}
