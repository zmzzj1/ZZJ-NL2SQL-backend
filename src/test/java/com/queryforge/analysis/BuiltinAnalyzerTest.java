package com.queryforge.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("内置分析器（确定性聚合）")
class BuiltinAnalyzerTest {

    private final BuiltinAnalyzer analyzer = new BuiltinAnalyzer();

    private static List<Map<String, Object>> orders() {
        return List.of(
                Map.of("month", "2026-08", "amount", 120.00),
                Map.of("month", "2026-09", "amount", 280.49),
                Map.of("month", "2026-08", "amount", 80.50));
    }

    @Test
    @DisplayName("对数值列做分组求和，产出真实图表数据")
    void run_shouldAggregateNumericColumn() throws AnalysisExecutionException {
        AnalysisEngine.AnalysisResult result = analyzer.run(
                "", List.of("month", "amount"), orders());
        assertThat(result.charts()).isNotEmpty();
        assertThat(result.charts().stream().anyMatch(c -> c instanceof ChartSchema.BarChart
                || c instanceof ChartSchema.LineChart)).isTrue();
        // 2026-08 组 = 120 + 80.50 = 200.50；2026-09 组 = 280.49
        assertThat(result.summary()).contains("280.49");
    }

    @Test
    @DisplayName("空结果集返回无数据结论，不产生图表")
    void run_shouldReturnEmptyForNoRows() throws AnalysisExecutionException {
        AnalysisEngine.AnalysisResult result = analyzer.run("", List.of("month", "amount"), List.of());
        assertThat(result.charts()).isEmpty();
        assertThat(result.summary()).contains("0 行");
    }

    @Test
    @DisplayName("缺少数值列时不产生图表")
    void run_shouldReturnNoChartWithoutMetric() throws AnalysisExecutionException {
        AnalysisEngine.AnalysisResult result = analyzer.run(
                "", List.of("name", "city"),
                List.of(Map.of("name", "a", "city", "b")));
        assertThat(result.charts()).isEmpty();
    }

    @Test
    @DisplayName("内置分析器始终可用")
    void available_shouldBeTrue() {
        assertThat(analyzer.available()).isTrue();
        assertThat(analyzer.name()).isEqualTo("BuiltinAnalyzer");
    }
}
