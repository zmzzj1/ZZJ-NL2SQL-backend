package com.queryforge.infrastructure.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.analysis.AnalysisEngine;
import com.queryforge.domain.analysis.AnalysisEngine.AnalysisResult;
import com.queryforge.domain.analysis.AnalysisExecutionException;
import com.queryforge.domain.analysis.AnalysisProperties;
import com.queryforge.domain.analysis.ChartSchema;
import com.zzj.sandbox.SandboxErrorCategory;
import com.zzj.sandbox.SandboxException;
import com.zzj.sandbox.SandboxExecutor;
import com.zzj.sandbox.SandboxLimits;
import com.zzj.sandbox.SandboxRequest;
import com.zzj.sandbox.SandboxResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 隔离 Python 沙箱适配器（P13-M4）：保留 {@link AnalysisEngine} 业务契约，
 * 只负责把 {@link AnalysisProperties}/业务输入转成 {@link SandboxRequest}、调用 {@link SandboxExecutor}、
 * 并把受控结构化 JSON 解析为原有 {@link ChartSchema} 图表/摘要。
 *
 * <p>Docker 进程管理、资源限制、超时与清理已下沉到 {@code zzj-sandbox} 模块；此处不再直接管理容器。
 * 安全行为不变：模型生成的代码只在隔离容器执行，Docker 不可用时拒绝执行（见 {@link AnalysisService} 的
 * {@code ANALYSIS_SANDBOX_UNAVAILABLE} 门禁），绝不回退宿主机。</p>
 */
@Component
public class SandboxPythonEngine implements AnalysisEngine {

    private static final int MAX_TAIL_CHARS = 2000;
    private static final int PIDS_LIMIT = 64;
    private static final int MAX_STDOUT_BYTES = 64 * 1024;
    private static final long MAX_OUTPUT_BYTES = 1024L * 1024;

    private final AnalysisProperties properties;
    private final ObjectMapper objectMapper;
    private final SandboxExecutor sandboxExecutor;

