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
                return new AnalysisResult(parsed.charts(), parsed.summary(),
                        tail(result.stdoutTail()), tail(result.stderrTail()), 0);
            } catch (Exception ex) {
                throw new AnalysisExecutionException("沙箱输出不是合法的图表 JSON", ex);
            }
        } catch (SandboxException ex) {
            throw new AnalysisExecutionException(ex.getMessage(), ex.getCause());
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
