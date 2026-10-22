package com.queryforge.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.application.query.QueryHistoryService;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.domain.analysis.AnalysisProperties;
import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.domain.analysis.AnalysisStatus;
import com.queryforge.domain.analysis.ChartSchema;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.infrastructure.analysis.SandboxPythonEngine;
import com.queryforge.infrastructure.persistence.AnalysisRepository;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.zzj.sandbox.DockerPythonSandboxExecutor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * P14-C 真实沙箱 E2E（服务层）：{@link AnalysisService} + 真实容器执行，仅存储层为 Mock。
 *
 * <p>验证 P14-C 的两条真实路径：
 * <ul>
 *   <li>成功：真实容器内模型代码产出图表 → 工件以 {@code {"charts":[...],"summary":"..."}} 包装落库，
 *       且 {@code detail()} 能跨请求读回真实摘要（Report 步骤依赖该契约）；</li>
 *   <li>失败：真实非零退出/超时只落一次失败、绝不 completeRun、绝不落工件（即退出码缺陷修复在真实路径下成立）。</li>
 * </ul>
 * Docker 不可用时如实跳过，不用桩冒充真实执行结论。
 */
class AgentCodeExecutionRealP14CTest {

    private static final Long USER_ID = 501L;
    private static final Long RUN_ID = 9001L;
    private static final String IMAGE = "python:3.12-slim";
    private static final List<String> COLUMNS = List.of("region", "amount");
    private static final List<Map<String, Object>> ROWS = List.of(
            Map.of("region", "华东", "amount", 120),
            Map.of("region", "华南", "amount", 80));

    private AnalysisRepository repository;
    private AnalysisService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repository = mock(AnalysisRepository.class);
        SandboxPythonEngine engine = new SandboxPythonEngine(
                new AnalysisProperties(200, 256 * 1024, 4000, 20, 256L * 1024 * 1024, 1.0, IMAGE, "python"),
                objectMapper,
                new DockerPythonSandboxExecutor());
        assumeTrue(engine.available(), "仅在 Docker 守护进程可用时执行真实容器验证");

