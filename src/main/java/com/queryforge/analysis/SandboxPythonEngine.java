package com.queryforge.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 隔离 Python 沙箱：模型生成的 Python 代码只在一次性 Docker 容器内执行，
 * 非 root、禁网络、只读根、去 capabilities、内存/CPU/时间/输出限额、一次性临时目录。
 * 控制面不拼接用户参数到 docker 命令（全部通过参数列表传递），不暴露宿主目录/凭据/网络。
 * Docker 守护进程不可用时 available()==false，调用方不得回退到宿主机执行。
 */
@Component
public class SandboxPythonEngine implements AnalysisEngine {

    private static final Logger log = LoggerFactory.getLogger(SandboxPythonEngine.class);
    private static final int MAX_STDOUT_BYTES = 64 * 1024;
    private static final int MAX_TAIL_CHARS = 2000;

    private final AnalysisProperties properties;
    private final ObjectMapper objectMapper;

    public SandboxPythonEngine(AnalysisProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "SandboxPython";
    }

    @Override
    public boolean available() {
        return dockerInfo() != null;
    }

    @Override
    public AnalysisResult run(String code, List<String> columns, List<Map<String, Object>> rows)
            throws AnalysisExecutionException {
        if (!available()) {
            throw new AnalysisExecutionException("隔离沙箱运行时不可用（Docker 未就绪）");
        }
        Path workDir = createWorkDir();
        try {
            Path input = workDir.resolve("input.json");
            Path codeFile = workDir.resolve("main.py");
            Path output = workDir.resolve("output.json");
            writeJson(input, new SandboxInput(columns, rows));
            Files.writeString(codeFile, code, StandardCharsets.UTF_8);

            List<String> command = buildCommand(workDir, input, codeFile, output);
            ProcessResult result = runContainer(command, workDir);

            if (result.exitCode() != 0) {
                return new AnalysisResult(
                        List.of(), "Python 执行失败（退出码 " + result.exitCode() + "）",
                        tail(result.stdout()), tail(result.stderr()), result.exitCode());
            }
            String out = tail(result.stdout());
            if (Files.exists(output)) {
                try {
                    SandboxOutput parsed = objectMapper.readValue(
                            Files.readString(output, StandardCharsets.UTF_8), SandboxOutput.class);
                    return new AnalysisResult(parsed.charts(), parsed.summary(), out, tail(result.stderr()), 0);
                } catch (IOException ex) {
                    throw new AnalysisExecutionException("沙箱输出不是合法的图表 JSON", ex);
                }
            }
            throw new AnalysisExecutionException("沙箱未产生 output.json 结果");
        } catch (IOException ex) {
            throw new AnalysisExecutionException("沙箱临时文件读写失败", ex);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private List<String> buildCommand(Path workDir, Path input, Path codeFile, Path output) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--network", "none",
                "--user", "65534:65534",
                "--read-only",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--memory", String.valueOf(properties.memoryBytes()),
                "--memory-swap", String.valueOf(properties.memoryBytes()),
                "--cpus", String.valueOf(properties.cpus()),
                "--pids-limit", "64",
                "-v", workDir.toAbsolutePath().toString() + ":/work:ro",
                "-w", "/tmp",
                properties.sandboxImage(),
                properties.sandboxCommand(),
                "/work/main.py"));
        return command;
    }

    private ProcessResult runContainer(List<String> command, Path workDir) throws AnalysisExecutionException {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();
            byte[] stdout = process.getInputStream().readNBytes(MAX_STDOUT_BYTES + 1);
            byte[] stderr = process.getErrorStream().readNBytes(MAX_STDOUT_BYTES + 1);
            boolean finished = process.waitFor(properties.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new AnalysisExecutionException("Python 执行超时（" + properties.timeoutSeconds() + " 秒）");
            }
            return new ProcessResult(
                    process.exitValue(),
                    new String(stdout, StandardCharsets.UTF_8),
                    new String(stderr, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new AnalysisExecutionException("无法启动隔离容器", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AnalysisExecutionException("隔离容器执行被中断", ex);
        }
    }

    private String dockerInfo() {
        try {
            Process process = new ProcessBuilder("docker", "info").start();
            process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0 ? "ok" : null;
        } catch (IOException | InterruptedException ex) {
            log.debug("sandbox_docker_unavailable message={}", ex.getMessage());
            return null;
        }
    }

    private Path createWorkDir() throws AnalysisExecutionException {
        try {
            return Files.createTempDirectory("analysis-sandbox-");
        } catch (IOException ex) {
            throw new AnalysisExecutionException("无法创建沙箱临时目录", ex);
        }
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.writeString(path, objectMapper.writeValueAsString(value), StandardCharsets.UTF_8);
    }

    private static String tail(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() > MAX_TAIL_CHARS ? value.substring(0, MAX_TAIL_CHARS) : value;
    }

    private static void deleteQuietly(Path dir) {
        try {
            if (dir != null && Files.exists(dir)) {
                Files.walk(dir)
                        .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                                // 临时目录清理失败不影响主流程
                            }
                        });
            }
        } catch (IOException ignored) {
            // 清理失败忽略
        }
    }

    /** 沙箱输入：受限投影，不含连接凭据、Token 或宿主路径。 */
    public record SandboxInput(List<String> columns, List<Map<String, Object>> rows) {
    }

    /** 沙箱输出：符合图表 schema 的结构化结果。 */
    public record SandboxOutput(List<ChartSchema.TypedChart> charts, String summary) {
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
