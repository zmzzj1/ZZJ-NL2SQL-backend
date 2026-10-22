package com.queryforge.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.analysis.AnalysisEngine.AnalysisResult;
import com.queryforge.domain.analysis.AnalysisExecutionException;
import com.queryforge.domain.analysis.AnalysisProperties;
import com.queryforge.domain.analysis.ChartSchema;
import com.zzj.sandbox.DockerPythonSandboxExecutor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P14-C 真实沙箱 E2E（引擎层）：{@link SandboxPythonEngine} + 真实 {@link DockerPythonSandboxExecutor}。
 *
 * <p>与 {@link SandboxPythonEngineTest}（Mock 编排语义）互补：本类要求 Docker 守护进程可用，
 * 断言的是「模型代码在真实隔离容器内执行 → /output/output.json 被 P14-C 的
 * {charts,summary} 包装契约解析 → validateCharts 严格校验」这一真实闭环。
 * Docker 不可用时如实跳过，绝不用桩冒充真实执行结论。</p>
 */
class SandboxPythonEngineRealP14CTest {

    private static final String IMAGE = "python:3.12-slim";
    private static final List<String> COLUMNS = List.of("region", "amount");
    private static final List<Map<String, Object>> ROWS = List.of(
            Map.of("region", "华东", "amount", 120),
            Map.of("region", "华南", "amount", 80),
            Map.of("region", "华东", "amount", 30));

    private static SandboxPythonEngine engine;

    @BeforeAll
    static void requireDocker() {
        engine = new SandboxPythonEngine(
                new AnalysisProperties(200, 256 * 1024, 4000, 20, 256L * 1024 * 1024, 1.0, IMAGE, "python"),
                new ObjectMapper(),
                new DockerPythonSandboxExecutor());
        assumeTrue(engine.available(), "仅在 Docker 守护进程可用时执行真实容器验证");
    }

    @Test
    @DisplayName("真实容器：读 /work/input.json 聚合真实行 → 写 /output/output.json 的 {charts,summary}，被 P14-C 包装契项正确解析")
    void realContainer_readsInputAndWritesWrappedOutput() throws Exception {
        String code = """
                import json
                payload = json.load(open('/work/input.json'))
                rows = payload['rows']
                agg = {}
                for row in rows:
                    agg[row['region']] = agg.get(row['region'], 0) + float(row['amount'])
                cats = sorted(agg)
                out = {
                    'charts': [{
                        'chartType': 'bar',
                        'title': '地区销售额',
                        'xLabel': '地区',
                        'yLabel': '销售额',
                        'categories': cats,
                        'values': [agg[c] for c in cats],
                        'sourceRunId': '77'
                    }],
                    'summary': '共 %d 个地区，合计 %.0f' % (len(cats), sum(agg.values()))
                }
                open('/output/output.json', 'w').write(json.dumps(out, ensure_ascii=False))
                """;

        AnalysisResult result = engine.run(code, COLUMNS, ROWS);

        assertThat(result.exitCode()).isZero();
        assertThat(result.charts()).hasSize(1);
        ChartSchema.BarChart bar = (ChartSchema.BarChart) result.charts().get(0);
        assertThat(bar.categories()).containsExactly("华东", "华南");
        assertThat(bar.values()).containsExactly(150.0, 80.0);
        assertThat(bar.sourceRunId()).isEqualTo("77");
        // 摘要来自真实容器内计算，不是宿主机伪造。
        assertThat(result.summary()).contains("共 2 个地区").contains("230");
    }

    @Test
    @DisplayName("真实容器：table 图契约（列定义 + 行列一致 + sourceRunId）被接受")
    void realContainer_tableChartAccepted() throws Exception {
        String code = """
                import json
                payload = json.load(open('/work/input.json'))
                rows = payload['rows']
                out = {
                    'charts': [{
                        'chartType': 'table',
                        'title': '明细',
                        'columns': [{'name': 'region', 'dataType': 'string'},
                                    {'name': 'amount', 'dataType': 'number'}],
                        'data': [[r['region'], r['amount']] for r in rows],
                        'sourceRunId': '77'
                    }],
                    'summary': '明细 %d 行' % len(rows)
                }
                open('/output/output.json', 'w').write(json.dumps(out, ensure_ascii=False))
                """;

        AnalysisResult result = engine.run(code, COLUMNS, ROWS);

        assertThat(result.exitCode()).isZero();
        assertThat(result.charts()).singleElement().isInstanceOf(ChartSchema.TableChart.class);
        ChartSchema.TableChart table = (ChartSchema.TableChart) result.charts().get(0);
        assertThat(table.columns()).hasSize(2);
        assertThat(table.data()).hasSize(3).allSatisfy(row -> assertThat(row).hasSize(2));
    }

