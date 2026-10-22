package com.queryforge.application.agent.sql;

import com.queryforge.application.agent.AgentLlmCallLimiter;
import com.queryforge.application.agent.AgentSqlAssistant;
import com.queryforge.domain.model.LlmProperties;
import com.queryforge.domain.model.ModelRouteContextHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P14-B：多候选 SQL 生成。一次请求内并行生成最多 {@value #MAX_CANDIDATES} 条候选，
 * 负责差异化 Prompt、LLM 调用、超时与单候选失败隔离；不做任何 JDBC 查询，
 * 也不判断 SQL 是否安全（安全校验由 {@link SqlCandidateScorer} 完成）。
 *
 * <p>并发与隔离：
 * <ul>
 *   <li>使用容量有界的专用执行器（禁止向公共无界线程池丢任务）；</li>
 *   <li>{@link ModelRouteContextHolder} 是 ThreadLocal，候选任务从主流程显式捕获
 *       Context 后逐线程 set → 调用 → finally clear，线程池复用不串用户；</li>
 *   <li>每个候选独立占用一次模型预算，单候选失败/超时不影响其他候选；</li>
 *   <li>回收严格按 candidateIndex 顺序，不依赖线程完成顺序。</li>
 * </ul>
 */
@Component
public class SqlCandidateGenerator {

    private static final Logger log = LoggerFactory.getLogger(SqlCandidateGenerator.class);

    /** 候选上限：一次选优最多 3 条候选。 */
    public static final int MAX_CANDIDATES = 3;

    private static final int POOL_SIZE = MAX_CANDIDATES;
    private static final int QUEUE_CAPACITY = 32;
    private static final int MAX_REJECTION_CHARS = 120;

    private final AgentSqlAssistant sqlAssistant;
    private final AgentLlmCallLimiter llmLimiter;
    private final long timeoutMillis;
    private final ExecutorService executor;

    public SqlCandidateGenerator(
            AgentSqlAssistant sqlAssistant, AgentLlmCallLimiter llmLimiter, LlmProperties llmProperties) {
        this.sqlAssistant = sqlAssistant;
        this.llmLimiter = llmLimiter;
        this.timeoutMillis = llmProperties.requestTimeout().toMillis();
        this.executor = new ThreadPoolExecutor(
                POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "sql-candidate-" + runnable.hashCode());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 并行生成候选。返回列表严格按 candidateIndex 升序（长度恒为 {@value #MAX_CANDIDATES}）；
     * 生成失败/预算不足/超时的候选标记为拒绝并带简短原因。
     */
    public List<SqlCandidate> generate(SqlSelectionRequest request) {
        ModelRouteContextHolder.Context modelContext = ModelRouteContextHolder.get();
        List<Future<SqlCandidate>> futures = new ArrayList<>(MAX_CANDIDATES);
        for (int index = 1; index <= MAX_CANDIDATES; index++) {
            final int candidateIndex = index;
            futures.add(executor.submit(() -> generateOne(request, candidateIndex, modelContext)));
        }
        List<SqlCandidate> candidates = new ArrayList<>(MAX_CANDIDATES);
        for (int index = 0; index < futures.size(); index++) {
            candidates.add(await(futures.get(index), index + 1));
        }
        return candidates;
    }

    /**
     * 在有界执行器上执行一次性 LLM 任务（评分调用复用同一执行器、超时与线程级模型上下文隔离）。
     * 超时/中断返回 null；任务自身异常原样抛出，由调用方决定降级策略。
     */
    public <T> T runBounded(Callable<T> task) {
        ModelRouteContextHolder.Context modelContext = ModelRouteContextHolder.get();
        Future<T> future = executor.submit(() -> {
            if (modelContext != null) {
                ModelRouteContextHolder.set(modelContext);
            }
            try {
                return task.call();
            } finally {
                ModelRouteContextHolder.clear();
            }
        });
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return null;
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("候选评分任务失败", cause);
        }
    }

    private SqlCandidate generateOne(
            SqlSelectionRequest request, int candidateIndex, ModelRouteContextHolder.Context modelContext) {
        if (modelContext != null) {
            ModelRouteContextHolder.set(modelContext);
        }
        try {
            // 每次生成都计入当前 Run 的模型调用次数；预算不足不越额生成。
            if (!llmLimiter.tryAcquire(request.runId())) {
                return SqlCandidate.rejected(candidateIndex, "模型调用预算不足，跳过该候选");
            }
            String sql = sqlAssistant.generateCandidateSql(
                    request.stepObjective(),
                    request.schema(),
                    request.previousSummaries(),
                    request.knowledge(),
                    request.systemPrompt(),
                    request.skillGuidance(),
                    request.authorizedContext(),
                    candidateIndex,
                    strategyFor(candidateIndex),
                    request.dialect());
            if (sql == null || sql.isBlank()) {
                return SqlCandidate.rejected(candidateIndex, "模型未返回 SQL");
            }
            return SqlCandidate.generated(candidateIndex, sql);
        } catch (RuntimeException ex) {
            log.debug("sql_candidate_generation_failed index={} reason={}", candidateIndex,
                    ex.getClass().getSimpleName());
            return SqlCandidate.rejected(candidateIndex, "候选生成失败：" + safeReason(ex));
        } finally {
            ModelRouteContextHolder.clear();
        }
    }

    private SqlCandidate await(Future<SqlCandidate> future, int candidateIndex) {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return SqlCandidate.rejected(candidateIndex, "候选生成超时");
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return SqlCandidate.rejected(candidateIndex, "候选生成被中断");
        } catch (ExecutionException ex) {
            return SqlCandidate.rejected(candidateIndex, "候选生成失败");
        }
    }

    /**
     * 差异化策略：#1 常规方案，#2/#3 适度要求替代性的 JOIN/聚合/筛选表达。
     * 只影响表达方式，不改变业务口径、Schema 使用范围与安全约束。
     */
    static String strategyFor(int candidateIndex) {
        return switch (candidateIndex) {
            case 1 -> "常规方案：使用最直接、可读性最好的 JOIN / 聚合 / 筛选表达。";
            case 2 -> "替代方案一：换一种 JOIN 路径或聚合维度（如不同的关联顺序、子查询与 JOIN 的取舍）。";
            default -> "替代方案二：换一种筛选与分组表达（如不同的条件组织方式或分组粒度）。";
        };
    }

    private static String safeReason(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > MAX_REJECTION_CHARS
                ? message.substring(0, MAX_REJECTION_CHARS)
                : message;
    }
}