        service = new AnalysisService(
                repository,
                mock(QueryHistoryService.class),
                mock(SqlExecutionService.class),
                mock(BuiltinAnalyzer.class),
                engine,
                new AnalysisProperties(200, 256 * 1024, 4000, 20, 256L * 1024 * 1024, 1.0, IMAGE, "python"),
                objectMapper);
        lenient().when(repository.insertRun(any(), any(), any(), any(), any(), any(), anyString(), anyString(),
                anyInt(), anyInt())).thenReturn(RUN_ID);
    }

    @Test
    @DisplayName("真实容器成功：completeRun + 工件按 {charts,summary} 包装落库，detail() 读回真实摘要")
    void realSuccess_persistsWrappedArtifactAndReadsBackSummary() throws Exception {
        stubOwned(AnalysisStatus.SUCCEEDED);
        String code = """
                import json
                payload = json.load(open('/work/input.json'))
                rows = payload['rows']
                agg = {}
                for row in rows:
                    agg[row['region']] = agg.get(row['region'], 0) + float(row['amount'])
                cats = sorted(agg)
                out = {'charts': [{'chartType': 'bar', 'title': '地区销售额', 'xLabel': '地区',
                                   'yLabel': '销售额', 'categories': cats,
                                   'values': [agg[c] for c in cats], 'sourceRunId': '9001'}],
                       'summary': '真实容器内聚合：合计 %.0f' % sum(agg.values())}
                open('/output/output.json', 'w').write(json.dumps(out, ensure_ascii=False))
                """;

        AnalysisRunResponse response = service.analyzeResult(
                USER_ID, 11L, 22L, 33L, 44L, 55L, "SELECT region, amount FROM t",
                new QueryResultResponse(COLUMNS, ROWS, ROWS.size(), false),
                code, null);

        assertThat(response.charts()).singleElement().isInstanceOf(ChartSchema.BarChart.class);
        assertThat(response.summary()).contains("真实容器内聚合").contains("200");

        verify(repository).completeRun(eq(RUN_ID), anyString(), anyString(), eq(0), anyLong());
        verify(repository, never()).failRun(anyLong(), anyString(), anyString(), anyString(), anyLong());

        // 工件必须是 P14-C 包装格式，Report 步骤才能跨请求读回摘要。
        ArgumentCaptor<String> artifact = ArgumentCaptor.forClass(String.class);
        verify(repository).insertArtifact(eq(RUN_ID), eq(USER_ID), eq("analysis_result"),
                eq("application/json"), anyInt(), artifact.capture());
        JsonNode node = objectMapper.readTree(artifact.getValue());
        assertThat(node.isObject()).isTrue();
        assertThat(node.path("charts").isArray()).isTrue();
        assertThat(node.path("charts")).hasSize(1);
        assertThat(node.path("summary").asText()).contains("真实容器内聚合");

        // 跨请求读回：detail() 从工件恢复真实摘要，而不是空。
        when(repository.findArtifacts(RUN_ID, USER_ID)).thenReturn(List.of(
                new AnalysisRepository.AnalysisArtifact(1L, RUN_ID, USER_ID, "analysis_result",
                        "application/json", artifact.getValue().length(), artifact.getValue(),
                        LocalDateTime.now())));
        AnalysisRunResponse detail = service.detail(USER_ID, RUN_ID);
        assertThat(detail.summary()).contains("真实容器内聚合");
        assertThat(detail.charts()).hasSize(1);
    }

    @Test
    @DisplayName("真实容器非零退出：只落一次失败，绝不 completeRun / 落工件（退出码缺陷修复的真实验证）")
    void realNonZeroExit_failsOnceAndNeverCompletes() {
        stubOwned(AnalysisStatus.FAILED);

        assertThatThrownBy(() -> service.analyzeResult(
                USER_ID, 11L, 22L, 33L, 44L, 55L, "SELECT 1",
                new QueryResultResponse(COLUMNS, ROWS, ROWS.size(), false),
                "raise RuntimeError('real-container-boom')\n", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ANALYSIS_EXECUTION_ERROR));

        verify(repository, times(1)).failRun(anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(repository, never()).completeRun(anyLong(), anyString(), anyString(), anyInt(), anyLong());
        verify(repository, never()).insertArtifact(anyLong(), anyLong(), anyString(), anyString(), anyInt(),
                anyString());
    }

    @Test
    @DisplayName("真实容器超时：明确失败关闭，不返回部分图表")
    void realTimeout_failsClosed() {
        stubOwned(AnalysisStatus.FAILED);
        AnalysisService shortTimeout = new AnalysisService(
                repository,
                mock(QueryHistoryService.class),
                mock(SqlExecutionService.class),
                mock(BuiltinAnalyzer.class),
                new SandboxPythonEngine(
                        new AnalysisProperties(200, 256 * 1024, 4000, 3, 256L * 1024 * 1024, 1.0, IMAGE, "python"),
                        objectMapper,
                        new DockerPythonSandboxExecutor()),
                new AnalysisProperties(200, 256 * 1024, 4000, 3, 256L * 1024 * 1024, 1.0, IMAGE, "python"),
                objectMapper);

        assertThatThrownBy(() -> shortTimeout.analyzeResult(
                USER_ID, 11L, 22L, 33L, 44L, 55L, "SELECT 1",
                new QueryResultResponse(COLUMNS, ROWS, ROWS.size(), false),
                "import time\ntime.sleep(60)\n", null))
                .isInstanceOf(ApiException.class);

        verify(repository, times(1)).failRun(anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(repository, never()).completeRun(anyLong(), anyString(), anyString(), anyInt(), anyLong());
    }

    private void stubOwned(AnalysisStatus status) {
        lenient().when(repository.findOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(
                new AnalysisRepository.AnalysisRun(RUN_ID, USER_ID, 22L, 11L, 33L, 44L, 55L,
                        "SELECT 1", "SandboxPython", status, 2, 256 * 1024, null, null,
                        "", "", 0, 0, LocalDateTime.now(), LocalDateTime.now())));
    }
}
