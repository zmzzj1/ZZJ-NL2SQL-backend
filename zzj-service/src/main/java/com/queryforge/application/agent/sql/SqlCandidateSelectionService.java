package com.queryforge.application.agent.sql;

import com.queryforge.application.agent.AgentLlmCallLimiter;
import com.queryforge.application.agent.AgentSqlAssistant;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * P14-B：为当前 {@code runId/stepId} 编排单路径或多候选 SQL 生成与选优，统一返回
 * 唯一被选中 SQL 及有界统计。候选只存在于本次请求内存，不逐条入库。
 *
 * <p>路由分支（指南 §3）：
 * <ul>
 *   <li>{@code SIMPLE}：复用 {@link AgentSqlAssistant#generateSql} 单次生成，不评分；</li>
 *   <li>{@code COMPLEX}/{@code MEDIUM}：最多 3 个并行候选 → Guard 过滤 → 一次评分 LLM 选优；</li>
 *   <li>其他路由（CHITCHAT/CLARIFY/KNOWLEDGE/显式工具）不得进入本服务。</li>
 * </ul>
 * 评分模型不可用（预算不足/失败/超时/JSON 非法）时按纯规则分数选优；
 * 没有任何合法候选时明确失败，不兜底执行危险 SQL。
 */
@Service
public class SqlCandidateSelectionService {

    private static final Logger log = LoggerFactory.getLogger(SqlCandidateSelectionService.class);

    private final AgentSqlAssistant sqlAssistant;
    private final AgentLlmCallLimiter llmLimiter;
    private final SqlCandidateGenerator generator;
    private final SqlCandidateScorer scorer;

    public SqlCandidateSelectionService(
            AgentSqlAssistant sqlAssistant,
            AgentLlmCallLimiter llmLimiter,
            SqlCandidateGenerator generator,
            SqlCandidateScorer scorer) {
        this.sqlAssistant = sqlAssistant;
        this.llmLimiter = llmLimiter;
        this.generator = generator;
        this.scorer = scorer;
    }

    /** 是否走多候选链路：COMPLEX 与分类降级 MEDIUM 一并纳入。 */
    public static boolean isMultiCandidateRoute(String route) {
        return "COMPLEX".equals(route) || "MEDIUM".equals(route);
    }

    /**
     * 生成并选优。返回的 {@link SqlSelection#selectedSql()} 必须由调用方再做一次
     * {@code ReadOnlySqlGuard.validate} 后才可入库等待审批。
     */
    public SqlSelection generateAndSelect(SqlSelectionRequest request) {
        if (isMultiCandidateRoute(request.route())) {
            return selectMulti(request);
        }
        return selectSingle(request);
    }

    /** SIMPLE：一次生成模型调用，不额外打分（不因“专业 SQL Agent”强制多次模型调用）。 */
    private SqlSelection selectSingle(SqlSelectionRequest request) {
        String sql = llmLimiter.count(request.runId(), () -> sqlAssistant.generateSql(
                request.stepObjective(),
                request.schema(),
                request.previousSummaries(),
                request.knowledge(),
                request.systemPrompt(),
                request.skillGuidance(),
                request.authorizedContext()));
        // 单路径不评分：分值为 0，不发送候选评估事件。
        return new SqlSelection(
                SqlSelection.MODE_SINGLE, sql, 1, 1, 1, 0.0, 0.0, 0.0, false);
    }

    /** COMPLEX/MEDIUM：最多 3 个并行候选 → Guard → 一次评分 LLM → 确定性选优。 */
    private SqlSelection selectMulti(SqlSelectionRequest request) {
        List<SqlCandidate> generated = generator.generate(request);
        List<SqlCandidate> validated = scorer.validateCandidates(generated, request.schema());
        List<SqlCandidate> valid = validated.stream().filter(SqlCandidate::valid).toList();
        if (valid.isEmpty()) {
            log.info("sql_candidates_all_rejected runId={} stepId={}", request.runId(), request.stepId());
            throw new ApiException(
                    ErrorCode.AGENT_STEP_FAILED, HttpStatus.UNPROCESSABLE_ENTITY,
                    "没有生成通过只读安全校验的 SQL 候选");
        }

        boolean ruleFallback;
        if (valid.size() == 1) {
            // 有效候选仅一条：无需再调用评审模型。
            ruleFallback = false;
            valid = List.of(valid.get(0).withLlmScore(SqlCandidateScorer.SINGLE_CANDIDATE_LLM_SCORE));
        } else {
            String scoreJson = scoreWithModel(request, valid);
            SqlCandidateScorer.ScoringResult result = scorer.applyLlmScores(valid, scoreJson);
            valid = result.candidates();
            ruleFallback = result.ruleFallback();
        }
        return scorer.select(valid, generated.size(), ruleFallback);
    }

    /**
     * 评分模型只调用一次，且只看到通过安全过滤的合法候选。
     * 预算不足、失败或超时都返回 null，由评分器降级为纯规则选优；
     * 评分失败不得引发自动 SQL 执行。
     */
    private String scoreWithModel(SqlSelectionRequest request, List<SqlCandidate> valid) {
        List<String> candidateSqls = valid.stream().map(SqlCandidate::sql).toList();
        try {
            return generator.runBounded(() -> llmLimiter.tryCount(request.runId(),
                    () -> sqlAssistant.scoreCandidates(
                            request.stepObjective(), request.schema(), candidateSqls)));
        } catch (RuntimeException ex) {
            log.debug("sql_candidate_scoring_failed runId={} reason={}",
                    request.runId(), ex.getClass().getSimpleName());
            return null;
        }
    }
}
