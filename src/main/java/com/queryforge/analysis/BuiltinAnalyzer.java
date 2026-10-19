package com.queryforge.analysis;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 受信任的内置分析器：用确定性逻辑对「服务端已授权的受限结果」做分组/聚合，产出真实图表数据。
 * 这不是模型生成代码的执行，也不依赖外部容器，因此始终 available，用于打通
 * SQL → 分析 → 图表 → 报告的完整真实闭环（P602/P603/P608）。
 * 任意模型 Python 代码仍必须走 SandboxPython 隔离运行时，二者不混用。
 */
@Component
public class BuiltinAnalyzer implements AnalysisEngine {

    @Override
    public String name() {
        return "BuiltinAnalyzer";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public AnalysisResult run(String code, List<String> columns, List<Map<String, Object>> rows)
            throws AnalysisExecutionException {
        if (rows.isEmpty()) {
            return new AnalysisResult(List.of(), "查询返回 0 行，无可分析数据。", "", "", 0);
        }
        String dimension = pickDimension(columns);
        String metric = pickMetric(columns);
        if (dimension == null || metric == null) {
            return new AnalysisResult(
                    List.of(),
                    "结果集缺少可用于分组或求和的数值列，无法生成聚合图表。",
                    "", "", 0);
        }

        List<ChartSchema.Column> tableColumns = columns.stream()
                .map(name -> new ChartSchema.Column(name, typeOf(name, metric)))
                .toList();
        List<List<Object>> tableData = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<Object> values = new ArrayList<>();
            for (String column : columns) {
                values.add(row.get(column));
            }
            tableData.add(values);
        }
        ChartSchema.TableChart table = new ChartSchema.TableChart(
                "结果数据表", tableColumns, tableData, "query");

        Map<String, BigDecimal> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get(dimension));
            BigDecimal value = toDecimal(row.get(metric));
            grouped.merge(key, value, BigDecimal::add);
        }
        List<String> categories = new ArrayList<>(grouped.keySet());
        List<Number> values = new ArrayList<>(grouped.values());

        ChartSchema.TypedChart chart = switch (normalizeChartType(columns, dimension)) {
            case "line" -> new ChartSchema.LineChart(
                    "按 " + dimension + " 汇总 " + metric, dimension, metric, categories, values, "query");
            case "pie" -> new ChartSchema.PieChart(
                    metric + " 分布", categories, values, "query");
            default -> new ChartSchema.BarChart(
                    "按 " + dimension + " 汇总 " + metric, dimension, metric, categories, values, "query");
        };

        String summary = "共 " + rows.size() + " 行，按「" + dimension + "」分组对「" + metric
                + "」求和，得到 " + categories.size() + " 组；最大值 "
                + categories.get(maxIndex(values)) + " = " + values.get(maxIndex(values)) + "。";
        return new AnalysisResult(List.of(table, chart), summary, "", "", 0);
    }

    private static String pickDimension(List<String> columns) {
        for (String column : columns) {
            if (isDimensionName(column)) {
                return column;
            }
        }
        // 退而求其次：首个非数值列；都没有则用第一列。
        for (String column : columns) {
            if (!isMetricName(column)) {
                return column;
            }
        }
        return columns.isEmpty() ? null : columns.get(0);
    }

    private static String pickMetric(List<String> columns) {
        for (String column : columns) {
            if (isMetricName(column)) {
                return column;
            }
        }
        return null;
    }

    private static boolean isDimensionName(String column) {
        String lower = column.toLowerCase();
        return lower.contains("name") || lower.contains("city") || lower.contains("status")
                || lower.contains("month") || lower.contains("date") || lower.contains("category")
                || lower.contains("type") || lower.contains("sku") || lower.equals("id");
    }

    private static boolean isMetricName(String column) {
        String lower = column.toLowerCase();
        return lower.contains("amount") || lower.contains("quantity") || lower.contains("price")
                || lower.contains("views") || lower.contains("count") || lower.contains("total")
                || lower.contains("sum") || lower.contains("value");
    }

    private static String normalizeChartType(List<String> columns, String dimension) {
        String lower = dimension.toLowerCase();
        if (lower.contains("month") || lower.contains("date") || lower.contains("time")) {
            return "line";
        }
        return "bar";
    }

    private static String typeOf(String column, String metric) {
        return column.equals(metric) ? "number" : "string";
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static int maxIndex(List<Number> values) {
        int index = 0;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i).doubleValue() > values.get(index).doubleValue()) {
                index = i;
            }
        }
        return index;
    }
}
