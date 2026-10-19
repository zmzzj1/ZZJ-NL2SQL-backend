package com.queryforge.report;

/**
 * 报告详情响应。htmlContent 为服务端净化后的安全 HTML。
 */
public record ReportResponse(
        Long id,
        Long analysisRunId,
        Long connectionId,
        Long agentRunId,
        String title,
        String status,
        String htmlContent,
        int sizeBytes,
        int revision,
        String createdAt,
        String updatedAt
) {
}
