package com.queryforge.analysis;

import java.util.List;

/**
 * 分析运行详情响应。stdout/stderr 只返回脱敏尾段，不返回凭据或原始全量数据。
 */
public record AnalysisRunResponse(
        Long id,
        Long queryHistoryId,
        Long conversationId,
        Long connectionId,
        Long agentRunId,
        Long agentStepId,
        String engine,
        String status,
        int inputRowCount,
        String chartType,
        List<ChartSchema.TypedChart> charts,
        String summary,
        String errorCode,
        String errorMessage,
        int exitCode,
        long durationMs,
        String createdAt,
        String updatedAt
) {
}