    public SandboxPythonEngine(AnalysisProperties properties, ObjectMapper objectMapper,
                               SandboxExecutor sandboxExecutor) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sandboxExecutor = sandboxExecutor;
    }

    @Override
    public String name() {
        return "SandboxPython";
    }

    @Override
    public boolean available() {
        return sandboxExecutor.available();
    }

    @Override
    public AnalysisResult run(String code, List<String> columns, List<Map<String, Object>> rows)
            throws AnalysisExecutionException {
        if (!available()) {
            throw new AnalysisExecutionException("隔离沙箱运行时不可用（Docker 未就绪）");
        }
        try {
            String inputJson;
            try {
                inputJson = objectMapper.writeValueAsString(new SandboxInput(columns, rows));
            } catch (JsonProcessingException ex) {
                throw new AnalysisExecutionException("沙箱输入序列化失败", ex);
            }
            SandboxRequest request = new SandboxRequest(code, inputJson);
            SandboxLimits limits = new SandboxLimits(
                    properties.sandboxImage(),
                    properties.sandboxCommand(),
                    properties.timeoutSeconds(),
                    properties.memoryBytes(),
                    properties.cpus(),
                    PIDS_LIMIT,
                    MAX_STDOUT_BYTES,
                    MAX_OUTPUT_BYTES);

            SandboxResult result = sandboxExecutor.execute(request, limits);

            if (result.errorCategory() == SandboxErrorCategory.TIMEOUT) {
                throw new AnalysisExecutionException("Python 执行超时（" + properties.timeoutSeconds() + " 秒）");
            }
            if (result.exitCode() != 0) {
                return new AnalysisResult(
                        List.of(), "Python 执行失败（退出码 " + result.exitCode() + "）",
                        tail(result.stdoutTail()), tail(result.stderrTail()), result.exitCode());
            }
            String structured = result.structuredOutput();
            if (structured == null || structured.isBlank()) {
                throw new AnalysisExecutionException("沙箱未产生结构化输出");
            }
            try {
                SandboxOutput parsed = objectMapper.readValue(structured, SandboxOutput.class);
                List<ChartSchema.TypedChart> charts = validateCharts(parsed.charts());
                String summary = parsed.summary() == null ? "" : parsed.summary();
                return new AnalysisResult(charts, summary,
                        tail(result.stdoutTail()), tail(result.stderrTail()), 0);
            } catch (AnalysisExecutionException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new AnalysisExecutionException("沙箱输出不是合法的图表 JSON", ex);
            }
        } catch (SandboxException ex) {
            throw new AnalysisExecutionException(ex.getMessage(), ex.getCause());
        }
    }

    /**
     * P14-C：严格校验图表 schema。类型只允许 table/bar/line/pie（反序列化已保证），
     * 各类型必填字段缺失、类目与数值长度不一致、表格行列不匹配都视为数据非法，
     * 分析失败，不得把残缺数据伪装成成功图表。
     */
    static List<ChartSchema.TypedChart> validateCharts(List<ChartSchema.TypedChart> charts)
            throws AnalysisExecutionException {
        if (charts == null) {
            throw new AnalysisExecutionException("沙箱输出缺少 charts 数组");
        }
        for (ChartSchema.TypedChart chart : charts) {
            if (chart instanceof ChartSchema.TableChart table) {
                requireText(table.title(), "table.title");
                if (table.columns() == null || table.columns().isEmpty()) {
                    throw new AnalysisExecutionException("table 图表缺少 columns");
                }
                for (ChartSchema.Column column : table.columns()) {
                    requireText(column.name(), "table.columns.name");
                    requireText(column.dataType(), "table.columns.dataType");
                }
                if (table.data() == null) {
                    throw new AnalysisExecutionException("table 图表缺少 data");
                }
                for (List<Object> row : table.data()) {
                    if (row == null || row.size() != table.columns().size()) {
                        throw new AnalysisExecutionException("table 图表行列数与列定义不一致");
                    }
                }
                requireText(table.sourceRunId(), "table.sourceRunId");
                continue;
            }
            List<String> categories;
            List<Number> values;
            String kind;
            if (chart instanceof ChartSchema.BarChart bar) {
                categories = bar.categories();
                values = bar.values();
                kind = "bar";
                requireText(bar.title(), "bar.title");
                requireText(bar.xLabel(), "bar.xLabel");
                requireText(bar.yLabel(), "bar.yLabel");
            } else if (chart instanceof ChartSchema.LineChart line) {
                categories = line.categories();
                values = line.values();
                kind = "line";
                requireText(line.title(), "line.title");
                requireText(line.xLabel(), "line.xLabel");
                requireText(line.yLabel(), "line.yLabel");
            } else if (chart instanceof ChartSchema.PieChart pie) {
                categories = pie.labels();
                values = pie.values();
                kind = "pie";
                requireText(pie.title(), "pie.title");
            } else {
                throw new AnalysisExecutionException("不支持的图表类型");
            }
            if (categories == null || values == null) {
                throw new AnalysisExecutionException(kind + " 图表缺少 categories/values");
            }
            if (categories.size() != values.size()) {
                throw new AnalysisExecutionException(kind + " 图表类目与数值数量不一致");
            }
            for (int index = 0; index < values.size(); index++) {
                if (values.get(index) == null || categories.get(index) == null) {
                    throw new AnalysisExecutionException(kind + " 图表存在空类别或空数值");
                }
            }
        }
        return charts;
    }

    private static void requireText(String value, String field) throws AnalysisExecutionException {
        if (value == null || value.isBlank()) {
            throw new AnalysisExecutionException("沙箱输出图表缺少必填字段 " + field);
        }
    }

    /** 沙箱输入：受限投影，不含连接凭据、Token 或宿主路径。 */
    public record SandboxInput(List<String> columns, List<Map<String, Object>> rows) {
    }

    /** 沙箱输出：符合图表 schema 的结构化结果。 */
    public record SandboxOutput(List<ChartSchema.TypedChart> charts, String summary) {
    }

    private static String tail(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() > MAX_TAIL_CHARS ? value.substring(0, MAX_TAIL_CHARS) : value;
    }
}
