package com.queryforge.application.agent.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * P14-B：候选校验、评分与择优。只处理文本：调用 {@link ReadOnlySqlGuard} 过滤危险与
 * 无法解析的 SQL，解析评分模型返回的 JSON，按 {@code 0.4*规则 + 0.6*模型} 选优；
 * 不执行任何 SQL，不访问数据库。
 */
@Component
public class SqlCandidateScorer {

    /** 合法候选的弱长度偏好下限（不能使非法 SQL 被选中，只是同合法集合内的微弱偏好）。 */
    static final double MIN_RULE_SCORE = 0.3;
    static final double RULE_SCORE_DIVISOR = 2000.0;
    /** 合法候选仅 1 条时跳过评分模型，使用固定语义分。 */
    public static final double SINGLE_CANDIDATE_LLM_SCORE = 0.8;
    /** 评分 JSON 中缺失项的统一回退分数（不因缺失归零，也不窃取模型评分）。 */
    static final double MISSING_SCORE_FALLBACK = 0.5;
    static final double RULE_WEIGHT = 0.4;
    static final double LLM_WEIGHT = 0.6;
    /** 综合分并列判定精度（避免浮点误差破坏确定性）。 */
    static final double TIE_EPSILON = 1e-9;
    static final int MIN_MODEL_SCORE = 1;
    static final int MAX_MODEL_SCORE = 10;
    private static final int MAX_REJECTION_CHARS = 120;

    private final ReadOnlySqlGuard guard;
    private final ObjectMapper objectMapper;

