package com.queryforge.report;

import com.queryforge.analysis.AnalysisRunResponse;
import com.queryforge.analysis.AnalysisService;
import com.queryforge.analysis.ChartSchema;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 报告生成：基于真实分析结果渲染安全 HTML（严格净化），持久化并鉴权访问。
 * 报告只引用真实 Query/Analysis 来源，不信任模型声称的统计值；标题/结论可追溯到图表数据。
 */
@Service
public class ReportService {

    private static final int LIST_LIMIT = 20;

    private final ReportRepository repository;
    private final AnalysisService analysisService;

    public ReportService(ReportRepository repository, AnalysisService analysisService) {
        this.repository = repository;
        this.analysisService = analysisService;
    }

    public ReportResponse create(Long userId, CreateReportRequest request) {
        AnalysisRunResponse analysis = analysisService.detail(userId, request.analysisRunId());
        String title = request.title() == null || request.title().isBlank()
                ? "数据分析报告" : request.title().trim();
        String html = render(analysis, title);
        Long id = repository.insert(userId, analysis.connectionId(), analysis.agentRunId(),
                analysis.id(), title, html);
        return toResponse(repository.findOwned(id, userId).orElseThrow());
    }

    public ReportResponse detail(Long userId, Long id) {
        return toResponse(requireOwned(userId, id));
    }

    public List<ReportResponse> list(Long userId) {
        return repository.findByUser(userId, LIST_LIMIT).stream()
                .map(ReportService::toResponse)
                .toList();
    }

    public void delete(Long userId, Long id) {
        if (repository.delete(id, userId) == 0) {
            throw new ApiException(ErrorCode.REPORT_NOT_FOUND, HttpStatus.NOT_FOUND, "报告不存在或无权访问");
        }
    }

    /** 渲染报告 HTML：只输出白名单标签，所有动态值经净化器转义，不含任何脚本。 */
    private String render(AnalysisRunResponse analysis, String title) {
        StringBuilder builder = new StringBuilder();
        builder.append("<h1>").append(ReportHtmlSanitizer.sanitize(title)).append("</h1>");
        builder.append("<p>分析运行 #").append(analysis.id()).append("（引擎 ")
                .append(ReportHtmlSanitizer.sanitize(analysis.engine())).append("）</p>");
        if (analysis.summary() != null && !analysis.summary().isBlank()) {
            builder.append("<p>").append(ReportHtmlSanitizer.sanitize(analysis.summary())).append("</p>");
        }
        for (ChartSchema.TypedChart chart : analysis.charts()) {
            appendChart(builder, chart);
        }
        return builder.toString();
    }

    private void appendChart(StringBuilder builder, ChartSchema.TypedChart chart) {
        if (chart instanceof ChartSchema.TableChart table) {
            builder.append("<h2>").append(ReportHtmlSanitizer.sanitize(table.title())).append("</h2>");
            builder.append("<table><thead><tr>");
            for (ChartSchema.Column column : table.columns()) {
                builder.append("<th>").append(ReportHtmlSanitizer.sanitize(column.name())).append("</th>");
            }
            builder.append("</tr></thead><tbody>");
            for (List<Object> row : table.data()) {
                builder.append("<tr>");
                for (Object value : row) {
                    builder.append("<td>").append(ReportHtmlSanitizer.sanitize(String.valueOf(value))).append("</td>");
                }
                builder.append("</tr>");
            }
            builder.append("</tbody></table>");
            return;
        }
        List<String> categories;
        List<Number> values;
        String xLabel;
        String yLabel;
        if (chart instanceof ChartSchema.BarChart bar) {
            categories = bar.categories();
            values = bar.values();
            xLabel = bar.xLabel();
            yLabel = bar.yLabel();
        } else if (chart instanceof ChartSchema.LineChart line) {
            categories = line.categories();
            values = line.values();
            xLabel = line.xLabel();
            yLabel = line.yLabel();
        } else if (chart instanceof ChartSchema.PieChart pie) {
            categories = pie.labels();
            values = pie.values();
            xLabel = "类别";
            yLabel = "数值";
        } else {
            return;
        }
        builder.append("<h2>").append(ReportHtmlSanitizer.sanitize(chartTitle(chart))).append("</h2>");
        builder.append("<table><thead><tr><th>")
                .append(ReportHtmlSanitizer.sanitize(xLabel)).append("</th><th>")
                .append(ReportHtmlSanitizer.sanitize(yLabel)).append("</th></tr></thead><tbody>");
        for (int i = 0; i < categories.size() && i < values.size(); i++) {
            builder.append("<tr><td>").append(ReportHtmlSanitizer.sanitize(categories.get(i)))
                    .append("</td><td>").append(ReportHtmlSanitizer.sanitize(String.valueOf(values.get(i))))
                    .append("</td></tr>");
        }
        builder.append("</tbody></table>");
    }

    private static String chartTitle(ChartSchema.TypedChart chart) {
        if (chart instanceof ChartSchema.BarChart bar) {
            return bar.title();
        }
        if (chart instanceof ChartSchema.LineChart line) {
            return line.title();
        }
        if (chart instanceof ChartSchema.PieChart pie) {
            return pie.title();
        }
        return "图表";
    }

    private ReportRepository.Report requireOwned(Long userId, Long id) {
        return repository.findOwned(id, userId).orElseThrow(() -> new ApiException(
                ErrorCode.REPORT_NOT_FOUND, HttpStatus.NOT_FOUND, "报告不存在或无权访问"));
    }

    private static ReportResponse toResponse(ReportRepository.Report report) {
        return new ReportResponse(
                report.id(), report.analysisRunId(), report.connectionId(), report.agentRunId(),
                report.title(), report.status(), report.htmlContent(), report.sizeBytes(), report.revision(),
                report.createdAt().toString(), report.updatedAt().toString());
    }
}
