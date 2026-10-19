package com.queryforge.agent;

import com.queryforge.agent.AgentRun;
import com.queryforge.agent.AgentStep;
import com.queryforge.agent.ApprovalStatus;
import com.queryforge.agent.RunStatus;
import com.queryforge.agent.StepStatus;
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

/**
 * Agent Run/Step 持久化。读取一律带 user_id 或 run_id 校验，避免仅凭 ID 越权。
 * 步骤状态流转使用条件更新，保证重复确认只有一次生效。
 */
@Repository
public class AgentRunRepository {

    private static final String RUN_COLUMNS = """
            id, user_id, conversation_id, connection_id, connection_name_snapshot, question,
            status, route, role_activities, knowledge_sources, llm_call_count,
            summary, error_code, error_message, created_at, updated_at
            """;

    private static final String STEP_COLUMNS = """
            id, run_id, sequence, objective, depends_on, status, sql_text, sql_version, agent_role,
            approval_status, repair_count, row_count, truncated, error_code, error_message,
            result_summary, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public AgentRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insertRun(Long userId, Long conversationId, Long connectionId, String connectionName,
                          String question, RunStatus status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_runs
                        (user_id, conversation_id, connection_id, connection_name_snapshot, question, status)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, conversationId);
            setNullableLong(statement, 3, connectionId);
            statement.setString(4, connectionName);
            statement.setString(5, question);
            statement.setString(6, status.name());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Agent Run 创建后未返回主键");
        }
        return key.longValue();
    }

    public Long insertStep(Long runId, int sequence, String objective, Integer dependsOn) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_steps (run_id, sequence, objective, depends_on, status, agent_role)
                    VALUES (?, ?, ?, ?, ?, 'SQL_AGENT')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, runId);
            statement.setInt(2, sequence);
            statement.setString(3, objective);
            if (dependsOn == null) {
                statement.setNull(4, Types.INTEGER);
            } else {
                statement.setInt(4, dependsOn);
            }
            statement.setString(5, StepStatus.PENDING.name());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Agent 步骤创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<AgentRun> findRunOwned(Long runId, Long userId) {
        List<AgentRun> rows = jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS + " FROM agent_runs WHERE id = ? AND user_id = ?",
                (resultSet, rowNum) -> mapRun(resultSet),
                runId,
                userId);
        return rows.stream().findFirst();
    }

    public Optional<AgentRun> findLatestRun(Long userId, Long conversationId) {
        List<AgentRun> rows = jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS + " FROM agent_runs"
                        + " WHERE user_id = ? AND conversation_id = ? ORDER BY id DESC LIMIT 1",
                (resultSet, rowNum) -> mapRun(resultSet),
                userId,
                conversationId);
        return rows.stream().findFirst();
    }

    public List<AgentRun> findRuns(Long userId, Long conversationId, int limit) {
        return jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS + " FROM agent_runs"
                        + " WHERE user_id = ? AND conversation_id = ? ORDER BY id DESC LIMIT ?",
                (resultSet, rowNum) -> mapRun(resultSet),
                userId,
                conversationId,
                limit);
    }

    public List<AgentStep> findSteps(Long runId) {
        return jdbcTemplate.query(
                "SELECT " + STEP_COLUMNS + " FROM agent_steps WHERE run_id = ? ORDER BY sequence",
                (resultSet, rowNum) -> mapStep(resultSet),
                runId);
    }

    public Optional<AgentStep> findStep(Long runId, Long stepId) {
        List<AgentStep> rows = jdbcTemplate.query(
                "SELECT " + STEP_COLUMNS + " FROM agent_steps WHERE id = ? AND run_id = ?",
                (resultSet, rowNum) -> mapStep(resultSet),
                stepId,
                runId);
        return rows.stream().findFirst();
    }

    /** 更新路由结果；角色活动与知识来源为服务端生成的安全 JSON 摘要。 */
    public int updateRunRoute(Long runId, String route, String roleActivities, String knowledgeSources) {
        return jdbcTemplate.update("""
                        UPDATE agent_runs
                        SET route = ?, role_activities = ?, knowledge_sources = ?, updated_at = ?
                        WHERE id = ?
                        """,
                route, roleActivities, knowledgeSources, LocalDateTime.now(), runId);
    }

    public int llmCallCount(Long runId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT llm_call_count FROM agent_runs WHERE id = ?",
                (resultSet, rowNum) -> resultSet.getInt(1),
                runId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /** 检索命中的知识来源（服务端生成的安全摘要 JSON）。 */
    public int updateRunKnowledgeSources(Long runId, String sourcesJson) {
        return jdbcTemplate.update(
                "UPDATE agent_runs SET knowledge_sources = ?, updated_at = ? WHERE id = ?",
                sourcesJson, LocalDateTime.now(), runId);
    }

    public int incrementLlmCalls(Long runId) {
        return jdbcTemplate.update(
                "UPDATE agent_runs SET llm_call_count = llm_call_count + 1, updated_at = ? WHERE id = ?",
                LocalDateTime.now(), runId);
    }

    public int updateRunStatus(Long runId, RunStatus status, String summary, String errorCode, String errorMessage) {
        return jdbcTemplate.update("""
                        UPDATE agent_runs
                        SET status = ?, summary = ?, error_code = ?, error_message = ?, updated_at = ?
                        WHERE id = ?
                        """,
                status.name(), summary, errorCode, errorMessage, LocalDateTime.now(), runId);
    }

    /** 写入新版本 SQL 并重新进入待确认；旧版本号不再可用。 */
    public int setStepSql(Long stepId, String sql, int sqlVersion, int repairCount) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps
                        SET sql_text = ?, sql_version = ?, repair_count = ?, status = ?,
                            approval_status = ?, updated_at = ?
                        WHERE id = ?
                        """,
                sql,
                sqlVersion,
                repairCount,
                StepStatus.WAITING_APPROVAL.name(),
                ApprovalStatus.PENDING.name(),
                LocalDateTime.now(),
                stepId);
    }

    /** 原子领取：只有仍处于待确认且版本一致的步骤会被置为 RUNNING。 */
    public int claimStep(Long runId, Long stepId, int sqlVersion) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps
                        SET status = ?, approval_status = ?, updated_at = ?
                        WHERE id = ? AND run_id = ? AND status = ? AND sql_version = ?
                        """,
                StepStatus.RUNNING.name(),
                ApprovalStatus.APPROVED.name(),
                LocalDateTime.now(),
                stepId,
                runId,
                StepStatus.WAITING_APPROVAL.name(),
                sqlVersion);
    }

    public int completeStep(Long stepId, int rowCount, boolean truncated, String resultSummary) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps
                        SET status = ?, row_count = ?, truncated = ?, result_summary = ?,
                            error_code = NULL, error_message = NULL, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                StepStatus.SUCCEEDED.name(),
                rowCount,
                truncated,
                resultSummary,
                LocalDateTime.now(),
                stepId,
                StepStatus.RUNNING.name());
    }

    public int failStep(Long stepId, String errorCode, String errorMessage) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps
                        SET status = ?, error_code = ?, error_message = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                StepStatus.FAILED.name(),
                errorCode,
                errorMessage,
                LocalDateTime.now(),
                stepId,
                StepStatus.RUNNING.name());
    }

    public int rejectStep(Long stepId, String reason) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps
                        SET status = ?, approval_status = ?, error_message = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                StepStatus.CANCELED.name(),
                ApprovalStatus.REJECTED.name(),
                reason,
                LocalDateTime.now(),
                stepId,
                StepStatus.WAITING_APPROVAL.name());
    }

    public int cancelPendingSteps(Long runId) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps
                        SET status = ?, approval_status = ?, updated_at = ?
                        WHERE run_id = ? AND status IN (?, ?)
                        """,
                StepStatus.CANCELED.name(),
                ApprovalStatus.REJECTED.name(),
                LocalDateTime.now(),
                runId,
                StepStatus.PENDING.name(),
                StepStatus.WAITING_APPROVAL.name());
    }

    /** 幂等取消：已经是终态的 Run 不会被改写。 */
    public int cancelActiveRun(Long runId, Long userId) {
        return jdbcTemplate.update("""
                        UPDATE agent_runs
                        SET status = ?, error_code = ?, updated_at = ?
                        WHERE id = ? AND user_id = ? AND status IN (?, ?, ?)
                        """,
                RunStatus.CANCELED.name(),
                "RUN_CANCELED",
                LocalDateTime.now(),
                runId,
                userId,
                RunStatus.PLANNING.name(),
                RunStatus.WAITING_APPROVAL.name(),
                RunStatus.RUNNING.name());
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static AgentRun mapRun(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        long connectionId = resultSet.getLong("connection_id");
        Long nullableConnectionId = resultSet.wasNull() ? null : connectionId;
        return new AgentRun(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getLong("conversation_id"),
                nullableConnectionId,
                resultSet.getString("connection_name_snapshot"),
                resultSet.getString("question"),
                RunStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("route"),
                resultSet.getString("role_activities"),
                resultSet.getString("knowledge_sources"),
                resultSet.getInt("llm_call_count"),
                resultSet.getString("summary"),
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getTimestamp("updated_at").toLocalDateTime());
    }

    private static AgentStep mapStep(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        int dependsOn = resultSet.getInt("depends_on");
        Integer nullableDependsOn = resultSet.wasNull() ? null : dependsOn;
        int rowCount = resultSet.getInt("row_count");
        Integer nullableRowCount = resultSet.wasNull() ? null : rowCount;
        boolean truncated = resultSet.getBoolean("truncated");
        Boolean nullableTruncated = resultSet.wasNull() ? null : truncated;
        return new AgentStep(
                resultSet.getLong("id"),
                resultSet.getLong("run_id"),
                resultSet.getInt("sequence"),
                resultSet.getString("objective"),
                nullableDependsOn,
                StepStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("sql_text"),
                resultSet.getInt("sql_version"),
                resultSet.getString("agent_role"),
                ApprovalStatus.valueOf(resultSet.getString("approval_status")),
                resultSet.getInt("repair_count"),
                nullableRowCount,
                nullableTruncated,
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getString("result_summary"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getTimestamp("updated_at").toLocalDateTime());
    }
}