    @Test
    @DisplayName("真实容器：类目与数值数量不一致的真实 output.json 被 validateCharts 拒绝（不伪装成功）")
    void realContainer_mismatchedSeriesRejected() {
        String code = """
                import json
                out = {
                    'charts': [{
                        'chartType': 'bar', 'title': 't', 'xLabel': 'x', 'yLabel': 'y',
                        'categories': ['a', 'b', 'c'], 'values': [1, 2], 'sourceRunId': '77'
                    }],
                    'summary': 's'
                }
                open('/output/output.json', 'w').write(json.dumps(out))
                """;

        assertThatThrownBy(() -> engine.run(code, COLUMNS, ROWS))
                .isInstanceOf(AnalysisExecutionException.class)
                .hasMessageContaining("类目与数值数量不一致");
    }

    @Test
    @DisplayName("真实容器：必填字段缺失的真实 output.json 被拒绝（不补全兜底）")
    void realContainer_missingRequiredFieldRejected() {
        String code = """
                import json
                out = {'charts': [{'chartType': 'bar', 'title': 't', 'xLabel': 'x',
                                   'categories': ['a'], 'values': [1], 'sourceRunId': '77'}],
                       'summary': 's'}
                open('/output/output.json', 'w').write(json.dumps(out))
                """;

        assertThatThrownBy(() -> engine.run(code, COLUMNS, ROWS))
                .isInstanceOf(AnalysisExecutionException.class)
                .hasMessageContaining("缺少必填字段").hasMessageContaining("yLabel");
    }

    @Test
    @DisplayName("真实容器：代码抛异常（非零退出）如实返回失败，不产生图表、不冒充成功")
    void realContainer_nonZeroExitReturnsFailureWithoutCharts() throws Exception {
        String code = """
                raise RuntimeError('boom-from-container')
                """;

        AnalysisResult result = engine.run(code, COLUMNS, ROWS);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.charts()).isEmpty();
        assertThat(result.summary()).contains("Python 执行失败");
        assertThat(result.stderrTail()).contains("boom-from-container");
    }

    @Test
    @DisplayName("真实容器：退出 0 但未写 output.json → 明确失败（无结构化输出）")
    void realContainer_missingStructuredOutputRejected() {
        String code = """
                print('no structured output')
                """;

        assertThatThrownBy(() -> engine.run(code, COLUMNS, ROWS))
                .isInstanceOf(AnalysisExecutionException.class)
                .hasMessageContaining("未产生结构化输出");
    }

    @Test
    @DisplayName("真实容器：超时被强制终止并映射为 ANALYSIS 执行异常（不返回部分结果）")
    void realContainer_timeoutIsKilled() {
        SandboxPythonEngine shortTimeout = new SandboxPythonEngine(
                new AnalysisProperties(200, 256 * 1024, 4000, 3, 256L * 1024 * 1024, 1.0, IMAGE, "python"),
                new ObjectMapper(),
                new DockerPythonSandboxExecutor());

        assertThatThrownBy(() -> shortTimeout.run("import time\ntime.sleep(60)\n", COLUMNS, ROWS))
                .isInstanceOf(AnalysisExecutionException.class)
                .hasMessageContaining("超时");
    }

    @Test
    @DisplayName("真实容器：P14-C 文件契约——/work 只读、无网络、非 root，但仍能正常完成分析")
    void realContainer_fileContractAndIsolation() throws Exception {
        String code = """
                import json, os, socket
                probe = {}
                probe['uid'] = os.getuid()
                try:
                    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    s.settimeout(3)
                    s.connect(('8.8.8.8', 53))
                    s.close()
                    probe['network'] = 'open'
                except Exception:
                    probe['network'] = 'blocked'
                try:
                    open('/work/_w', 'w').close()
                    probe['input_writable'] = True
                except Exception:
                    probe['input_writable'] = False
                payload = json.load(open('/work/input.json'))
                rows = payload['rows']
                agg = {}
                for row in rows:
                    agg[row['region']] = agg.get(row['region'], 0) + float(row['amount'])
                cats = sorted(agg)
                out = {'charts': [{'chartType': 'pie', 'title': '占比',
                                   'labels': cats, 'values': [agg[c] for c in cats],
                                   'sourceRunId': '77'}],
                       'summary': 'probe'}
                open('/output/output.json', 'w').write(json.dumps(out, ensure_ascii=False))
                print('PROBE=' + json.dumps(probe))
                """;

        AnalysisResult result = engine.run(code, COLUMNS, ROWS);

        assertThat(result.exitCode()).isZero();
        assertThat(result.charts()).singleElement().isInstanceOf(ChartSchema.PieChart.class);
        String stdout = result.stdoutTail();
        assertThat(stdout).contains("\"uid\": 65534");
        assertThat(stdout).contains("\"network\": \"blocked\"");
        assertThat(stdout).contains("\"input_writable\": false");
    }
}
