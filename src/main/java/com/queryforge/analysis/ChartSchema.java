package com.queryforge.analysis;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * 结构化图表数据的可校验 schema。前端只按 schema 渲染，不执行任何模型代码片段。
 * 所有图表数值必须来自真实已执行的受限结果投影。
 */
public final class ChartSchema {

    private ChartSchema() {
    }

    /** 图表描述：标题、单位、结论必须能追溯到输出数据。 */
    public record Chart(
            String chartType,
            String title,
            String xLabel,
            String yLabel,
            List<Column> columns,
            List<List<Object>> data,
            String sourceRunId
    ) {
    }

    /** 单列定义，带数据类型约束。 */
    public record Column(String name, String dataType) {
    }

    /** 数值型聚合结果（table/bar/line/pie 共用）。 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "chartType")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TableChart.class, name = "table"),
            @JsonSubTypes.Type(value = BarChart.class, name = "bar"),
            @JsonSubTypes.Type(value = LineChart.class, name = "line"),
            @JsonSubTypes.Type(value = PieChart.class, name = "pie")
    })
    public sealed interface TypedChart permits TableChart, BarChart, LineChart, PieChart {
    }

    public record TableChart(String title, List<Column> columns, List<List<Object>> data, String sourceRunId)
            implements TypedChart {
    }

    public record BarChart(String title, String xLabel, String yLabel,
                           List<String> categories, List<Number> values, String sourceRunId)
            implements TypedChart {
    }

    public record LineChart(String title, String xLabel, String yLabel,
                            List<String> categories, List<Number> values, String sourceRunId)
            implements TypedChart {
    }

    public record PieChart(String title, List<String> labels, List<Number> values, String sourceRunId)
            implements TypedChart {
    }
}
