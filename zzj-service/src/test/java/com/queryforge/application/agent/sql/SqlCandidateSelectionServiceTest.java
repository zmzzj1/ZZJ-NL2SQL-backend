package com.queryforge.application.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.application.agent.AgentLlmCallLimiter;
import com.queryforge.application.agent.AgentSqlAssistant;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

/**
 * P14-B：单路径 / 多候选编排、评分降级、预算竞态与运行间隔离。
 * 评分与 Guard 使用真实组件；模型调用为 Mock。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqlCandidateSelectionServiceTest {

    private static final long RUN_ID = 5L;
    private static final long STEP_ID = 11L;
    private static final String SCHEMA = "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL)";
    private static final String DATABASE = "sql_demo_a";
    private static final String SHORT_SQL = "SELECT id FROM shop_orders";
    private static final String LONG_SQL =
            "SELECT id, amount, status FROM shop_orders WHERE status = 'paid' AND amount > 100";
    private static final String DANGEROUS_SQL = "SELECT SLEEP(5) FROM shop_orders";

    @Mock
    private AgentSqlAssistant sqlAssistant;
    @Mock
    private AgentLlmCallLimiter llmLimiter;
    @Mock
    private SqlCandidateGenerator generator;
    @Mock
    private AgentRunRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SqlCandidateScorer scorer =
            new SqlCandidateScorer(new ReadOnlySqlGuard(), objectMapper);

    private SqlSelectionRequest request(String route) {
        return new SqlSelectionRequest(
                RUN_ID, STEP_ID, route, "统计订单", "按月统计订单",
                SCHEMA, DATABASE, "", "", "", "", "", "MySQL 8");
    }

    @BeforeEach
    void stubBoundedExecution() {
        // 有界执行器原样执行任务（真实超时/上下文隔离由 Generator 单测覆盖）。
        when(generator.runBounded(any())).thenAnswer(invocation -> {
            try {
                return ((java.util.concurrent.Callable<Object>) invocation.getArgument(0)).call();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        // 评分预算抢占成功时执行评分调用。
        when(llmLimiter.tryCount(eq(RUN_ID), org.mockito.ArgumentMatchers.<java.util.function.Supplier<String>>any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<String>) invocation.getArgument(1)).get());
    }

    private SqlCandidateSelectionService service() {
        return new SqlCandidateSelectionService(sqlAssistant, llmLimiter, generator, scorer);
    }

    // ------------------------------------------------------------------ 单路径

    @Test
    @DisplayName("SIMPLE：只调用一次生成模型，不生成候选、不评分")
    void simpleRoute_shouldGenerateOnceWithoutScoring() {
        when(llmLimiter.count(eq(RUN_ID), org.mockito.ArgumentMatchers.<java.util.function.Supplier<String>>any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<String>) invocation.getArgument(1)).get());
        when(sqlAssistant.generateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SHORT_SQL);

        SqlSelection selection = service().generateAndSelect(request("SIMPLE"));

        assertThat(selection.mode()).isEqualTo(SqlSelection.MODE_SINGLE);
        assertThat(selection.selectedSql()).isEqualTo(SHORT_SQL);
        assertThat(selection.selectedCandidateIndex()).isEqualTo(1);
        assertThat(selection.multiCandidate()).isFalse();
        verify(sqlAssistant).generateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(generator, never()).generate(any());
        verify(sqlAssistant, never()).scoreCandidates(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("SIMPLE：生成模型调用经由预算器计数（超限即失败，不静默放行）")
    void simpleRoute_shouldCountGenerationCall() {
        when(llmLimiter.count(eq(RUN_ID), org.mockito.ArgumentMatchers.<java.util.function.Supplier<String>>any()))
                .thenThrow(new ApiException(
                        ErrorCode.AGENT_STATE_CONFLICT, HttpStatus.TOO_MANY_REQUESTS, "本次运行的模型调用次数已达上限"));

        Throwable thrown = catchThrowable(() -> service().generateAndSelect(request("SIMPLE")));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(ErrorCode.AGENT_STATE_CONFLICT);
    }

    // ------------------------------------------------------------------ 多候选

    @Test
    @DisplayName("COMPLEX：三候选 → Guard 过滤 → 评分只看到合法候选 → 0.4/0.6 选优")
    void complexRoute_shouldSelectBestValidCandidate() {
        when(generator.generate(any())).thenReturn(List.of(
                SqlCandidate.generated(1, SHORT_SQL),
                SqlCandidate.generated(2, LONG_SQL),
                SqlCandidate.generated(3, DANGEROUS_SQL)));
        when(sqlAssistant.scoreCandidates(eq("按月统计订单"), eq(SCHEMA), any()))
                .thenReturn("[{\"candidate\":1,\"score\":5},{\"candidate\":2,\"score\":9}]");

        SqlSelection selection = service().generateAndSelect(request("COMPLEX"));

        assertThat(selection.mode()).isEqualTo(SqlSelection.MODE_MULTI);
        assertThat(selection.totalCandidates()).isEqualTo(3);
        assertThat(selection.validCandidates()).isEqualTo(2);
        assertThat(selection.selectedSql()).isEqualTo(LONG_SQL);
        assertThat(selection.selectedCandidateIndex()).isEqualTo(2);
        assertThat(selection.ruleFallback()).isFalse();
        // 评审只看到通过安全过滤的候选（危险候选不出现在评分请求里）。
        verify(sqlAssistant).scoreCandidates(eq("按月统计订单"), eq(SCHEMA), eq(List.of(SHORT_SQL, LONG_SQL)));
    }

    @Test
    @DisplayName("MEDIUM：分类降级同样走多候选链路")
    void mediumRoute_shouldUseMultiCandidatePath() {
        when(generator.generate(any())).thenReturn(List.of(
                SqlCandidate.generated(1, SHORT_SQL),
                SqlCandidate.generated(2, LONG_SQL)));
        when(sqlAssistant.scoreCandidates(anyString(), anyString(), any()))
                .thenReturn("[{\"candidate\":1,\"score\":7},{\"candidate\":2,\"score\":8}]");

        SqlSelection selection = service().generateAndSelect(request("MEDIUM"));

        assertThat(selection.multiCandidate()).isTrue();
        assertThat(selection.selectedCandidateIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("仅一条合法候选：不再调用评审模型，使用固定语义分")
    void singleValidCandidate_shouldSkipScoringModel() {
        when(generator.generate(any())).thenReturn(List.of(
                SqlCandidate.generated(1, SHORT_SQL),
                SqlCandidate.generated(2, DANGEROUS_SQL)));

        SqlSelection selection = service().generateAndSelect(request("COMPLEX"));

        assertThat(selection.validCandidates()).isEqualTo(1);
        assertThat(selection.selectedSql()).isEqualTo(SHORT_SQL);
        assertThat(selection.llmScore()).isEqualTo(0.8);
        assertThat(selection.compositeScore())
                .isCloseTo(0.4 * selection.ruleScore() + 0.6 * 0.8, org.assertj.core.data.Offset.offset(0.001));
        verify(sqlAssistant, never()).scoreCandidates(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("评分模型失败/JSON 非法：按纯规则分数选优（ruleFallback）")
    void scoringFailure_shouldFallBackToRuleScores() {
        when(generator.generate(any())).thenReturn(List.of(
                SqlCandidate.generated(1, LONG_SQL),
                SqlCandidate.generated(2, SHORT_SQL)));
        when(sqlAssistant.scoreCandidates(anyString(), anyString(), any()))
                .thenThrow(new ApiException(
                        ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "模型服务暂不可用"));

        SqlSelection selection = service().generateAndSelect(request("COMPLEX"));

        assertThat(selection.ruleFallback()).isTrue();
        // 纯规则下更短的 SQL 占优（候选 2）。
        assertThat(selection.selectedCandidateIndex()).isEqualTo(2);
        assertThat(selection.llmScore()).isEqualTo(selection.ruleScore());
    }

    @Test
    @DisplayName("评分预算不足：合法候选按规则退化，不伪造完成三候选")
    void scoringBudgetExhausted_shouldDegradeToRules() {
        when(generator.generate(any())).thenReturn(List.of(
                SqlCandidate.generated(1, LONG_SQL),
                SqlCandidate.generated(2, SHORT_SQL)));
        // 预算抢占失败：返回 null（doReturn 覆盖 @BeforeEach 桩，避免二次桩触发旧答案）。
        org.mockito.Mockito.doReturn(null)
                .when(llmLimiter).tryCount(eq(RUN_ID), org.mockito.ArgumentMatchers.<java.util.function.Supplier<String>>any());

        SqlSelection selection = service().generateAndSelect(request("COMPLEX"));

        assertThat(selection.ruleFallback()).isTrue();
        assertThat(selection.selectedCandidateIndex()).isEqualTo(2);
        verify(sqlAssistant, never()).scoreCandidates(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("全部候选被 Guard 拒绝：明确失败，不进入等待审批")
    void allCandidatesInvalid_shouldFailWithoutApproval() {
        when(generator.generate(any())).thenReturn(List.of(
                SqlCandidate.generated(1, DANGEROUS_SQL),
                SqlCandidate.generated(2, "DELETE FROM shop_orders"),
                SqlCandidate.rejected(3, "候选生成超时")));

        Throwable thrown = catchThrowable(() -> service().generateAndSelect(request("COMPLEX")));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(ErrorCode.AGENT_STEP_FAILED);
        verify(sqlAssistant, never()).scoreCandidates(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("非 SQL 路由不得进入候选服务（按单路径防御处理）")
    void nonSqlRoutes_shouldNotEnterCandidateService() {
        when(llmLimiter.count(eq(RUN_ID), org.mockito.ArgumentMatchers.<java.util.function.Supplier<String>>any()))
                .thenReturn(SHORT_SQL);

        for (String route : new String[] {"CHITCHAT", "CLARIFY", "KNOWLEDGE", "TOOL_INVOCATION", ""}) {
            SqlSelection selection = service().generateAndSelect(request(route));
            assertThat(selection.mode()).isEqualTo(SqlSelection.MODE_SINGLE);
        }
        verify(generator, never()).generate(any());
    }

    // ------------------------------------------------------------ 预算与并发

    @Test
    @DisplayName("并行候选并发抢占预算：llm_call_count 不突破上限，评分也计数")
    void parallelCandidates_shouldNeverExceedLlmBudget() {
        AgentProperties properties = new AgentProperties(3, 2, 6000, 2);
        AgentLlmCallLimiter realLimiter = new AgentLlmCallLimiter(repository, properties);
        AtomicInteger counter = new AtomicInteger();
        // 模拟数据库条件更新：CAS 式递增，达到上限后返回 0。
        when(repository.tryIncrementLlmCalls(eq(RUN_ID), anyInt())).thenAnswer(invocation -> {
            int max = invocation.getArgument(1);
            for (;;) {
                int current = counter.get();
                if (current >= max) {
                    return 0;
                }
                if (counter.compareAndSet(current, current + 1)) {
                    return 1;
                }
            }
        });
        SqlCandidateGenerator realGenerator = new SqlCandidateGenerator(
                sqlAssistant, realLimiter,
                new com.queryforge.domain.model.LlmProperties(
                        "https://example.invalid/v1", "key", "model",
                        java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5)));
        when(sqlAssistant.generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.<Integer>getArgument(7) == 1 ? LONG_SQL : SHORT_SQL);

        SqlSelection selection = new SqlCandidateSelectionService(
                sqlAssistant, realLimiter, realGenerator, scorer).generateAndSelect(request("COMPLEX"));

        assertThat(counter.get()).isEqualTo(2); // 3 个候选 + 1 次评分竞争 2 个额度，绝不超额
        assertThat(selection.multiCandidate()).isTrue();
        assertThat(selection.ruleFallback()).isTrue(); // 评分拿不到额度 → 规则选优
        assertThat(selection.selectedSql()).isEqualTo(SHORT_SQL);
        verify(sqlAssistant, never()).scoreCandidates(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("两个 Run 并发走不同路由：互不影响，路由取自各自请求")
    void concurrentRuns_shouldNotShareRouteState() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        lenient().when(llmLimiter.count(eq(RUN_ID), org.mockito.ArgumentMatchers.<java.util.function.Supplier<String>>any()))
                .thenAnswer(invocation -> {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return SHORT_SQL;
                });
        lenient().when(generator.generate(any())).thenAnswer(invocation -> {
            barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return List.of(SqlCandidate.generated(1, SHORT_SQL), SqlCandidate.generated(2, LONG_SQL));
        });
        when(sqlAssistant.scoreCandidates(anyString(), anyString(), any()))
                .thenReturn("[{\"candidate\":1,\"score\":6},{\"candidate\":2,\"score\":7}]");

        SqlCandidateSelectionService service = service();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var simpleFuture = pool.submit(() -> service.generateAndSelect(request("SIMPLE")));
            var complexFuture = pool.submit(() -> service.generateAndSelect(request("COMPLEX")));
            SqlSelection simple = simpleFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
            SqlSelection complex = complexFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(simple.mode()).isEqualTo(SqlSelection.MODE_SINGLE);
            assertThat(simple.selectedSql()).isEqualTo(SHORT_SQL);
            assertThat(complex.mode()).isEqualTo(SqlSelection.MODE_MULTI);
            assertThat(complex.selectedCandidateIndex()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }
}
