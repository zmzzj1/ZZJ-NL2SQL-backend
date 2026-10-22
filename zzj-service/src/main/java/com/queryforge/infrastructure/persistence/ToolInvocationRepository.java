package com.queryforge.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import com.queryforge.domain.tool.ToolInvocation;

/**
 * 工具调用审计的持久化。只记录归属、状态与耗时，不落工具输出或敏感结果行。
 */
@Repository
public class ToolInvocationRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolInvocationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insert(ToolInvocation invocation, String status, Long durationMs, String errorCode) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tool_invocations
                      (user_id, tool_name, conversation_id, agent_run_id, agent_step_id,
                       workflow_run_id, workflow_node_id, status, duration_ms, error_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, invocation.userId());
            statement.setString(2, invocation.toolName());
            setNullableLong(statement, 3, invocation.conversationId());
            setNullableLong(statement, 4, invocation.agentRunId());
            setNullableLong(statement, 5, invocation.agentStepId());
            setNullableLong(statement, 6, invocation.workflowRunId());
            setNullableLong(statement, 7, invocation.workflowNodeId());
            statement.setString(8, status);
            setNullableLong(statement, 9, durationMs);
            statement.setString(10, errorCode);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
