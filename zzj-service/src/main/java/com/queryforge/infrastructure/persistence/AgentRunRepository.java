package com.queryforge.infrastructure.persistence;

import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.ApprovalStatus;
import com.queryforge.domain.agent.PlanReviewStatus;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
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
            summary, error_code, error_message, created_at, updated_at,
            agent_id, agent_version_id, resolved_skill_versions, tool_policy_hash,
            plan_auto_confirm, plan_review_version, plan_review_status, plan_review_required,
            plan_review_reason, plan_rejection_count, plan_review_feedback
            """;

    private static final String STEP_COLUMNS = """
            id, run_id, sequence, objective, depends_on, status, sql_text, sql_version, agent_role,
            approval_status, repair_count, row_count, truncated, error_code, error_message,
            result_summary, created_at, updated_at, analysis_run_id, report_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public AgentRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insertRun(Long userId, Long conversationId, Long connectionId, String connectionName,
                          String question, RunStatus status) {
        return insertRun(userId, conversationId, connectionId, connectionName, question, status,
                null, null, null, null);
    }

    public Long insertRun(Long userId, Long conversationId, Long connectionId, String connectionName,
                          String question, RunStatus status, Long agentId, Long agentVersionId,
                          String resolvedSkillVersions, String toolPolicyHash) {
        return insertRun(userId, conversationId, connectionId, connectionName, question, status,
                agentId, agentVersionId, resolvedSkillVersions, toolPolicyHash, Boolean.TRUE);
    }

    /**
     * P14-D：创建 Run 时固定计划级审核模式。{@code planAutoConfirm} 为 null 时按默认 true 处理
     * （兼容未传该字段的旧调用，避免旧 Run 意外卡在计划审核）。
     */
    public Long insertRun(Long userId, Long conversationId, Long connectionId, String connectionName,
                          String question, RunStatus status, Long agentId, Long agentVersionId,
                          String resolvedSkillVersions, String toolPolicyHash, Boolean planAutoConfirm) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        boolean auto = planAutoConfirm != null && planAutoConfirm;
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_runs
                        (user_id, conversation_id, connection_id, connection_name_snapshot, question, status,
                         agent_id, agent_version_id, resolved_skill_versions, tool_policy_hash, plan_auto_confirm)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, conversationId);
            setNullableLong(statement, 3, connectionId);
            statement.setString(4, connectionName);
            statement.setString(5, question);
            statement.setString(6, status.name());
            setNullableLong(statement, 7, agentId);
            setNullableLong(statement, 8, agentVersionId);
            statement.setString(9, resolvedSkillVersions);
            statement.setString(10, toolPolicyHash);
            statement.setBoolean(11, auto);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Agent Run 创建后未返回主键");
        }
        return key.longValue();
    }

    public Long insertStep(Long runId, int sequence, String objective, Integer dependsOn) {
        return insertStep(runId, sequence, objective, dependsOn, "SQL_AGENT");
    }

    /**
     * 插入步骤。P14-A：显式工具快捷路径使用 {@code TOOL_ASSISTANT} 角色，
     * 仍复用 {@code agent_steps}（sql_text 为 null、sql_version 为 0），不伪造 SQL。
     */
    public Long insertStep(Long runId, int sequence, String objective, Integer dependsOn, String agentRole) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_steps (run_id, sequence, objective, depends_on, status, agent_role)
                    VALUES (?, ?, ?, ?, ?, ?)
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
            statement.setString(6, agentRole == null || agentRole.isBlank() ? "SQL_AGENT" : agentRole);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Agent 步骤创建后未返回主键");
        }
        return key.longValue();
    }

    /**
     * P14-A：非 SQL 步骤（显式工具）原子进入执行态。
     * 只有仍处于 PENDING 的步骤会被置为 RUNNING，重复触发不会二次执行。
     */
    public int startNonSqlStep(Long stepId) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps SET status = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                StepStatus.RUNNING.name(),
                LocalDateTime.now(),
                stepId,
                StepStatus.PENDING.name());
    }

    /** P14-A：非 SQL 步骤成功落库（仅写结果摘要，不动 sql/审批字段）。 */
    public int completeNonSqlStep(Long stepId, String resultSummary) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps
                        SET status = ?, result_summary = ?, error_code = NULL, error_message = NULL,
                            updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                StepStatus.SUCCEEDED.name(),
                resultSummary,
                LocalDateTime.now(),
                stepId,
                StepStatus.RUNNING.name());
    }

    /** P14-A：非 SQL 步骤失败落库（不触碰 SQL 审批状态）。 */
    public int failNonSqlStep(Long stepId, String errorCode, String errorMessage) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps
                        SET status = ?, error_code = ?, error_message = ?, updated_at = ?
                        WHERE id = ? AND status IN (?, ?)
                        """,
                StepStatus.FAILED.name(),
                errorCode,
                errorMessage,
                LocalDateTime.now(),
                stepId,
                StepStatus.PENDING.name(),
                StepStatus.RUNNING.name());
    }

    /**
     * P14-C：非 SQL 步骤（Code/Report）原子进入执行态。
     * 匹配 runId + stepId + PENDING，重复触发（SSE 重连/前端双击）不会二次执行。
     */
    public int claimNonSqlStep(Long runId, Long stepId) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps SET status = ?, updated_at = ?
                        WHERE id = ? AND run_id = ? AND status = ?
                        """,
                StepStatus.RUNNING.name(),
                LocalDateTime.now(),
                stepId,
                runId,
                StepStatus.PENDING.name());
    }

    /** P14-C：Code 步骤成功落库：挂真实 analysisRunId 与安全摘要（仅 RUNNING → SUCCEEDED）。 */
    public int completeCodeStep(Long runId, Long stepId, Long analysisRunId, String resultSummary) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps
                        SET status = ?, result_summary = ?, analysis_run_id = ?,
                            error_code = NULL, error_message = NULL, updated_at = ?
                        WHERE id = ? AND run_id = ? AND status = ?
                        """,
                StepStatus.SUCCEEDED.name(),
                resultSummary,
                analysisRunId,
                LocalDateTime.now(),
                stepId,
                runId,
                StepStatus.RUNNING.name());
    }

    /** P14-C：Report 步骤成功落库：挂真实 reportId 与安全摘要（仅 RUNNING → SUCCEEDED）。 */
    public int completeReportStep(Long runId, Long stepId, Long reportId, String resultSummary) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps
                        SET status = ?, result_summary = ?, report_id = ?,
                            error_code = NULL, error_message = NULL, updated_at = ?
                        WHERE id = ? AND run_id = ? AND status = ?
                        """,
                StepStatus.SUCCEEDED.name(),
                resultSummary,
                reportId,
                LocalDateTime.now(),
                stepId,
                runId,
                StepStatus.RUNNING.name());
    }

    /**
     * P14-C：非 SQL 步骤失败落库（匹配 runId + stepId + 预期状态）。
     * 已终结 Run 的迟到回调因状态不匹配而写不进去，不会把成功改写成失败。
     */
    public int failNonSqlStep(Long runId, Long stepId, String errorCode, String errorMessage) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps
                        SET status = ?, error_code = ?, error_message = ?, updated_at = ?
                        WHERE id = ? AND run_id = ? AND status IN (?, ?)
                        """,
                StepStatus.FAILED.name(),
                errorCode,
                errorMessage,
                LocalDateTime.now(),
                stepId,
                runId,
                StepStatus.PENDING.name(),
                StepStatus.RUNNING.name());
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

    /** 供 ToolRegistry 校验：按 runId 取运行归属与工具策略快照（不限制 user，调用方自行校验）。 */
    public Optional<AgentRun> findRun(Long runId) {
        List<AgentRun> rows = jdbcTemplate.query(
                "SELECT " + RUN_COLUMNS + " FROM agent_runs WHERE id = ?",
                (resultSet, rowNum) -> mapRun(resultSet),
                runId);
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

    /**
     * P14-B：原子占用一次模型调用预算（条件更新，多候选并行不超额）。
     * 这是平台自身元数据的计数更新，不是模型生成 SQL，也不走外部连接只读查询。
     *
     * @return 1 表示占用成功；0 表示已达上限或运行不存在
     */
    public int tryIncrementLlmCalls(Long runId, int maxCalls) {
        return jdbcTemplate.update(
                "UPDATE agent_runs SET llm_call_count = llm_call_count + 1, updated_at = ? "
                        + "WHERE id = ? AND llm_call_count < ?",
                LocalDateTime.now(), runId, maxCalls);
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

    /** P11：把 RoleAssigner 选定的专业角色持久化到步骤，使详情/刷新与前端流式角色一致。 */
    public int updateStepRole(Long stepId, String role) {
        return jdbcTemplate.update("""
                        UPDATE agent_steps SET agent_role = ?, updated_at = ? WHERE id = ?
                        """,
                role,
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

    // ---------------------------------------------------------------- P14-D：计划级 HITL 审核持久化

    /** 仅迁移 Run 持久状态（不触碰其他字段，避免覆盖 summary/error）。 */
    public int transitionRunStatus(Long runId, RunStatus status) {
        return jdbcTemplate.update(
                "UPDATE agent_runs SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), LocalDateTime.now(), runId);
    }

    /**
     * 进入计划审核等待：Run=WAITING_PLAN_REVIEW，审核状态=AWAITING，版本+1。
     * 仅在 PLANNING 阶段成功（rowsAffected==1），并发/非预期状态不会误入。
     * 成功返回新的审核版本（≥1），失败返回 -1。
     */
    public int enterPlanReviewAwaiting(Long runId, Long userId, String reason) {
        int updated = jdbcTemplate.update("""
                        UPDATE agent_runs
                        SET status = ?, plan_review_status = ?, plan_review_required = 1,
                            plan_review_reason = ?, plan_review_version = plan_review_version + 1,
                            updated_at = ?
                        WHERE id = ? AND user_id = ? AND status = 'PLANNING'
                        """,
                RunStatus.WAITING_PLAN_REVIEW.name(), PlanReviewStatus.AWAITING.name(),
                truncateReason(reason), LocalDateTime.now(), runId, userId);
        if (updated != 1) {
            return -1;
        }
        return currentPlanReviewVersion(runId);
    }

    /** 记录 Gate 判定（SKIPPED/NOT_REQUIRED/EVAL_UNAVAILABLE），不进入等待，不阻塞执行。 */
    public int markPlanReviewStatus(Long runId, Long userId, PlanReviewStatus status) {
        return jdbcTemplate.update("""
                        UPDATE agent_runs
                        SET plan_review_status = ?, plan_review_required = 0, updated_at = ?
                        WHERE id = ? AND user_id = ?
                        """,
                status.name(), LocalDateTime.now(), runId, userId);
    }

    /**
     * 一次性批准：把当前 AWAITING 版本标记为 APPROVED 并自增版本（作废旧会话）。
     * 仅当 Run=WAITING_PLAN_REVIEW、状态=AWAITING 且版本匹配时成功（rowsAffected==1）。
     */
    public int claimPlanApproval(Long runId, Long userId, int version) {
        return jdbcTemplate.update("""
                        UPDATE agent_runs
                        SET plan_review_status = ?, plan_review_required = 0,
                            plan_review_version = plan_review_version + 1, updated_at = ?
                        WHERE id = ? AND user_id = ? AND status = 'WAITING_PLAN_REVIEW'
                          AND plan_review_status = 'AWAITING' AND plan_review_version = ?
                        """,
                PlanReviewStatus.APPROVED.name(), LocalDateTime.now(), runId, userId, version);
    }

    /**
     * 一次性拒绝：拒绝计数+1，置为 REPLAN_REQUIRED 等待重规划（版本在重规划重新进入 AWAITING 时自增）。
     * 同样要求 Run=WAITING_PLAN_REVIEW、AWAITING、版本匹配（rowsAffected==1）。
     */
    public int claimPlanRejection(Long runId, Long userId, int version, String feedback, String reason) {
        return jdbcTemplate.update("""
                        UPDATE agent_runs
                        SET plan_rejection_count = plan_rejection_count + 1,
                            plan_review_status = ?, plan_review_required = 1,
                            plan_review_feedback = ?, plan_review_reason = ?, updated_at = ?
                        WHERE id = ? AND user_id = ? AND status = 'WAITING_PLAN_REVIEW'
                          AND plan_review_status = 'AWAITING' AND plan_review_version = ?
                        """,
                PlanReviewStatus.REPLAN_REQUIRED.name(), truncateFeedback(feedback),
                truncateReason(reason), LocalDateTime.now(), runId, userId, version);
    }

    /** 第 3 次拒绝后终结 Run：FAILED + REJECTED，清理等待标记。 */
    public int failPlanReview(Long runId, String errorCode, String errorMessage) {
        return jdbcTemplate.update("""
                        UPDATE agent_runs
                        SET status = ?, plan_review_status = ?, plan_review_required = 0,
                            error_code = ?, error_message = ?, updated_at = ?
                        WHERE id = ?
                        """,
                RunStatus.FAILED.name(), PlanReviewStatus.REJECTED.name(), errorCode, errorMessage,
                LocalDateTime.now(), runId);
    }

    /** 重规划前原子作废全部尚未执行的 PENDING 步骤（不触碰已执行/已确认步骤）。 */
    public int deletePendingSteps(Long runId) {
        return jdbcTemplate.update(
                "DELETE FROM agent_steps WHERE run_id = ? AND status = ?",
                runId, StepStatus.PENDING.name());
    }

    public int currentPlanReviewVersion(Long runId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT plan_review_version FROM agent_runs WHERE id = ?",
                (resultSet, rowNum) -> resultSet.getInt(1), runId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    public int currentRejectionCount(Long runId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT plan_rejection_count FROM agent_runs WHERE id = ?",
                (resultSet, rowNum) -> resultSet.getInt(1), runId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private static String truncateReason(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > 300 ? reason.substring(0, 300) : reason;
    }

    private static String truncateFeedback(String feedback) {
        if (feedback == null) {
            return null;
        }
        return feedback.length() > 4000 ? feedback.substring(0, 4000) : feedback;
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
        long agentId = resultSet.getLong("agent_id");
        Long nullableAgentId = resultSet.wasNull() ? null : agentId;
        long agentVersionId = resultSet.getLong("agent_version_id");
        Long nullableAgentVersionId = resultSet.wasNull() ? null : agentVersionId;
        boolean planAutoConfirm = resultSet.getBoolean("plan_auto_confirm");
        int planReviewVersion = resultSet.getInt("plan_review_version");
        String planReviewStatus = resultSet.getString("plan_review_status");
        boolean planReviewRequired = resultSet.getBoolean("plan_review_required");
        String planReviewReason = resultSet.getString("plan_review_reason");
        int planRejectionCount = resultSet.getInt("plan_rejection_count");
        String planReviewFeedback = resultSet.getString("plan_review_feedback");
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
                resultSet.getTimestamp("updated_at").toLocalDateTime(),
                nullableAgentId,
                nullableAgentVersionId,
                resultSet.getString("resolved_skill_versions"),
                resultSet.getString("tool_policy_hash"),
                planAutoConfirm,
                planReviewVersion,
                planReviewStatus,
                planReviewRequired,
                planReviewReason,
                planRejectionCount,
                planReviewFeedback);
    }

    private static AgentStep mapStep(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        int dependsOn = resultSet.getInt("depends_on");
        Integer nullableDependsOn = resultSet.wasNull() ? null : dependsOn;
        int rowCount = resultSet.getInt("row_count");
        Integer nullableRowCount = resultSet.wasNull() ? null : rowCount;
        boolean truncated = resultSet.getBoolean("truncated");
        Boolean nullableTruncated = resultSet.wasNull() ? null : truncated;
        long analysisRunId = resultSet.getLong("analysis_run_id");
        Long nullableAnalysisRunId = resultSet.wasNull() ? null : analysisRunId;
        long reportId = resultSet.getLong("report_id");
        Long nullableReportId = resultSet.wasNull() ? null : reportId;
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
                resultSet.getTimestamp("updated_at").toLocalDateTime(),
                nullableAnalysisRunId,
                nullableReportId);
    }

    /** 写入/更新统一状态图检查点（P11）。state_revision 用作乐观并发保护。 */
    public int upsertGraphCheckpoint(long runId, String graphStateJson, long stateRevision) {
        return jdbcTemplate.update("""
                        INSERT INTO agent_graph_checkpoints (run_id, graph_state_json, state_revision, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            graph_state_json = VALUES(graph_state_json),
                            state_revision = VALUES(state_revision),
                            updated_at = VALUES(updated_at)
                        """,
                runId, graphStateJson, stateRevision, LocalDateTime.now(), LocalDateTime.now());
    }

    /** 读取最近一次确认的状态图 JSON；无则返回空（恢复失败安全，P1130）。 */
    public Optional<String> findGraphCheckpointJson(long runId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT graph_state_json FROM agent_graph_checkpoints WHERE run_id = ?",
                (resultSet, rowNum) -> resultSet.getString(1),
                runId);
        return rows.stream().findFirst();
    }
}
