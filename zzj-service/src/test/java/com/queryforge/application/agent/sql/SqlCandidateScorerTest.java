package com.queryforge.application.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P14-B：候选校验、评分解析与确定性选优（只处理文本，不执行 SQL）。
 * 使用真实 {@link ReadOnlySqlGuard}（JSQLParser）验证候选过滤的安全边界。
 */
class SqlCandidateScorerTest {

    private static final String SCHEMA = "sql_demo_a";
    private static final String SHORT_SQL = "SELECT id FROM shop_orders";
    private static final String LONG_SQL =
            "SELECT id, amount, status, created_at, updated_at, channel, region, remark "
                    + "FROM shop_orders WHERE status = 'paid' AND channel = 'app' "
                    + "AND region = 'east' AND amount > 100 ORDER BY created_at DESC LIMIT 200";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SqlCandidateScorer scorer = new SqlCandidateScorer(new ReadOnlySqlGuard(), objectMapper);

    // ------------------------------------------------------------------ 规则分

    @Test
    @DisplayName("规则分：合法 SQL 的弱长度偏好，下限 0.3、上限 1.0")
    void ruleScore_shouldBeBoundedLengthPreference() {
        assertThat(SqlCandidateScorer.ruleScore("")).isEqualTo(1.0);
        assertThat(SqlCandidateScorer.ruleScore("SELECT 1")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(SqlCandidateScorer.ruleScore("x".repeat(2000))).isEqualTo(0.3);
        assertThat(SqlCandidateScorer.ruleScore("x".repeat(5000))).isEqualTo(0.3);
        assertThat(SqlCandidateScorer.ruleScore("x".repeat(1000))).isEqualTo(0.5);
    }

    @Test
    @DisplayName("综合分：0.4 规则 + 0.6 模型")
    void composite_shouldUseFourSixWeights() {
        assertThat(SqlCandidateScorer.composite(1.0, 1.0)).isEqualTo(1.0);
        assertThat(SqlCandidateScorer.composite(1.0, 0.0)).isEqualTo(0.4);
        assertThat(SqlCandidateScorer.composite(0.5, 0.5)).isEqualTo(0.5);
    }

    // -------------------------------------------------------------- 候选过滤

    @Test
    @DisplayName("候选过滤：多语句、写操作、跨库、危险函数被 Guard 拦截，合法候选保留规范化文本")
    void validateCandidates_shouldRejectDangerousAndInvalidSql() {
        List<SqlCandidate> raw = List.of(
                SqlCandidate.generated(1, SHORT_SQL + ";"),
                SqlCandidate.generated(2, "SELECT id FROM shop_orders; DROP TABLE shop_orders"),
                SqlCandidate.generated(3, "DELETE FROM shop_orders"),
                SqlCandidate.generated(4, "SELECT id FROM other_db.shop_orders"),
                SqlCandidate.generated(5, "SELECT SLEEP(5) FROM shop_orders"),
                SqlCandidate.generated(6, "SELECT FROM WHERE"));

        List<SqlCandidate> validated = scorer.validateCandidates(raw, SCHEMA);

        assertThat(validated).hasSize(6);
        assertThat(validated.get(0).valid()).isTrue();
        assertThat(validated.get(0).sql()).isEqualTo(SHORT_SQL); // 尾部分号被规范化去掉
        assertThat(validated.get(0).ruleScore()).isCloseTo(0.987, org.assertj.core.data.Offset.offset(0.001));
        for (int index = 1; index < 6; index++) {
            assertThat(validated.get(index).valid()).isFalse();
            assertThat(validated.get(index).ruleScore()).isEqualTo(0.0);
            assertThat(validated.get(index).rejectionReason()).isNotBlank();
        }
        // 拒绝原因不得把原始非法 SQL 回传给前端。
        assertThat(validated.get(1).rejectionReason()).doesNotContain("DROP TABLE");
        assertThat(validated.get(3).rejectionReason()).doesNotContain("other_db");
    }

    @Test
    @DisplayName("候选过滤：生成阶段失败的候选保持拒绝原因，不再送 Guard")
    void validateCandidates_shouldKeepGenerationFailures() {
        List<SqlCandidate> validated = scorer.validateCandidates(
                List.of(SqlCandidate.rejected(1, "模型未返回 SQL"), SqlCandidate.generated(2, SHORT_SQL)),
                SCHEMA);

        assertThat(validated.get(0).valid()).isFalse();
        assertThat(validated.get(0).rejectionReason()).isEqualTo("模型未返回 SQL");
        assertThat(validated.get(1).valid()).isTrue();
    }

    // ------------------------------------------------------------ 评分解析

    private List<SqlCandidate> twoValidCandidates() {
        return List.of(
                new SqlCandidate(1, SHORT_SQL, true, "", 1.0, 0.0),
                new SqlCandidate(2, LONG_SQL, true, "", 0.96, 0.0));
    }

    @Test
    @DisplayName("评分解析：合法 JSON 按序号映射，缺失项按统一回退分数处理")
    void applyLlmScores_shouldMapScoresAndFallBackMissingEntries() {
        String json = "[{\"candidate\":1,\"score\":6,\"reason\":\"直接\"}]";

        SqlCandidateScorer.ScoringResult result = scorer.applyLlmScores(twoValidCandidates(), json);

        assertThat(result.ruleFallback()).isFalse();
        assertThat(result.candidates().get(0).llmScore()).isEqualTo(0.6);
        assertThat(result.candidates().get(1).llmScore()).isEqualTo(SqlCandidateScorer.MISSING_SCORE_FALLBACK);
    }

    @Test
    @DisplayName("评分解析：拒绝重复索引、越界与非数值，保留首个有效条目")
    void applyLlmScores_shouldRejectDuplicateOutOfRangeAndNonNumeric() {
        String json = """
                [
                  {"candidate":1,"score":8},
                  {"candidate":1,"score":2},
                  {"candidate":9,"score":10},
                  {"candidate":2,"score":"high"}
                ]
                """;

        SqlCandidateScorer.ScoringResult result = scorer.applyLlmScores(twoValidCandidates(), json);

        assertThat(result.ruleFallback()).isFalse();
        assertThat(result.candidates().get(0).llmScore()).isEqualTo(0.8);
        assertThat(result.candidates().get(1).llmScore()).isEqualTo(SqlCandidateScorer.MISSING_SCORE_FALLBACK);
    }

    @Test
    @DisplayName("评分解析：超出 [1,10] 的分数被拒绝")
    void applyLlmScores_shouldRejectOutOfRangeScore() {
        String json = "[{\"candidate\":1,\"score\":42},{\"candidate\":2,\"score\":7}]";

        SqlCandidateScorer.ScoringResult result = scorer.applyLlmScores(twoValidCandidates(), json);

        assertThat(result.ruleFallback()).isFalse();
        assertThat(result.candidates().get(0).llmScore()).isEqualTo(SqlCandidateScorer.MISSING_SCORE_FALLBACK);
        assertThat(result.candidates().get(1).llmScore()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("评分降级：空响应、非法 JSON、空数组统一回退为规则分数")
    void applyLlmScores_shouldFallBackToRuleScores() {
        List<SqlCandidate> valid = twoValidCandidates();
        for (String broken : new String[] {null, "", "   ", "not-json", "{\"candidate\":1}", "[]"}) {
            SqlCandidateScorer.ScoringResult result = scorer.applyLlmScores(valid, broken);
            assertThat(result.ruleFallback()).isTrue();
            assertThat(result.candidates().get(0).llmScore()).isEqualTo(valid.get(0).ruleScore());
            assertThat(result.candidates().get(1).llmScore()).isEqualTo(valid.get(1).ruleScore());
        }
    }

    // ------------------------------------------------------------------ 选优

    @Test
    @DisplayName("选优：按 0.4/0.6 综合分取最大，非法候选不参与")
    void select_shouldPickHighestCompositeAmongValid() {
        List<SqlCandidate> valid = List.of(
                new SqlCandidate(1, SHORT_SQL, true, "", 1.0, 0.5),
                new SqlCandidate(2, LONG_SQL, true, "", 0.96, 0.9),
                SqlCandidate.rejected(3, "未通过只读安全校验"));

        SqlSelection selection = scorer.select(valid, 3, false);

        assertThat(selection.selectedCandidateIndex()).isEqualTo(2);
        assertThat(selection.selectedSql()).isEqualTo(LONG_SQL);
        assertThat(selection.totalCandidates()).isEqualTo(3);
        assertThat(selection.validCandidates()).isEqualTo(2);
        assertThat(selection.compositeScore()).isEqualTo(0.924); // 0.4*0.96 + 0.6*0.9
        assertThat(selection.ruleFallback()).isFalse();
        assertThat(selection.multiCandidate()).isTrue();
    }

    @Test
    @DisplayName("选优：分数并列时优先候选索引更小者（确定性）")
    void select_shouldPreferSmallerIndexOnTie() {
        List<SqlCandidate> valid = List.of(
                new SqlCandidate(2, LONG_SQL, true, "", 1.0, 0.5),
                new SqlCandidate(1, SHORT_SQL, true, "", 1.0, 0.5));

        SqlSelection selection = scorer.select(valid, 2, false);

        assertThat(selection.selectedCandidateIndex()).isEqualTo(1);
        assertThat(selection.selectedSql()).isEqualTo(SHORT_SQL);
    }

    @Test
    @DisplayName("选优：没有任何合法候选时明确失败，不兜底危险 SQL")
    void select_shouldFailWhenNoValidCandidate() {
        Throwable thrown = catchThrowable(() -> scorer.select(
                List.of(SqlCandidate.rejected(1, "a"), SqlCandidate.rejected(2, "b")), 2, false));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(ErrorCode.AGENT_STEP_FAILED);
    }
}
