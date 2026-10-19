package com.queryforge.workflow;

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
 * 工作流定义、运行与节点状态持久化。读取带 user_id，越权返回空。
 */
@Repository
public class WorkflowRepository {

    private static final String WORKFLOW_COLUMNS = """
            id, user_id, name, description, definition, revision, status, created_at, updated_at
            """;
    private static final String RUN_COLUMNS = """
            id, user_id, workflow_id, workflow_revision, conversation_id, connection_id, status,
            error_code, error_message, created_at, updated_at
            """;
    private static final String NODE_COLUMNS = """
            id, workflow_run_id, node_id, node_type, sequence, status, agent_run_id, agent_step_id,
            analysis_run_id, report_id, output_summary, error_code, error_message, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public WorkflowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insertWorkflow(Long userId, String name, String description, String definition) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO workflows (user_id, name, description, definition, revision, status)
                    VALUES (?, ?, ?, ?, 1, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, name);
            statement.setString(3, description);
            statement.setString(4, definition);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("工作流创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<Workflow> findWorkflowOwned(Long id, Long userId) {
        List<Workflow> rows = jdbcTemplate.query(
                "SELECT " + WORKFLOW_COLUMNS + " FROM workflows WHERE id = ? AND user_id = ?",
                (resultSet, rowNum) -> mapWorkflow(resultSet),
                id,
                userId);
        return rows.stream().findFirst();
    }

    public List<Workflow> findWorkflows(Long userId) {
        return jdbcTemplate.query(
                "SELECT " + WORKFLOW_COLUMNS + " FROM workflows WHERE user_id = ? AND status = 'ACTIVE' ORDER BY updated_at DESC",
                (resultSet, rowNum) -> mapWorkflow(resultSet),
                userId);
    }

    public int updateWorkflow(Long id, Long userId, String name, String description, String definition, int expectedRevision) {
        return jdbcTemplate.update("""
                        UPDATE workflows
                        SET name = ?, description = ?, definition = ?, revision = revision + 1, updated_at = ?
                        WHERE id = ? AND user_id = ? AND revision = ?
                        """,
                name, description, definition, LocalDateTime.now(), id, userId, expectedRevision);
    }

    public int deleteWorkflow(Long id, Long userId) {
        return jdbcTemplate.update("DELETE FROM workflows WHERE id = ? AND user_id = ?", id, userId);
    }

    public Long insertRun(Long userId, Long workflowId, int revision, Long conversationId, Long connectionId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO workflow_runs
                        (user_id, workflow_id, workflow_revision, conversation_id, connection_id, status)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, workflowId);
            statement.setInt(3, revision);
            setNullableLong(statement, 4, conversationId);
            setNullableLong(statement, 5, connectionId);
            statement.setString(6, WorkflowStatus.Run.PENDING.name());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("工作流运行创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<WorkflowRun> findRunOwned(Long id, Long userId) {
        List<WorkflowRun> rows = jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS + " FROM workflow_runs WHERE id = ? AND user_id = ?",
                (resultSet, rowNum) -> mapRun(resultSet),
                id,
                userId);
        return rows.stream().findFirst();
    }

    public void updateRunStatus(Long id, WorkflowStatus.Run status, String errorCode, String errorMessage) {
        jdbcTemplate.update("""
                        UPDATE workflow_runs SET status = ?, error_code = ?, error_message = ?, updated_at = ?
                        WHERE id = ?
                        """,
                status.name(), errorCode, errorMessage, LocalDateTime.now(), id);
    }

    public int cancelActiveRun(Long id, Long userId) {
        return jdbcTemplate.update("""
                        UPDATE workflow_runs SET status = ?, updated_at = ?
                        WHERE id = ? AND user_id = ? AND status IN (?, ?, ?)
                        """,
                WorkflowStatus.Run.CANCELED.name(), LocalDateTime.now(), id, userId,
                WorkflowStatus.Run.PENDING.name(), WorkflowStatus.Run.RUNNING.name(),
                WorkflowStatus.Run.WAITING_APPROVAL.name());
    }

    public void insertNode(Long workflowRunId, String nodeId, String nodeType, int sequence) {
        jdbcTemplate.update("""
                        INSERT INTO workflow_nodes (workflow_run_id, node_id, node_type, sequence, status)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                workflowRunId, nodeId, nodeType, sequence, WorkflowStatus.Node.PENDING.name());
    }

    public List<WorkflowNode> findNodes(Long workflowRunId) {
        return jdbcTemplate.query(
                "SELECT " + NODE_COLUMNS + " FROM workflow_nodes WHERE workflow_run_id = ? ORDER BY sequence",
                (resultSet, rowNum) -> mapNode(resultSet),
                workflowRunId);
    }

    public void updateNodeAnalysis(Long workflowRunId, String nodeId, Long analysisRunId) {
        jdbcTemplate.update(
                "UPDATE workflow_nodes SET analysis_run_id = ?, updated_at = ? WHERE workflow_run_id = ? AND node_id = ?",
                analysisRunId, LocalDateTime.now(), workflowRunId, nodeId);
    }

    public void updateNodeReport(Long workflowRunId, String nodeId, Long reportId) {
        jdbcTemplate.update(
                "UPDATE workflow_nodes SET report_id = ?, updated_at = ? WHERE workflow_run_id = ? AND node_id = ?",
                reportId, LocalDateTime.now(), workflowRunId, nodeId);
    }

    public void updateNodeStatus(Long workflowRunId, String nodeId, WorkflowStatus.Node status,
                                 String outputSummary, String errorCode, String errorMessage) {
        jdbcTemplate.update("""
                        UPDATE workflow_nodes
                        SET status = ?, output_summary = ?, error_code = ?, error_message = ?, updated_at = ?
                        WHERE workflow_run_id = ? AND node_id = ?
                        """,
                status.name(), outputSummary, errorCode, errorMessage, LocalDateTime.now(),
                workflowRunId, nodeId);
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Workflow mapWorkflow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new Workflow(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getString("definition"),
                resultSet.getInt("revision"),
                resultSet.getString("status"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getTimestamp("updated_at").toLocalDateTime());
    }

    private static WorkflowRun mapRun(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new WorkflowRun(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getLong("workflow_id"),
                resultSet.getInt("workflow_revision"),
                nullable(resultSet, "conversation_id"),
                nullable(resultSet, "connection_id"),
                WorkflowStatus.Run.valueOf(resultSet.getString("status")),
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getTimestamp("updated_at").toLocalDateTime());
    }

    private static WorkflowNode mapNode(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new WorkflowNode(
                resultSet.getLong("id"),
                resultSet.getLong("workflow_run_id"),
                resultSet.getString("node_id"),
                resultSet.getString("node_type"),
                resultSet.getInt("sequence"),
                WorkflowStatus.Node.valueOf(resultSet.getString("status")),
                nullable(resultSet, "agent_run_id"),
                nullable(resultSet, "agent_step_id"),
                nullable(resultSet, "analysis_run_id"),
                nullable(resultSet, "report_id"),
                resultSet.getString("output_summary"),
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getTimestamp("updated_at").toLocalDateTime());
    }

    private static Long nullable(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    public record Workflow(
            Long id, Long userId, String name, String description, String definition,
            int revision, String status, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
    }

    public record WorkflowRun(
            Long id, Long userId, Long workflowId, int workflowRevision,
            Long conversationId, Long connectionId, WorkflowStatus.Run status,
            String errorCode, String errorMessage, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
    }

    public record WorkflowNode(
            Long id, Long workflowRunId, String nodeId, String nodeType, int sequence,
            WorkflowStatus.Node status, Long agentRunId, Long agentStepId,
            Long analysisRunId, Long reportId, String outputSummary,
            String errorCode, String errorMessage, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
    }
}
