package com.queryforge.domain.analysis;

import java.util.List;

/**
 * 分析 SSE 具名事件的负载定义。所有事件带 runId 归属。
 */
public final class AnalysisStreamEvents {

    private AnalysisStreamEvents() {
    }

    public record AnalysisStarted(Long runId, String engine) {
    }

    public record AnalysisCompleted(Long runId, String chartType, int rowCount) {
    }

    public record AnalysisFailed(Long runId, String code, String message) {
    }

    public record AnalysisCanceled(Long runId) {
    }

    public record ReportReady(Long runId, Long reportId) {
    }
}
