package com.queryforge.application.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.domain.query.QueryHistoryResponse;
import com.queryforge.application.query.QueryHistoryService;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.queryforge.domain.analysis.AnalysisEngine;
import com.queryforge.domain.analysis.AnalysisExecutionException;
import com.queryforge.domain.analysis.AnalysisProperties;
import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.domain.analysis.ChartSchema;
import com.queryforge.domain.analysis.CreateAnalysisRunRequest;
import com.queryforge.infrastructure.analysis.SandboxPythonEngine;
import com.queryforge.infrastructure.persistence.AnalysisRepository;

/**
 * 分析运行编排：查询历史 → 归属校验 → 重新执行 SQL 取真实受限结果 → 裁剪 → 分析 → 存工件。
 * 关键安全不变量：
 * 1. 输入只来自「服务端重新校验权限与 SQL Guard 后重新执行的本次真实结果」，绝不接受前端伪造数组；
 * 2. 模型代码仅在 SandboxPython 引擎（隔离容器）执行；BuiltinAnalyzer 是受信任确定性聚合，二者不混用；
 * 3. 沙箱不可用时拒绝执行并标 BLOCKED，不回退宿主机 Python。
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private static final String ARTIFACT_KIND = "analysis_result";
    private static final String MIME_JSON = "application/json";

    private final AnalysisRepository repository;
    private final QueryHistoryService historyService;
    private final SqlExecutionService executionService;
    private final BuiltinAnalyzer builtinAnalyzer;
    private final SandboxPythonEngine sandboxEngine;
    private final AnalysisProperties properties;
    private final ObjectMapper objectMapper;

    public AnalysisService(
            AnalysisRepository repository,
            QueryHistoryService historyService,
            SqlExecutionService executionService,
            BuiltinAnalyzer builtinAnalyzer,
            SandboxPythonEngine sandboxEngine,
            AnalysisProperties properties,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.historyService = historyService;
        this.executionService = executionService;
        this.builtinAnalyzer = builtinAnalyzer;
        this.sandboxEngine = sandboxEngine;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AnalysisRunResponse create(Long userId, CreateAnalysisRunRequest request) {
        QueryHistoryResponse history = historyService.detail(userId, request.queryHistoryId());
        if (history.connectionId() == null) {
            throw new ApiException(ErrorCode.HISTORY_NOT_FOUND, HttpStatus.NOT_FOUND, "该记录关联的数据源已删除，无法分析");
        }
        QueryResultResponse result = executionService.execute(
                userId, new ExecuteSqlRequest(history.connectionId(), history.conversationId(), history.sql()));
        return analyzeResult(userId, history.connectionId(), history.conversationId(),
                history.runId(), history.stepId(), history.id(), history.sql(), result, request.code(), request.chartType());
    }

    /**
     * 直接分析给定的真实查询结果（供 workflow 复用，避免重复执行 SQL）。
     * 结果必须来自服务端已授权、已完成的真实执行，绝不接受前端伪造数据。
     */
    public AnalysisRunResponse analyzeResult(Long userId, Long connectionId, Long conversationId,
                                             Long agentRunId, Long agentStepId, Long queryHistoryId, String sourceSql,
                                             QueryResultResponse result, String code, String chartType) {
        boolean sandbox = code != null && !code.isBlank();
        AnalysisEngine engine = sandbox ? sandboxEngine : builtinAnalyzer;
        if (sandbox && !engine.available()) {
            throw new ApiException(
                    ErrorCode.ANALYSIS_SANDBOX_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "隔离 Python 沙箱不可用，已拒绝执行模型代码");
        }

        Long runId = repository.insertRun(
                userId, conversationId, connectionId, agentRunId, agentStepId,
                queryHistoryId, sourceSql, engine.name(),
                Math.min(result.rows().size(), properties.maxInputRows()), properties.maxInputBytes());

        boolean failureRecorded = false;
        try {
            Trimmed trimmed = trim(result);
            long startedAt = System.nanoTime();
            AnalysisEngine.AnalysisResult analysis = engine.run(code, trimmed.columns(), trimmed.rows());
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

            // P14-C：退出码非零（含超时/被杀）必须落失败，不能先 completeRun 再显示失败徽标。
            if (analysis.exitCode() != 0) {
                String message = safe(analysis.summary());
                repository.failRun(runId, "ANALYSIS_EXECUTION_ERROR", message, analysis.stderrTail(), durationMs);
                failureRecorded = true;
                throw new ApiException(
                        ErrorCode.ANALYSIS_EXECUTION_ERROR, HttpStatus.UNPROCESSABLE_ENTITY, message);
            }

            repository.completeRun(runId, analysis.stdoutTail(), analysis.stderrTail(), analysis.exitCode(), durationMs);
            // 工件同时保存 charts 与 summary，保证跨请求（Report 步骤）可读回真实摘要。
            String artifactJson = objectMapper.writeValueAsString(
                    new AnalysisArtifactPayload(analysis.charts(), analysis.summary()));
            repository.insertArtifact(runId, userId, ARTIFACT_KIND, MIME_JSON,
                    artifactJson.getBytes(StandardCharsets.UTF_8).length, artifactJson);
            return toResponse(repository.findOwned(runId, userId).orElseThrow(), analysis.charts(), analysis.summary());
        } catch (AnalysisExecutionException ex) {
            repository.failRun(runId, "ANALYSIS_EXECUTION_ERROR", safe(ex.getMessage()), "", 0);
            throw new ApiException(
                    ErrorCode.ANALYSIS_EXECUTION_ERROR, HttpStatus.UNPROCESSABLE_ENTITY, safe(ex.getMessage()));
        } catch (ApiException ex) {
            // 已在分支内落过失败（带真实退出码与耗时）的不再重复写，避免覆盖诊断信息。
            if (!failureRecorded) {
                repository.failRun(runId, ex.getErrorCode().name(), safe(ex.getMessage()), "", 0);
            }
            throw ex;
        } catch (IOException ex) {
            repository.failRun(runId, "ANALYSIS_EXECUTION_ERROR", "分析结果序列化失败", "", 0);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "分析结果序列化失败");
        } catch (RuntimeException ex) {
            log.warn("analysis_failed runId={} message={}", runId, ex.getMessage());
            repository.failRun(runId, "ANALYSIS_EXECUTION_ERROR", "分析执行失败", "", 0);
            throw new ApiException(ErrorCode.ANALYSIS_EXECUTION_ERROR, HttpStatus.UNPROCESSABLE_ENTITY, "分析执行失败");
        }
    }

    public AnalysisRunResponse detail(Long userId, Long id) {
        AnalysisRepository.AnalysisRun run = requireOwned(userId, id);
        AnalysisArtifactPayload payload = readArtifact(id, userId);
        return toResponse(run, payload.charts(), payload.summary());
    }

    public void cancel(Long userId, Long id) {
        requireOwned(userId, id);
        repository.cancelActiveRun(id, userId);
    }

    private Trimmed trim(QueryResultResponse result) {
        List<Map<String, Object>> rows = result.rows();
        if (rows.size() > properties.maxInputRows()) {
            rows = rows.subList(0, properties.maxInputRows());
        }
        return new Trimmed(result.columns(), rows);
    }

    /**
     * 读取分析工件。兼容两种历史格式：旧版为纯图表 JSON 数组，P14-C 起为
     * {@code {"charts":[...],"summary":"..."}} 包装；解析失败按空工件处理（不冒充成功）。
     */
    private AnalysisArtifactPayload readArtifact(Long analysisRunId, Long userId) {
        return repository.findArtifacts(analysisRunId, userId).stream()
                .filter(artifact -> ARTIFACT_KIND.equals(artifact.kind()))
                .findFirst()
                .map(artifact -> parseArtifact(artifact.content()))
                .orElseGet(AnalysisArtifactPayload::empty);
    }

    private AnalysisArtifactPayload parseArtifact(String json) {
        if (json == null || json.isBlank()) {
            return AnalysisArtifactPayload.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) {
                return new AnalysisArtifactPayload(parseCharts(node), null);
            }
            if (node.isObject()) {
                JsonNode summary = node.path("summary");
                return new AnalysisArtifactPayload(
                        parseCharts(node.path("charts")),
                        summary.isTextual() ? summary.asText() : null);
            }
            return AnalysisArtifactPayload.empty();
        } catch (IOException ex) {
            log.warn("analysis_chart_parse_failed message={}", ex.getMessage());
            return AnalysisArtifactPayload.empty();
        }
    }

    private List<ChartSchema.TypedChart> parseCharts(JsonNode node) throws IOException {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(node, new TypeReference<List<ChartSchema.TypedChart>>() {
        });
    }

    private AnalysisRepository.AnalysisRun requireOwned(Long userId, Long id) {
        return repository.findOwned(id, userId).orElseThrow(() -> new ApiException(
                ErrorCode.ANALYSIS_RUN_NOT_FOUND, HttpStatus.NOT_FOUND, "分析运行不存在或无权访问"));
    }

    private AnalysisRunResponse toResponse(AnalysisRepository.AnalysisRun run,
                                           List<ChartSchema.TypedChart> charts, String summary) {
        String chartType = charts.isEmpty() ? null : chartType(charts.get(0));
        return new AnalysisRunResponse(
                run.id(), run.queryHistoryId(), run.conversationId(), run.connectionId(),
                run.agentRunId(), run.agentStepId(), run.engine(), run.status().name(),
                run.inputRowCount(), chartType, charts, summary,
                run.errorCode(), run.errorMessage(), run.exitCode(), run.durationMs(),
                run.createdAt().toString(), run.updatedAt().toString());
    }

    private static String chartType(ChartSchema.TypedChart chart) {
        if (chart instanceof ChartSchema.TableChart) {
            return "table";
        }
        if (chart instanceof ChartSchema.BarChart) {
            return "bar";
        }
        if (chart instanceof ChartSchema.LineChart) {
            return "line";
        }
        if (chart instanceof ChartSchema.PieChart) {
            return "pie";
        }
        return "unknown";
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "分析执行失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private record Trimmed(List<String> columns, List<Map<String, Object>> rows) {
    }

    /** 分析工件内容：真实图表 + 真实摘要（均可跨请求读回）。 */
    private record AnalysisArtifactPayload(List<ChartSchema.TypedChart> charts, String summary) {

        static AnalysisArtifactPayload empty() {
            return new AnalysisArtifactPayload(List.of(), null);
        }
    }
}