    public SqlCandidateScorer(ReadOnlySqlGuard guard, ObjectMapper objectMapper) {
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    /** 评分应用结果：候选列表（llmScore 已填）与是否发生规则降级。 */
    public record ScoringResult(List<SqlCandidate> candidates, boolean ruleFallback) {

        public static ScoringResult ruleFallback(List<SqlCandidate> candidates) {
            return new ScoringResult(
                    candidates.stream().map(c -> c.withLlmScore(c.ruleScore())).toList(), true);
        }
    }

    /**
     * 候选过滤（任何评分之前）：对每条非空候选调用 {@link ReadOnlySqlGuard}。
     * 校验失败标记 {@code valid=false}、{@code ruleScore=0} 并记录简短拒绝原因；
     *  Guard 通过的候选保留其规范化 SQL 与规则分。
     */
    public List<SqlCandidate> validateCandidates(List<SqlCandidate> candidates, String schema) {
        List<SqlCandidate> validated = new ArrayList<>(candidates.size());
        for (SqlCandidate candidate : candidates) {
            if (candidate.sql() == null || candidate.sql().isBlank()) {
                validated.add(candidate); // 生成阶段已失败，保留拒绝原因
                continue;
            }
            try {
                String safe = guard.validate(candidate.sql(), schema);
                validated.add(candidate.withValidated(safe, ruleScore(safe)));
            } catch (RuntimeException ex) {
                validated.add(SqlCandidate.rejected(candidate.candidateIndex(), safeRejection(ex)));
            }
        }
        return validated;
    }

    /**
     * 合法候选的规则分数（对合法 SQL 的弱长度偏好）：
     * {@code min(1.0, max(0.3, 1.0 - sql.length / 2000.0))}。
     */
    static double ruleScore(String sql) {
        double length = sql == null ? 0 : sql.length();
        return Math.min(1.0, Math.max(MIN_RULE_SCORE, 1.0 - length / RULE_SCORE_DIVISOR));
    }

    /**
     * 应用评分模型结果。{@code scoreJson} 为 null/空、结构非法或没有任何可用条目时，
     * 统一按规则分数降级（{@code llmScore = ruleScore}，{@code ruleFallback=true}）；
     * 部分缺失的条目按统一回退分数处理，绝不把失败判成零分。
     *
     * @param validCandidates 仅包含合法候选，按 candidateIndex 升序（评分序号即其 1-based 位置）
     */
    public ScoringResult applyLlmScores(List<SqlCandidate> validCandidates, String scoreJson) {
        if (scoreJson == null || scoreJson.isBlank()) {
            return ScoringResult.ruleFallback(validCandidates);
        }
        Map<Integer, Double> scores;
        try {
            scores = parseScores(scoreJson, validCandidates.size());
        } catch (RuntimeException ex) {
            return ScoringResult.ruleFallback(validCandidates);
        }
        if (scores.isEmpty()) {
            return ScoringResult.ruleFallback(validCandidates);
        }
        List<SqlCandidate> scored = new ArrayList<>(validCandidates.size());
        for (int position = 0; position < validCandidates.size(); position++) {
            double llmScore = scores.getOrDefault(position + 1, MISSING_SCORE_FALLBACK);
            scored.add(validCandidates.get(position).withLlmScore(llmScore));
        }
        return new ScoringResult(scored, false);
    }

    /**
     * 在候选集合内取综合分数最大者；并列时优先候选索引更小者（确定性）。
     *
     * @param candidates     候选集合（可含被拒绝项，选优时跳过）
     * @param totalCandidates 生成的候选总数
     */
    public SqlSelection select(List<SqlCandidate> candidates, int totalCandidates, boolean ruleFallback) {
        SqlCandidate best = null;
        double bestComposite = 0.0;
        int validCount = 0;
        for (SqlCandidate candidate : candidates) {
            if (!candidate.valid()) {
                continue;
            }
            validCount++;
            double composite = composite(candidate.ruleScore(), candidate.llmScore());
            boolean better = best == null
                    || composite > bestComposite + TIE_EPSILON
                    || (Math.abs(composite - bestComposite) <= TIE_EPSILON
                            && candidate.candidateIndex() < best.candidateIndex());
            if (better) {
                best = candidate;
                bestComposite = composite;
            }
        }
        if (best == null) {
            throw new ApiException(
                    ErrorCode.AGENT_STEP_FAILED, HttpStatus.UNPROCESSABLE_ENTITY,
                    "没有生成通过只读安全校验的 SQL 候选");
        }
        return new SqlSelection(
                SqlSelection.MODE_MULTI,
                best.sql(),
                best.candidateIndex(),
                totalCandidates,
                validCount,
                round3(best.ruleScore()),
                round3(best.llmScore()),
                round3(bestComposite),
                ruleFallback);
    }

    /** 综合分数公式（指南 §5.2）。 */
    static double composite(double ruleScore, double llmScore) {
        return RULE_WEIGHT * ruleScore + LLM_WEIGHT * llmScore;
    }

    /**
     * 解析评分 JSON 数组。拒绝重复索引、越界索引与非数值项；
     * 键为合法候选集合内的 1-based 序号，值为 [1,10] 换算后的 [0.1,1.0]。
     */
    private Map<Integer, Double> parseScores(String scoreJson, int validCount) {
        JsonNode root;
        try {
            root = objectMapper.readTree(scoreJson);
        } catch (Exception ex) {
            return Map.of();
        }
        if (root == null || !root.isArray()) {
            return Map.of();
        }
        Map<Integer, Double> scores = new HashMap<>();
        for (JsonNode element : root) {
            JsonNode candidateNode = element.path("candidate");
            JsonNode scoreNode = element.path("score");
            if (!candidateNode.isNumber() || !scoreNode.isNumber() || !candidateNode.isInt()) {
                continue; // 非整数序号或非数值评分拒绝
            }
            int position = candidateNode.intValue();
            double score = scoreNode.doubleValue();
            if (position < 1 || position > validCount) {
                continue; // 越界拒绝
            }
            if (score < MIN_MODEL_SCORE || score > MAX_MODEL_SCORE) {
                continue; // 超出 [1,10] 拒绝
            }
            if (scores.containsKey(position)) {
                continue; // 重复索引拒绝（保留首个）
            }
            scores.put(position, score / (double) MAX_MODEL_SCORE);
        }
        return scores;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String safeRejection(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "未通过只读安全校验";
        }
        return message.length() > MAX_REJECTION_CHARS
                ? message.substring(0, MAX_REJECTION_CHARS)
                : message;
    }
}
