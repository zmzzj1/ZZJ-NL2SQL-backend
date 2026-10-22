package com.queryforge.application.report;

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

import com.queryforge.application.analysis.AnalysisService;
import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.domain.analysis.ChartSchema;
import com.queryforge.infrastructure.persistence.ReportRepository;
import com.queryforge.domain.report.CreateReportRequest;
import com.queryforge.domain.report.ReportResponse;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P14-C T05：ReportService 只基于真实分析工件生成报告，拒绝空引用/跨运行/未成功来源。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P14-C 报告生成（真实工件与越权拒绝）")
class AgentReportP14CTest {

    private static final Long USER_ID = 7L;
    private static final Long RUN_ID = 55L;
    private static final Long ANALYSIS_ID = 91L;
    private static final Long REPORT_ID = 300L;

    @Mock
    private ReportRepository repository;
    @Mock
    private AnalysisService analysisService;

    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(repository, analysisService);
    }

    private static AnalysisRunResponse analysis(Long agentRunId, String status, Long id) {
        return new AnalysisRunResponse(id, 5L, 2L, 1L, agentRunId, 10L, "SandboxPython", status, 2, "bar",
                List.of(new ChartSchema.BarChart("区域分布", "region", "total",
                        List.of("华东"), List.of(1200.0), String.valueOf(id))),
                "销售额集中在华东", null, null, 0, 30, "t", "t");
    }

    @Test
    @DisplayName("有真实工件：聚合成报告并绑定当前 Run")
    void createForAgentRun_shouldAggregateRealArtifacts() {
        when(analysisService.detail(USER_ID, ANALYSIS_ID)).thenReturn(analysis(RUN_ID, "SUCCEEDED", ANALYSIS_ID));
        when(repository.insert(eq(USER_ID), anyLong(), eq(RUN_ID), eq(ANALYSIS_ID), anyString(), anyString()))
                .thenReturn(REPORT_ID);
        when(repository.findOwned(REPORT_ID, USER_ID)).thenReturn(Optional.of(report()));

        ReportResponse response = service.createForAgentRun(
                USER_ID, RUN_ID, 12L, List.of(ANALYSIS_ID), "销售趋势报告", "整体上升");

        assertThat(response.id()).isEqualTo(REPORT_ID);
        assertThat(response.agentRunId()).isEqualTo(RUN_ID);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(eq(USER_ID), anyLong(), eq(RUN_ID), eq(ANALYSIS_ID),
                eq("销售趋势报告"), html.capture());
        // 报告只呈现真实图表与真实摘要，没有占位图表。
        assertThat(html.getValue()).contains("区域分布", "销售额集中在华东", "整体上升");
        assertThat(html.getValue()).doesNotContain("<script");
    }

    @Test
    @DisplayName("空工件列表：明确失败，不生成占位报告")
    void createForAgentRun_emptySources_shouldFail() {
        assertThatThrownBy(() -> service.createForAgentRun(USER_ID, RUN_ID, 12L, List.of(), "标题", ""))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(repository, never()).insert(anyLong(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("跨运行引用：拒绝（归属以服务端 Run 绑定为准）")
    void createForAgentRun_foreignRun_shouldReject() {
        when(analysisService.detail(USER_ID, ANALYSIS_ID)).thenReturn(analysis(999L, "SUCCEEDED", ANALYSIS_ID));

        assertThatThrownBy(() -> service.createForAgentRun(
                USER_ID, RUN_ID, 12L, List.of(ANALYSIS_ID), "标题", ""))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ANALYSIS_RUN_NOT_FOUND));
        verify(repository, never()).insert(anyLong(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("未成功的分析：拒绝作为报告来源")
    void createForAgentRun_failedAnalysis_shouldReject() {
        when(analysisService.detail(USER_ID, ANALYSIS_ID)).thenReturn(analysis(RUN_ID, "FAILED", ANALYSIS_ID));

        assertThatThrownBy(() -> service.createForAgentRun(
                USER_ID, RUN_ID, 12L, List.of(ANALYSIS_ID), "标题", ""))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ANALYSIS_RUN_NOT_FOUND));
    }

    @Test
    @DisplayName("HTML 中的动态值必须转义，脚本片段不留痕")
    void createForAgentRun_shouldEscapeNarrative() {
        when(analysisService.detail(USER_ID, ANALYSIS_ID)).thenReturn(analysis(RUN_ID, "SUCCEEDED", ANALYSIS_ID));
        when(repository.insert(anyLong(), any(), any(), any(), anyString(), anyString())).thenReturn(REPORT_ID);
        when(repository.findOwned(REPORT_ID, USER_ID)).thenReturn(Optional.of(report()));

        service.createForAgentRun(USER_ID, RUN_ID, 12L, List.of(ANALYSIS_ID),
                "标题<script>alert(1)</script>", "<img src=x onerror=alert(1)>");

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(anyLong(), any(), any(), any(), anyString(), html.capture());
        assertThat(html.getValue()).doesNotContain("<script", "onerror=", "<img");
        // 危险标签被整体丢弃、事件属性不保留，只留下无害文本。
        assertThat(html.getValue()).contains("alert(1)").doesNotContain("&lt;script&gt;");
    }

    private static ReportRepository.Report report() {
        LocalDateTime now = LocalDateTime.now();
        return new ReportRepository.Report(REPORT_ID, USER_ID, 1L, RUN_ID, ANALYSIS_ID, "销售趋势报告",
                "READY", "<h1>销售趋势报告</h1>", 64, 1, now, now);
    }

    @Test
    @DisplayName("单分析报告 API 契约不变（create 仍按原签名工作）")
    void create_shouldKeepLegacyContract() {
        when(analysisService.detail(USER_ID, ANALYSIS_ID)).thenReturn(analysis(null, "SUCCEEDED", ANALYSIS_ID));
        when(repository.insert(eq(USER_ID), anyLong(), any(), eq(ANALYSIS_ID), anyString(), anyString()))
                .thenReturn(REPORT_ID);
        when(repository.findOwned(REPORT_ID, USER_ID)).thenReturn(Optional.of(report()));

        ReportResponse response = service.create(USER_ID, new CreateReportRequest(ANALYSIS_ID, "旧契约标题"));

        assertThat(response.id()).isEqualTo(REPORT_ID);
        verify(repository).insert(eq(USER_ID), anyLong(), any(), eq(ANALYSIS_ID),
                eq("旧契约标题"), anyString());
    }

    @Test
    @DisplayName("报告列表与详情不受 P14-C 影响")
    void list_shouldStillWork() {
        when(repository.findByUser(USER_ID, 20)).thenReturn(List.of(report()));
        when(repository.findOwned(REPORT_ID, USER_ID)).thenReturn(Optional.of(report()));

        assertThat(service.list(USER_ID)).hasSize(1);
        assertThat(service.detail(USER_ID, REPORT_ID).status()).isEqualTo("READY");
        verify(repository).findByUser(eq(USER_ID), anyInt());
    }
}
