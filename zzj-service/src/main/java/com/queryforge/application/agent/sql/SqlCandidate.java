package com.queryforge.application.agent.sql;

/**
 * P14-B：一个 SQL 候选。候选只存在于本次请求内存，不逐条入库、不拆成多个待审批步骤。
 *
 * @param candidateIndex   候选序号（1..N，与模型任务一一对应）
 * @param sql              候选 SQL 文本（Guard 通过后为规范化文本）
 * @param valid            是否通过 {@code ReadOnlySqlGuard}；生成阶段失败的候选同样为 false
 * @param rejectionReason  失败/拒绝的简短原因（脱敏，不含原始非法 SQL 全文）
 * @param ruleScore        规则分数（仅合法候选非 0）
 * @param llmScore         评分模型分数（未评分或降级时为 0 / 等于规则分）
 */
public record SqlCandidate(
        int candidateIndex,
        String sql,
        boolean valid,
        String rejectionReason,
        double ruleScore,
        double llmScore
) {

    public static SqlCandidate generated(int candidateIndex, String sql) {
        return new SqlCandidate(candidateIndex, sql == null ? "" : sql, false, "", 0.0, 0.0);
    }

    public static SqlCandidate rejected(int candidateIndex, String reason) {
        return new SqlCandidate(candidateIndex, "", false, reason == null ? "" : reason, 0.0, 0.0);
    }

    /** Guard 通过：写入规范化 SQL 与规则分数（valid=true）。 */
    public SqlCandidate withValidated(String safeSql, double ruleScore) {
        return new SqlCandidate(candidateIndex, safeSql, true, "", ruleScore, 0.0);
    }

    public SqlCandidate withLlmScore(double score) {
        return new SqlCandidate(candidateIndex, sql, valid, rejectionReason, ruleScore, score);
    }
}
