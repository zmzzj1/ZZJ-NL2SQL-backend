package com.zzj.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Docker 的受控 Python 执行器：一次性容器，非 root、禁网络、只读根、去 capabilities、
 * no-new-privileges，内存/CPU/进程/时间/输出限额，一次性临时目录并在结束后清理。
 *
 * <p>安全约束：</p>
 * <ul>
 *   <li>结构化输出经<b>独立的受控可写输出目录</b>（{@code /output}，rw）返回，输入目录 {@code /work} 只读，
 *       不取消容器隔离、不给输入目录开放写权限；</li>
 *   <li>stdout/stderr 并发读取，避免管道互相阻塞导致超时/输出限制失效；</li>
 *   <li>Docker 命令全部用参数数组传递，不拼接不可信 shell；</li>
 *   <li>Docker 不可用、超时、输出异常时明确失败，<b>绝不回退宿主机</b>运行模型代码。</li>
 * </ul>
 */
public class DockerPythonSandboxExecutor implements SandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerPythonSandboxExecutor.class);

    private static final String NON_ROOT_UID = "65534:65534";
    private static final String WORK_MOUNT = "/work";
    private static final String OUTPUT_MOUNT = "/output";
    private static final String CODE_FILE = "/work/main.py";
    private static final String INPUT_FILE = "input.json";
    private static final String OUTPUT_FILE = "output.json";
    private static final int DEFAULT_PIDS = 64;
    private static final int DOCKER_PROBE_TIMEOUT_SECONDS = 5;
    private static final int DOCKER_RM_TIMEOUT_SECONDS = 10;
    private static final long ABSOLUTE_MAX_OUTPUT_BYTES = 16L * 1024 * 1024;
    private static final String CONTAINER_NAME_PREFIX = "zzj-sbx-";

    @Override
    public boolean available() {
        return dockerReachable();
    }

    @Override
    public SandboxResult execute(SandboxRequest request, SandboxLimits limits) {
        if (!available()) {
            // 失败关闭：运行时不可用，绝不回退宿主机执行模型代码。
            throw new SandboxException("隔离沙箱运行时不可用（Docker 未就绪）");
        }
        Path workDir = createWorkDir();
        // 唯一容器名：超时仅销毁 docker CLI 客户端不会停止容器，必须能按名强制移除，保证取消/超时后也清理。
        String containerName = CONTAINER_NAME_PREFIX + UUID.randomUUID();
        try {
            // 输入（只读挂载）与输出（独立可写）严格分离。
            Path inputDir = Files.createDirectories(workDir.resolve("input"));
            Path outputDir = Files.createDirectories(workDir.resolve("output"));
            Files.writeString(inputDir.resolve(INPUT_FILE), request.inputJson(), StandardCharsets.UTF_8);
            Files.writeString(inputDir.resolve("main.py"), request.code(), StandardCharsets.UTF_8);

            List<String> command = buildCommand(inputDir, outputDir, limits, containerName);
            return runContainer(command, limits, outputDir.resolve(OUTPUT_FILE));
        } catch (IOException ex) {
            throw new SandboxException("沙箱临时文件读写失败", ex);
        } finally {
            // 始终兜底移除容器（成功路径 --rm 已删，此处为幂等 no-op；超时/异常路径确保不留残留容器）。
            removeContainer(containerName);
            deleteQuietly(workDir);
        }
    }

    /**
     * 组装 docker 命令。全部经参数数组传递，不拼接不可信 shell；安全约束集中在此便于断言。
     * 包级可见以供定向测试验证“危险行为无法越界”的命令结构。
     */
    List<String> buildCommand(Path inputDir, Path outputDir, SandboxLimits limits, String containerName) {
        long memory = limits.memoryBytes();
        int pids = limits.pidsLimit() <= 0 ? DEFAULT_PIDS : limits.pidsLimit();
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--name", containerName,
                "--network", "none",
                "--user", NON_ROOT_UID,
                "--read-only",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--memory", String.valueOf(memory),
                "--memory-swap", String.valueOf(memory),
                "--cpus", String.valueOf(limits.cpus()),
                "--pids-limit", String.valueOf(pids),
                "-v", inputDir.toAbsolutePath() + ":" + WORK_MOUNT + ":ro",
                "-v", outputDir.toAbsolutePath() + ":" + OUTPUT_MOUNT + ":rw",
                "-w", OUTPUT_MOUNT,
                limits.image(),
                limits.command(),
                CODE_FILE));
        return command;
    }

    private SandboxResult runContainer(List<String> command, SandboxLimits limits, Path outputFile) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();
            // 并发读 stdout/stderr，避免单流读满后另一流写满缓冲区导致互相阻塞。
            CappedStreamReader stdout = new CappedStreamReader(process.getInputStream(), limits.maxStdoutBytes());
            CappedStreamReader stderr = new CappedStreamReader(process.getErrorStream(), limits.maxStdoutBytes());
            Thread outThread = new Thread(stdout, "sandbox-stdout");
            Thread errThread = new Thread(stderr, "sandbox-stderr");
            outThread.start();
            errThread.start();

            boolean finished = process.waitFor(limits.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                joinQuietly(outThread);
                joinQuietly(errThread);
                // 明确失败：超时不返回伪造成功。
                return new SandboxResult(-1, stdout.snapshot(), stderr.snapshot(), null,
                        SandboxErrorCategory.TIMEOUT);
            }
            joinQuietly(outThread);
            joinQuietly(errThread);

            int exitCode = process.exitValue();
            String structured = readStructuredOutput(outputFile, limits.maxOutputBytes());
            SandboxErrorCategory category = exitCode == 0
                    ? SandboxErrorCategory.NONE
                    : SandboxErrorCategory.EXECUTION_FAILED;
            return new SandboxResult(exitCode, stdout.snapshot(), stderr.snapshot(), structured, category);
        } catch (IOException ex) {
            throw new SandboxException("无法启动隔离容器", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SandboxException("隔离容器执行被中断", ex);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    String readStructuredOutput(Path outputFile, long maxBytes) {
        try {
            if (!Files.exists(outputFile)) {
                return null;
            }
            long effectiveCap = Math.min(Math.max(maxBytes, 1), ABSOLUTE_MAX_OUTPUT_BYTES);
            long size = Files.size(outputFile);
            int readLimit = Math.toIntExact(effectiveCap);
            try (InputStream input = Files.newInputStream(outputFile)) {
                // 只读取 cap + 1 字节判断是否超限，绝不把恶意大文件整体装入宿主机内存。
                byte[] bytes = input.readNBytes(readLimit + 1);
                int take = Math.min(bytes.length, readLimit);
                if (size > effectiveCap || bytes.length > readLimit) {
                    log.warn("sandbox_output_truncated size={} configuredCap={} effectiveCap={}",
                            size, maxBytes, effectiveCap);
                }
                return new String(bytes, 0, take, StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            log.debug("sandbox_output_read_failed message={}", ex.getMessage());
            return null;
        }
    }

    private static boolean dockerReachable() {
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(DOCKER_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            return finished && process.exitValue() == 0;
        } catch (IOException | InterruptedException ex) {
            log.debug("sandbox_docker_unavailable message={}", ex.getMessage());
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * 强制移除容器（幂等）：成功路径 --rm 已删时为无害 no-op；超时/异常路径确保按名清理，避免残留运行中的容器。
     * 前台 {@code docker run} 仅销毁 CLI 客户端不会停止容器，故必须显式移除。
     */
    private static void removeContainer(String containerName) {
        try {
            Process process = new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(DOCKER_RM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("sandbox_container_cleanup name={} message={}", containerName, ex.getMessage());
        }
    }

    private static Path createWorkDir() throws SandboxException {
        try {
            return Files.createTempDirectory("zzj-sandbox-");
        } catch (IOException ex) {
            throw new SandboxException("无法创建沙箱临时目录", ex);
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // 临时目录清理失败不影响主流程
                        }
                    });
        } catch (IOException ignored) {
            // 清理失败忽略
        }
    }

    /**
     * 并发、带上限地读取一条流：达到上限后继续排空（丢弃）以防阻塞写入方，最终只返回受上限约束的摘要。
     */
    private static final class CappedStreamReader implements Runnable {
        private final InputStream in;
        private final long cap;
        private volatile String snapshot = "";

        private CappedStreamReader(InputStream in, long cap) {
            this.in = in;
            this.cap = cap;
        }

        @Override
        public void run() {
            try {
                byte[] buffer = new byte[4096];
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                long kept = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (kept < cap) {
                        int take = (int) Math.min(read, cap - kept);
                        bos.write(buffer, 0, take);
                        kept += take;
                    }
                    // 超过 cap 后继续读取但丢弃，避免写端阻塞。
                }
                snapshot = bos.toString(StandardCharsets.UTF_8);
            } catch (IOException ex) {
                snapshot = "";
            }
        }

        private String snapshot() {
            return snapshot;
        }
    }
}
