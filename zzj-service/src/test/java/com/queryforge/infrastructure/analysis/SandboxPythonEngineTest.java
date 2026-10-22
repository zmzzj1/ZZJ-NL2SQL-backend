package com.queryforge.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P13-M4 定向测试：验证 {@link SandboxPythonEngine} 薄适配器把输入交给新 {@link SandboxExecutor}，
 * 并把合法结构化 JSON 转为原有图表/摘要响应；异常转换明确。使用测试替身，不依赖真实 Docker。
 */
class SandboxPythonEngineTest {

    private final AnalysisProperties properties = new AnalysisProperties(
            100, 256 * 1024, 4000, 20L, 256L * 1024 * 1024, 1.0, "python:3.12-slim", "python");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeExecutor executor = new FakeExecutor();
    private final SandboxPythonEngine engine = new SandboxPythonEngine(properties, objectMapper, executor);

    private static final String VALID_STRUCTURED = "{"
            + "\"charts\":[{\"chartType\":\"table\",\"title\":\"月度统计\","
            + "\"columns\":[{\"name\":\"month\",\"dataType\":\"string\"}],"
            + "\"data\":[[\"2026-01\"]],\"sourceRunId\":\"run-1\"}],"
            + "\"summary\":\"共 1 行\"}";

    @Test
    @DisplayName("available() 委托给 SandboxExecutor")
    void available_delegatesToExecutor() {
        executor.available = true;
        assertThat(engine.available()).isTrue();
        executor.available = false;
        assertThat(engine.available()).isFalse();
    }

    @Test
    @DisplayName("成功：把受限源码+输入交给 Executor，并把合法结构化 JSON 转为图表/摘要响应")
    void run_success_convertsStructuredJson() throws Exception {
        executor.available = true;
        executor.toReturn = new SandboxResult(0, "hello-stdout", "", VALID_STRUCTURED, SandboxErrorCategory.NONE);

        AnalysisResult result = engine.run(
                "print('hello')", List.of("month"), List.of(Map.of("month", "2026-01")));

        // 输入交接：受限源码 + 受控输入 JSON（含列名）。
        SandboxRequest captured = executor.lastRequest;
        assertThat(captured).isNotNull();
        assertThat(captured.code()).isEqualTo("print('hello')");
        assertThat(captured.inputJson()).contains("month");

        // 结构化 JSON → 原有图表/摘要协议。
        assertThat(result.exitCode()).isZero();
        assertThat(result.summary()).isEqualTo("共 1 行");
        assertThat(result.stdoutTail()).isEqualTo("hello-stdout");
        assertThat(result.charts()).hasSize(1);
        assertThat(result.charts().get(0)).isInstanceOf(ChartSchema.TableChart.class);
    }

    @Test
    @DisplayName("非零退出：返回失败摘要（空图表），保留退出码，不伪造成功")
    void run_nonZeroExit_returnsFailureResult() throws Exception {
        executor.available = true;
        executor.toReturn = new SandboxResult(2, "out", "boom", null, SandboxErrorCategory.EXECUTION_FAILED);

        AnalysisResult result = engine.run("raise SystemExit(2)", List.of(), List.of());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.charts()).isEmpty();
        assertThat(result.summary()).contains("执行失败");
        assertThat(result.stderrTail()).isEqualTo("boom");
    }

    @Test
    @DisplayName("超时：明确抛 AnalysisExecutionException")
    void run_timeout_throws() {
        executor.available = true;
        executor.toReturn = new SandboxResult(-1, "", "", null, SandboxErrorCategory.TIMEOUT);

        assertThatThrownBy(() -> engine.run("while True: pass", List.of(), List.of()))
                .isInstanceOf(AnalysisExecutionException.class)
                .hasMessageContaining("超时");
    }

    @Test
    @DisplayName("退出 0 但无结构化输出：明确抛异常（不伪造成功）")
    void run_missingStructuredOutput_throws() {
        executor.available = true;
        executor.toReturn = new SandboxResult(0, "out", "", null, SandboxErrorCategory.NONE);

        assertThatThrownBy(() -> engine.run("print('x')", List.of(), List.of()))
                .isInstanceOf(AnalysisExecutionException.class)
                .hasMessageContaining("结构化输出");
    }

    @Test
    @DisplayName("结构化输出非法 JSON：明确抛异常")
    void run_invalidJson_throws() {
        executor.available = true;
        executor.toReturn = new SandboxResult(0, "out", "", "not-json", SandboxErrorCategory.NONE);

        assertThatThrownBy(() -> engine.run("print('x')", List.of(), List.of()))
                .isInstanceOf(AnalysisExecutionException.class)
                .hasMessageContaining("合法的图表 JSON");
    }

    @Test
    @DisplayName("SandboxException：包装为 AnalysisExecutionException 并携带原因")
    void run_sandboxException_wrapped() {
        executor.available = true;
        executor.toThrow = new SandboxException("无法启动隔离容器");

        assertThatThrownBy(() -> engine.run("print('x')", List.of(), List.of()))
                .isInstanceOf(AnalysisExecutionException.class)
                .hasMessageContaining("无法启动隔离容器");
    }

    @Test
    @DisplayName("运行时不可用：拒绝执行，不回退宿主机")
    void run_unavailable_rejects() {
        executor.available = false;

        assertThatThrownBy(() -> engine.run("print('x')", List.of(), List.of()))
                .isInstanceOf(AnalysisExecutionException.class)
                .hasMessageContaining("不可用");
    }

    /** 测试替身：记录交接的请求，按预设返回结果/异常。 */
    private static final class FakeExecutor implements SandboxExecutor {
        private boolean available;
        private SandboxResult toReturn;
        private SandboxException toThrow;
        private SandboxRequest lastRequest;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public SandboxResult execute(SandboxRequest request, SandboxLimits limits) {
            this.lastRequest = request;
            if (toThrow != null) {
                throw toThrow;
            }
            return toReturn;
        }
    }
}
