package com.queryforge.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.analysis.AnalysisEngine;
import com.queryforge.domain.analysis.AnalysisProperties;
import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.domain.analysis.AnalysisStatus;
import com.queryforge.domain.analysis.ChartSchema;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.infrastructure.analysis.SandboxPythonEngine;
import com.queryforge.infrastructure.persistence.AnalysisRepository;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P14-C T03：沙箱执行的真实成败语义。Docker 在本机不可用（BLOCKED），
 * 因此本类不启动真实容器，只验证编排层对 exitCode/availability 的处理是否诚实。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P14-C 分析执行编排（沙箱退出码与工件）")
class AgentCodeExecutionP14CTest {

    private static final Long USER_ID = 42L;
    private static final Long RUN_ID = 900L;

    @Mock
    private AnalysisRepository repository;
    @Mock
    private BuiltinAnalyzer builtinAnalyzer;
    @Mock
    private SandboxPythonEngine sandboxEngine;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AnalysisService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisService(repository, null, null, builtinAnalyzer, sandboxEngine,
                new AnalysisProperties(100, 1024, 4000, 20, 256L * 1024 * 1024, 1.0,
                        "python:3.12-slim", "python"), objectMapper);
        org.mockito.Mockito.lenient().when(repository.insertRun(eq(USER_ID), any(), any(), any(), any(), any(),
                        anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(RUN_ID);
    }

    private static QueryResultResponse result() {
        return new QueryResultResponse(List.of("region"), List.of(Map.of("region", "华东")), 1, false);
    }

    @Test
    @DisplayName("沙箱退出码非 0：落失败并抛错，绝不先标记成功")
    void nonZeroExitCode_shouldFailRun() {
        when(sandboxEngine.available()).thenReturn(true);
        when(sandboxEngine.name()).thenReturn("SandboxPython");
        when(sandboxEngine.run(anyString(), any(), any()))
                .thenReturn(new AnalysisEngine.AnalysisResult(List.of(), "Traceback ...", "out", "err", 1));

        assertThatThrownBy(() -> service.analyzeResult(USER_ID, 1L, 2L, 3L, 4L, 5L, "SELECT 1", result(),
                "print(1)", "bar"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ANALYSIS_EXECUTION_ERROR));

        verify(repository).failRun(eq(RUN_ID), eq("ANALYSIS_EXECUTION_ERROR"), anyString(), anyString(), anyLong());
        verify(repository, never()).completeRun(anyLong(), anyString(), anyString(), anyInt(), anyLong());
        verify(repository, never()).insertArtifact(anyLong(), anyLong(), anyString(), anyString(), anyInt(),
                anyString());
    }

    @Test
    @DisplayName("沙箱不可用：拒绝执行模型代码，不静默回退宿主机")
    void sandboxUnavailable_shouldReject() {
        when(sandboxEngine.available()).thenReturn(false);

        assertThatThrownBy(() -> service.analyzeResult(USER_ID, 1L, 2L, 3L, 4L, 5L, "SELECT 1", result(),
                "print(1)", "bar"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ANALYSIS_SANDBOX_UNAVAILABLE));

        verify(repository, never()).insertRun(anyLong(), any(), any(), any(), any(), any(), anyString(),
                anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("成功：工件以 charts+summary 包装落库，detail 可读回真实摘要")
    void success_shouldPersistArtifactWithSummary() {
        when(sandboxEngine.available()).thenReturn(true);
        when(sandboxEngine.name()).thenReturn("SandboxPython");
        when(sandboxEngine.run(anyString(), any(), any())).thenReturn(new AnalysisEngine.AnalysisResult(
                List.of(new ChartSchema.BarChart("区域分布", "region", "total",
                        List.of("华东"), List.of(1200.0), "900")),
                "销售额集中在华东", "out", "", 0));
        when(repository.findOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(analysisRun(AnalysisStatus.SUCCEEDED)));

        AnalysisRunResponse response = service.analyzeResult(USER_ID, 1L, 2L, 3L, 4L, 5L, "SELECT 1", result(),
                "print(1)", "bar");

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.summary()).isEqualTo("销售额集中在华东");
        assertThat(response.charts()).hasSize(1);

        ArgumentCaptor<String> artifact = ArgumentCaptor.forClass(String.class);
        verify(repository).insertArtifact(eq(RUN_ID), eq(USER_ID), anyString(), anyString(), anyInt(),
                artifact.capture());
        assertThat(artifact.getValue()).contains("\"charts\"", "\"summary\":\"销售额集中在华东\"");

        // 跨请求 detail 必须读回同一份真实摘要（Report 步骤依赖它，不允许空串冒充）。
        when(repository.findArtifacts(RUN_ID, USER_ID)).thenReturn(List.of(
                new AnalysisRepository.AnalysisArtifact(1L, RUN_ID, USER_ID, "analysis_result",
                        "application/json", artifact.getValue().getBytes(StandardCharsets.UTF_8).length,
                        artifact.getValue(), LocalDateTime.now())));
        AnalysisRunResponse detail = service.detail(USER_ID, RUN_ID);
        assertThat(detail.summary()).isEqualTo("销售额集中在华东");
        assertThat(detail.charts()).hasSize(1);
    }

    @Test
    @DisplayName("旧版纯数组工件：兼容解析为图表，summary 为空而不是伪造")
    void legacyArrayArtifact_shouldParseChartsOnly() {
        String legacy = "[{\"chartType\":\"table\",\"title\":\"t\",\"columns\":[],\"data\":[],"
                + "\"sourceRunId\":\"900\"}]";
        when(repository.findOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(analysisRun(AnalysisStatus.SUCCEEDED)));
        when(repository.findArtifacts(RUN_ID, USER_ID)).thenReturn(List.of(
                new AnalysisRepository.AnalysisArtifact(1L, RUN_ID, USER_ID, "analysis_result",
                        "application/json", legacy.length(), legacy, LocalDateTime.now())));

        AnalysisRunResponse detail = service.detail(USER_ID, RUN_ID);

        assertThat(detail.charts()).hasSize(1);
        assertThat(detail.summary()).isNull();
    }

    private static AnalysisRepository.AnalysisRun analysisRun(AnalysisStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new AnalysisRepository.AnalysisRun(RUN_ID, USER_ID, 2L, 1L, 3L, 4L, 5L, "SELECT 1",
                "SandboxPython", status, 1, 1024, null, null, "out", "", 0, 12, now, now);
    }
}
