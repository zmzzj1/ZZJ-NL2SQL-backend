package com.queryforge.report;

import com.queryforge.analysis.AnalysisRunResponse;
import com.queryforge.analysis.ChartSchema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建报告请求：引用真实分析结果（analysisRunId），标题可选。
 * 报告内容由服务端基于真实分析工件渲染，前端不直接上传 HTML。
 */
public record CreateReportRequest(
        @NotNull Long analysisRunId,
        @Size(max = 200) String title
) {
}
