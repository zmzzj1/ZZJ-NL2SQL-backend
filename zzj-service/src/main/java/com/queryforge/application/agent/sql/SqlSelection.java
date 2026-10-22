package com.queryforge.application.agent.sql;

/**
 * P14-B：选优结果。一次选优只产生一个 SQL 版本；候选原文不在此对象中扩散
 * （{@link #selectedSql()} 是 Guard 通过后的唯一文本，由调用方再校验后入库）。
 *
 * @param mode                   {@code SINGLE}（简单查询单路径，不评分）或 {@code MULTI}
 * @param selectedSql            被选中的 SQL
 * @param selectedCandidateIndex 被选中的候选序号（单路径为 1）
 * @param totalCandidates        生成的候选总数
 * @param validCandidates        通过安全校验的候选数
 * @param ruleScore              选中候选的规则分数（单路径不评分，为 0）
 * @param llmScore               选中候选的模型评分（单路径为 0；降级时等于规则分）
 * @param compositeScore         综合分数 {@code 0.4*rule + 0.6*llm}（单路径为 0）
 * @param ruleFallback           评分模型不可用时是否按纯规则选优
 */
public record SqlSelection(
        String mode,
        String selectedSql,
        int selectedCandidateIndex,
        int totalCandidates,
        int validCandidates,
        double ruleScore,
        double llmScore,
        double compositeScore,
        boolean ruleFallback
) {

    public static final String MODE_SINGLE = "SINGLE";
    public static final String MODE_MULTI = "MULTI";

    public boolean multiCandidate() {
        return MODE_MULTI.equals(mode);
    }
}
