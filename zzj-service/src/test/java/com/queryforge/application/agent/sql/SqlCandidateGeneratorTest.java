package com.queryforge.application.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.queryforge.application.agent.AgentLlmCallLimiter;
import com.queryforge.application.agent.AgentSqlAssistant;
import com.queryforge.domain.model.LlmProperties;
import com.queryforge.domain.model.ModelRouteContextHolder;
import com.queryforge.shared.error.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * P14-B：多候选生成的并发、隔离、超时与线程级模型上下文（Mock 协作者，不调用真实模型）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqlCandidateGeneratorTest {

    private static final long RUN_ID = 5L;
    private static final long STEP_ID = 11L;
    private static final ModelRouteContextHolder.Context CONTEXT =
            new ModelRouteContextHolder.Context(1L, 2L, null, RUN_ID, 3L, 7L);

    @Mock
    private AgentSqlAssistant sqlAssistant;
    @Mock
    private AgentLlmCallLimiter llmLimiter;

    @AfterEach
    void tearDown() {
        ModelRouteContextHolder.clear();
    }

    private SqlCandidateGenerator generator(Duration requestTimeout) {
        return new SqlCandidateGenerator(
                sqlAssistant, llmLimiter,
                new LlmProperties("https://example.invalid/v1", "key", "model",
                        Duration.ofSeconds(5), requestTimeout));
    }

    private SqlSelectionRequest request() {
        return new SqlSelectionRequest(
                RUN_ID, STEP_ID, "COMPLEX", "统计订单", "按月统计订单",
                "数据库：sql_demo_a", "sql_demo_a", "", "", "", "", "", "MySQL 8");
    }

    private void stubAllCandidates(java.util.function.Function<Integer, String> answer) {
        when(sqlAssistant.generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString()))
                .thenAnswer(invocation -> answer.apply(invocation.getArgument(7)));
    }

    @Test
    @DisplayName("候选回收严格按 candidateIndex 顺序，不依赖线程完成顺序")
    void generate_shouldReturnCandidatesInIndexOrder() throws Exception {
        when(llmLimiter.tryAcquire(RUN_ID)).thenReturn(true);
        // 完成顺序与索引相反：候选 1 最慢、候选 3 最快。
        stubAllCandidates(index -> {
            try {
                Thread.sleep(index == 1 ? 300 : index == 2 ? 30 : 60);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "SELECT " + index + " FROM shop_orders";
        });

        List<SqlCandidate> candidates = generator(Duration.ofSeconds(5)).generate(request());

        assertThat(candidates).hasSize(3);
        assertThat(candidates).extracting(SqlCandidate::candidateIndex).containsExactly(1, 2, 3);
        assertThat(candidates).extracting(SqlCandidate::sql)
                .containsExactly("SELECT 1 FROM shop_orders", "SELECT 2 FROM shop_orders", "SELECT 3 FROM shop_orders");
    }

    @Test
    @DisplayName("单候选失败被隔离：不影响其他合法候选")
    void generate_shouldIsolateSingleCandidateFailure() {
        when(llmLimiter.tryAcquire(RUN_ID)).thenReturn(true);
        stubAllCandidates(index -> {
            if (index == 2) {
                throw new ApiException(
                        com.queryforge.shared.error.ErrorCode.LLM_UPSTREAM_ERROR,
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "模型服务暂不可用");
            }
            return "SELECT " + index + " FROM shop_orders";
        });

        List<SqlCandidate> candidates = generator(Duration.ofSeconds(5)).generate(request());

        assertThat(candidates).extracting(SqlCandidate::sql)
                .containsExactly("SELECT 1 FROM shop_orders", "", "SELECT 3 FROM shop_orders");
        assertThat(candidates.get(1).rejectionReason()).contains("候选生成失败");
    }

    @Test
    @DisplayName("模型调用预算不足时不越额生成该候选")
    void generate_shouldRejectCandidateWhenBudgetExhausted() {
        java.util.concurrent.atomic.AtomicInteger acquisitions = new java.util.concurrent.atomic.AtomicInteger();
        when(llmLimiter.tryAcquire(RUN_ID)).thenAnswer(invocation -> acquisitions.incrementAndGet() <= 2);
        stubAllCandidates(index -> "SELECT " + index + " FROM shop_orders");

        List<SqlCandidate> candidates = generator(Duration.ofSeconds(5)).generate(request());

        // 预算只够两次：恰好一个候选因预算不足被拒绝，且不越额。
        assertThat(acquisitions.get()).isEqualTo(3);
        assertThat(candidates.stream()
                .filter(candidate -> candidate.rejectionReason().contains("预算不足"))
                .count()).isEqualTo(1L);
        assertThat(candidates.stream().filter(candidate -> !candidate.sql().isBlank()).count()).isEqualTo(2L);
    }

    @Test
    @DisplayName("模型未返回文本时该候选失败，不当成成功")
    void generate_shouldRejectBlankCandidate() {
        when(llmLimiter.tryAcquire(RUN_ID)).thenReturn(true);
        stubAllCandidates(index -> index == 1 ? "   " : "SELECT " + index + " FROM shop_orders");

        List<SqlCandidate> candidates = generator(Duration.ofSeconds(5)).generate(request());

        assertThat(candidates.get(0).valid()).isFalse();
        assertThat(candidates.get(0).rejectionReason()).isEqualTo("模型未返回 SQL");
    }

    @Test
    @DisplayName("超时候选被标记失败，不把超时当作成功")
    void generate_shouldTimeOutSlowCandidate() {
        when(llmLimiter.tryAcquire(RUN_ID)).thenReturn(true);
        stubAllCandidates(index -> {
            if (index == 2) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return "SELECT " + index + " FROM shop_orders";
        });

        List<SqlCandidate> candidates = generator(Duration.ofMillis(300)).generate(request());

        assertThat(candidates.get(1).valid()).isFalse();
        assertThat(candidates.get(1).rejectionReason()).isEqualTo("候选生成超时");
        assertThat(candidates.get(0).sql()).isEqualTo("SELECT 1 FROM shop_orders");
        assertThat(candidates.get(2).sql()).isEqualTo("SELECT 3 FROM shop_orders");
    }

    @Test
    @DisplayName("线程级模型上下文：候选任务显式 set/finally clear，主流程上下文不被串改")
    void generate_shouldIsolateModelRouteContextPerThread() throws Exception {
        when(llmLimiter.tryAcquire(RUN_ID)).thenReturn(true);
        List<ModelRouteContextHolder.Context> seen = new CopyOnWriteArrayList<>();
        stubAllCandidates(index -> {
            seen.add(ModelRouteContextHolder.get());
            return "SELECT " + index + " FROM shop_orders";
        });

        ModelRouteContextHolder.set(CONTEXT);
        List<SqlCandidate> candidates = generator(Duration.ofSeconds(5)).generate(request());

        assertThat(candidates).hasSize(3);
        assertThat(seen).hasSize(3);
        assertThat(seen).containsOnly(CONTEXT);
        // 主流程上下文不受工作线程 clear 影响。
        assertThat(ModelRouteContextHolder.get()).isSameAs(CONTEXT);
        // 另一个 run 的上下文不会与本次混淆。
        ModelRouteContextHolder.set(new ModelRouteContextHolder.Context(9L, null, null, 99L, null, null));
        assertThat(ModelRouteContextHolder.get().runId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("runBounded：评分任务同样携带模型上下文；超时返回 null 供调用方降级")
    void runBounded_shouldCarryContextAndReturnNullOnTimeout() throws Exception {
        when(llmLimiter.tryAcquire(RUN_ID)).thenReturn(true);
        ModelRouteContextHolder.set(CONTEXT);
        List<ModelRouteContextHolder.Context> seen = new CopyOnWriteArrayList<>();

        String fast = generator(Duration.ofSeconds(5)).runBounded(() -> {
            seen.add(ModelRouteContextHolder.get());
            return "ok";
        });
        assertThat(fast).isEqualTo("ok");
        assertThat(seen).containsOnly(CONTEXT);

        String slow = generator(Duration.ofMillis(200)).runBounded(() -> {
            Thread.sleep(3000);
            return "late";
        });
        assertThat(slow).isNull();
    }

    @Test
    @DisplayName("差异化策略：#2/#3 与 #1 不同，且都只谈表达方式")
    void strategyFor_shouldDifferentiateCandidates() {
        String first = SqlCandidateGenerator.strategyFor(1);
        String second = SqlCandidateGenerator.strategyFor(2);
        String third = SqlCandidateGenerator.strategyFor(3);
        assertThat(first).isNotEqualTo(second);
        assertThat(second).isNotEqualTo(third);
        assertThat(first).contains("常规方案");
        assertThat(second).contains("替代方案一");
        assertThat(third).contains("替代方案二");
    }

    @Test
    @DisplayName("候选上限为 3")
    void maxCandidates_shouldBeThree() {
        assertThat(SqlCandidateGenerator.MAX_CANDIDATES).isEqualTo(3);
    }
}
