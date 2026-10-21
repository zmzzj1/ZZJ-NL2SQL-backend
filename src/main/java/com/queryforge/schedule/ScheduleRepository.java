package com.queryforge.schedule;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 定时计划的持久化：计划 CRUD、触发记录的幂等插入、原子领取与租约。
 * 幂等核心：唯一约束 (schedule_id, fire_at_utc, workflow_revision, attempt) + INSERT ON DUPLICATE KEY UPDATE；
 * 领取核心：条件 UPDATE state='SCHEDULED' -> 'CLAIMED'，返回 affected==1 才视为抢到。
 * 时间字段统一 UTC；读回时以 UTC 解释数据库 DATETIME(3)。
 */
@Repository
public class ScheduleRepository {

    private static final String SCHEDULE_COLUMNS = """
            id, owner_user_id, workspace_id, workflow_id, workflow_revision, connection_id,
            conversation_id, user_input, agent_id, agent_version_id, cron_expression, cron_normalized,
            timezone, status, misfire_policy, overlap_policy, next_fire_at, last_fire_at, revision,
            created_at, updated_at
            """;
    private static final String OCCURRENCE_COLUMNS = """
            id, schedule_id, fire_at_utc, fire_at_local, workflow_revision, state, attempt, trigger_source,
            workflow_run_id, error_code, error_message, lease_owner, lease_expires_at, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public ScheduleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertSchedule(ScheduleRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO workflow_schedules
                        (owner_user_id, workspace_id, workflow_id, workflow_revision, connection_id,
                         conversation_id, user_input, agent_id, agent_version_id, cron_expression,
                         cron_normalized, timezone, status, misfire_policy, overlap_policy, next_fire_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            statement.setLong(i++, row.ownerUserId());
            setNullableLong(statement, i++, row.workspaceId());
            statement.setLong(i++, row.workflowId());
            statement.setInt(i++, row.workflowRevision());
            statement.setLong(i++, row.connectionId());
            setNullableLong(statement, i++, row.conversationId());
            statement.setString(i++, row.userInput());
            setNullableLong(statement, i++, row.agentId());
            setNullableLong(statement, i++, row.agentVersionId());
            statement.setString(i++, row.cronExpression());
            statement.setString(i++, row.cronNormalized());
            statement.setString(i++, row.timezone());
            statement.setString(i++, row.status());
            statement.setString(i++, row.misfirePolicy());
            statement.setString(i++, row.overlapPolicy());
            setNullableTimestamp(statement, i++, row.nextFireAt());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("计划创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<ScheduleRow> findOwned(long id, long userId) {
        return jdbcTemplate.query(
                "SELECT " + SCHEDULE_COLUMNS + " FROM workflow_schedules WHERE id = ? AND owner_user_id = ?",
                (resultSet, rowNum) -> mapSchedule(resultSet), id, userId).stream().findFirst();
    }

    public Optional<ScheduleRow> findOwnedByWorkflow(long workflowId, long userId) {
        return jdbcTemplate.query(
                "SELECT " + SCHEDULE_COLUMNS + " FROM workflow_schedules WHERE workflow_id = ? AND owner_user_id = ?",
                (resultSet, rowNum) -> mapSchedule(resultSet), workflowId, userId).stream().findFirst();
    }

    public List<ScheduleRow> listOwned(long userId) {
        return jdbcTemplate.query(
                "SELECT " + SCHEDULE_COLUMNS + " FROM workflow_schedules WHERE owner_user_id = ? ORDER BY updated_at DESC",
                (resultSet, rowNum) -> mapSchedule(resultSet), userId);
    }

    public int updateSchedule(long id, long userId, int expectedRevision, String cronExpression,
                              String cronNormalized, String timezone, String status, String misfirePolicy,
                              String overlapPolicy, String userInput, Instant nextFireAt) {
        return jdbcTemplate.update("""
                        UPDATE workflow_schedules
                        SET cron_expression = ?, cron_normalized = ?, timezone = ?, status = ?,
                            misfire_policy = ?, overlap_policy = ?, user_input = ?,
                            next_fire_at = ?, revision = revision + 1
                        WHERE id = ? AND owner_user_id = ? AND revision = ?
                        """,
                cronExpression, cronNormalized, timezone, status, misfirePolicy, overlapPolicy, userInput,
                toLocal(nextFireAt), id, userId, expectedRevision);
    }

    /** 仅更新 next_fire_at（调度推进，不递增 revision）。 */
    public void updateNextFireAt(long id, Instant nextFireAt) {
        jdbcTemplate.update(
                "UPDATE workflow_schedules SET next_fire_at = ? WHERE id = ?",
                toLocal(nextFireAt), id);
    }

    public void markLastFireAt(long id, Instant lastFireAt) {
        jdbcTemplate.update(
                "UPDATE workflow_schedules SET last_fire_at = ? WHERE id = ?",
                toLocal(lastFireAt), id);
    }

    public int deleteOwned(long id, long userId) {
        return jdbcTemplate.update("DELETE FROM workflow_schedules WHERE id = ? AND owner_user_id = ?", id, userId);
    }

    /** 幂等插入触发记录：撞唯一键视为已存在，返回 affected==1 才表示真正新建。 */
    public boolean insertOccurrenceIfAbsent(long scheduleId, Instant fireAtUtc, String fireAtLocal,
                                            int workflowRevision, String source) {
        int affected = jdbcTemplate.update("""
                        INSERT INTO workflow_schedule_occurrences
                            (schedule_id, fire_at_utc, fire_at_local, workflow_revision, state, attempt, trigger_source)
                        VALUES (?, ?, ?, ?, 'SCHEDULED', 1, ?)
                        ON DUPLICATE KEY UPDATE updated_at = updated_at
                        """,
                scheduleId, toLocal(fireAtUtc), fireAtLocal, workflowRevision, source);
        return affected == 1;
    }

    /** 幂等创建一条终态（SKIPPED）occurrence：misfire 场景直接落终态，不进入 SCHEDULED 领取流程。 */
    public void insertSkippedOccurrence(long scheduleId, Instant fireAtUtc, String fireAtLocal,
                                        int workflowRevision, String errorCode, String errorMessage) {
        jdbcTemplate.update("""
                        INSERT INTO workflow_schedule_occurrences
                            (schedule_id, fire_at_utc, fire_at_local, workflow_revision, state, attempt,
                             trigger_source, error_code, error_message)
                        VALUES (?, ?, ?, ?, 'SKIPPED', 1, 'SCHEDULER', ?, ?)
                        ON DUPLICATE KEY UPDATE updated_at = updated_at
                        """,
                scheduleId, toLocal(fireAtUtc), fireAtLocal, workflowRevision, errorCode, errorMessage);
    }

    /** 轮询：返回到点且仍 SCHEDULED 的 occurrence id 列表（短事务，领取由 claimOccurrence 保证原子）。 */
    public List<Long> findDueOccurrences(Instant nowUtc, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM workflow_schedule_occurrences WHERE state = 'SCHEDULED' AND fire_at_utc <= ? ORDER BY fire_at_utc LIMIT ?",
                Long.class, toLocal(nowUtc), limit);
    }

    /** 原子领取：只有仍处于 SCHEDULED 的 occurrence 会被置为 CLAIMED 并写入租约。 */
    public boolean claimOccurrence(long occurrenceId, String leaseOwner, Instant leaseExpiresAt) {
        int affected = jdbcTemplate.update("""
                        UPDATE workflow_schedule_occurrences
                        SET state = 'CLAIMED', lease_owner = ?, lease_expires_at = ?
                        WHERE id = ? AND state = 'SCHEDULED'
                        """,
                leaseOwner, toLocal(leaseExpiresAt), occurrenceId);
        return affected == 1;
    }

    public void updateOccurrence(long occurrenceId, String state, Long workflowRunId,
                                 String errorCode, String errorMessage) {
        jdbcTemplate.update("""
                        UPDATE workflow_schedule_occurrences
                        SET state = ?, workflow_run_id = ?, error_code = ?, error_message = ?, lease_owner = NULL, lease_expires_at = NULL
                        WHERE id = ?
                        """,
                state, workflowRunId, errorCode, errorMessage, occurrenceId);
    }

    public List<OccurrenceRow> findOccurrences(long scheduleId, int limit) {
        return jdbcTemplate.query(
                "SELECT " + OCCURRENCE_COLUMNS + " FROM workflow_schedule_occurrences WHERE schedule_id = ? ORDER BY fire_at_utc DESC LIMIT ?",
                (resultSet, rowNum) -> mapOccurrence(resultSet), scheduleId, limit);
    }

    public Optional<OccurrenceRow> findOccurrenceOwned(long occurrenceId, long userId) {
        return jdbcTemplate.query(
                "SELECT o.id, o.schedule_id, o.fire_at_utc, o.fire_at_local, o.workflow_revision, o.state, "
                        + "o.attempt, o.trigger_source, o.workflow_run_id, o.error_code, o.error_message, "
                        + "o.lease_owner, o.lease_expires_at, o.created_at, o.updated_at "
                        + "FROM workflow_schedule_occurrences o JOIN workflow_schedules s ON o.schedule_id = s.id "
                        + "WHERE o.id = ? AND s.owner_user_id = ?",
                (resultSet, rowNum) -> mapOccurrence(resultSet), occurrenceId, userId).stream().findFirst();
    }

    public Optional<OccurrenceRow> findOccurrenceById(long occurrenceId) {
        return jdbcTemplate.query(
                "SELECT " + OCCURRENCE_COLUMNS + " FROM workflow_schedule_occurrences WHERE id = ?",
                (resultSet, rowNum) -> mapOccurrence(resultSet), occurrenceId).stream().findFirst();
    }

    public Optional<ScheduleRow> findScheduleById(long scheduleId) {
        return jdbcTemplate.query(
                "SELECT " + SCHEDULE_COLUMNS + " FROM workflow_schedules WHERE id = ?",
                (resultSet, rowNum) -> mapSchedule(resultSet), scheduleId).stream().findFirst();
    }

    /** 供调度器读取：返回启用且到点的计划（用于推进 next_fire_at 并生成 occurrence）。 */
    public List<ScheduleRow> findDueSchedules(Instant nowUtc, int limit) {
        return jdbcTemplate.query(
                "SELECT " + SCHEDULE_COLUMNS + " FROM workflow_schedules WHERE status = 'ACTIVE' AND next_fire_at IS NOT NULL AND next_fire_at <= ? ORDER BY next_fire_at LIMIT ?",
                (resultSet, rowNum) -> mapSchedule(resultSet), toLocal(nowUtc), limit);
    }

    /** 查找仍在运行的 occurrence（用于并发策略判断与状态同步）。 */
    public List<OccurrenceRow> findActiveOccurrences(long scheduleId) {
        return jdbcTemplate.query(
                "SELECT " + OCCURRENCE_COLUMNS + " FROM workflow_schedule_occurrences WHERE schedule_id = ? AND state IN ('CLAIMED','WAITING_APPROVAL','RUNNING') ORDER BY fire_at_utc",
                (resultSet, rowNum) -> mapOccurrence(resultSet), scheduleId);
    }

    /** 查找所有非终态 occurrence（用于重启后的惰性状态同步）。 */
    public List<OccurrenceRow> findNonTerminalOccurrences() {
        return jdbcTemplate.query(
                "SELECT " + OCCURRENCE_COLUMNS + " FROM workflow_schedule_occurrences WHERE state IN ('CLAIMED','WAITING_APPROVAL','RUNNING') ORDER BY id",
                (resultSet, rowNum) -> mapOccurrence(resultSet));
    }

    /** 回收过期租约：把租约过期且仍非终态的 occurrence 置为 FAILED（保守：不盲目重放未确认动作）。 */
    public int expireLeases(Instant nowUtc) {
        return jdbcTemplate.update("""
                        UPDATE workflow_schedule_occurrences
                        SET state = 'FAILED', error_code = 'TRIGGER_LEASE_EXPIRED', error_message = '租约过期，未确认执行结果',
                            lease_owner = NULL, lease_expires_at = NULL
                        WHERE state IN ('CLAIMED','RUNNING') AND lease_expires_at IS NOT NULL AND lease_expires_at < ?
                        """,
                toLocal(nowUtc));
    }

    private static ScheduleRow mapSchedule(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ScheduleRow(
                rs.getLong("id"), rs.getLong("owner_user_id"), nullableLong(rs, "workspace_id"),
                rs.getLong("workflow_id"), rs.getInt("workflow_revision"), rs.getLong("connection_id"),
                nullableLong(rs, "conversation_id"), rs.getString("user_input"), nullableLong(rs, "agent_id"),
                nullableLong(rs, "agent_version_id"), rs.getString("cron_expression"),
                rs.getString("cron_normalized"), rs.getString("timezone"), rs.getString("status"),
                rs.getString("misfire_policy"), rs.getString("overlap_policy"),
                nullableInstant(rs, "next_fire_at"), nullableInstant(rs, "last_fire_at"),
                rs.getInt("revision"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private static OccurrenceRow mapOccurrence(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OccurrenceRow(
                rs.getLong("id"), rs.getLong("schedule_id"), nullableInstant(rs, "fire_at_utc"),
                rs.getString("fire_at_local"), rs.getInt("workflow_revision"), rs.getString("state"),
                rs.getInt("attempt"), rs.getString("trigger_source"), nullableLong(rs, "workflow_run_id"),
                rs.getString("error_code"), rs.getString("error_message"), rs.getString("lease_owner"),
                nullableInstant(rs, "lease_expires_at"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setObject(index, LocalDateTime.ofInstant(value, ZoneOffset.UTC));
        }
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        LocalDateTime local = rs.getObject(column, LocalDateTime.class);
        return local == null ? null : local.toInstant(ZoneOffset.UTC);
    }

    public record ScheduleRow(
            long id, long ownerUserId, Long workspaceId, long workflowId, int workflowRevision,
            long connectionId, Long conversationId, String userInput, Long agentId, Long agentVersionId,
            String cronExpression, String cronNormalized, String timezone, String status,
            String misfirePolicy, String overlapPolicy, Instant nextFireAt, Instant lastFireAt,
            int revision, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
    }

    public record OccurrenceRow(
            long id, long scheduleId, Instant fireAtUtc, String fireAtLocal, int workflowRevision,
            String state, int attempt, String triggerSource, Long workflowRunId,
            String errorCode, String errorMessage, String leaseOwner, Instant leaseExpiresAt,
            LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
    }
}
