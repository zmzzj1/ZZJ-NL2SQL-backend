package com.zzj.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 定向验证 {@link DockerPythonSandboxExecutor} 的安全命令结构与失败关闭行为。
 * 真实容器执行仅在 Docker 守护进程可用时进行（不可用时相关用例如实跳过，不伪造结果）。
 */
class DockerPythonSandboxExecutorTest {

    private final DockerPythonSandboxExecutor executor = new DockerPythonSandboxExecutor();

    @Test
    @DisplayName("buildCommand：network none / 非 root / 只读根 / 去 capabilities / no-new-privileges / 限额 / 输入只读、输出独立可写 / 唯一容器名")
    void buildCommand_enforcesIsolation() {
        SandboxLimits limits = new SandboxLimits(
                "python:3.12-slim", "python", 20, 256L * 1024 * 1024, 1.0, 64, 64 * 1024, 1024 * 1024);
        List<String> command = executor.buildCommand(
                Path.of("/host/input"), Path.of("/host/output"), limits, "zzj-sbx-test");

        // 参数数组形式（不经 shell）。
        assertThat(command).startsWith("docker", "run");
        assertThat(command).doesNotContain("sh", "bash", "-c");

        // 唯一容器名：保证可按名强制移除（超时/取消后清理）。
        assertThat(indexValue(command, "--name")).isEqualTo("zzj-sbx-test");

        // 隔离与降权。
        assertThat(indexValue(command, "--network")).isEqualTo("none");
        assertThat(indexValue(command, "--user")).isEqualTo("65534:65534");
        assertThat(command).contains("--read-only");
        assertThat(indexValue(command, "--cap-drop")).isEqualTo("ALL");
        assertThat(indexValue(command, "--security-opt")).isEqualTo("no-new-privileges");

        // 资源/进程限额。
        assertThat(indexValue(command, "--memory")).isEqualTo(String.valueOf(limits.memoryBytes()));
        assertThat(indexValue(command, "--memory-swap")).isEqualTo(String.valueOf(limits.memoryBytes()));
        assertThat(indexValue(command, "--pids-limit")).isEqualTo("64");
        assertThat(command).contains("--cpus");

        // 输入目录只读挂载，结构化输出走独立可写目录（不取消隔离、不给输入开放写权限）。
        // 容器侧目标固定；宿主侧前缀因平台而异（Windows 为 C:\...，Docker Desktop 自动翻译），故只断言容器侧与读写属性。
        List<String> mounts = collectFlagValues(command, "-v");
        assertThat(mounts).anySatisfy(m -> assertThat(m).endsWith(":/work:ro").contains("input"));
        assertThat(mounts).anySatisfy(m -> assertThat(m).endsWith(":/output:rw").contains("output"));

        // 源码经文件进入，不拼进命令行；入口为只读区的 main.py。
        assertThat(command.get(command.size() - 1)).isEqualTo("/work/main.py");
        assertThat(String.join(" ", command)).doesNotContain("print(");
    }

    @Test
    @DisplayName("execute：隔离运行时不可用时失败关闭（明确拒绝，不回退宿主机）")
    void execute_whenUnavailable_failsClosed() {
        assumeTrue(!executor.available(), "本机 Docker 不可用才可确定性地验证失败关闭");
        assertThatThrownBy(() -> executor.execute(
                new SandboxRequest("print(1)", "{}"),
                new SandboxLimits("python:3.12-slim", "python", 5, 256L * 1024 * 1024, 1.0, 64, 1024, 1024)))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("Docker 未就绪");
    }

    @Test
    @DisplayName("结构化输出超限时只读取配置上限，不把整个文件装入宿主机内存")
    void readStructuredOutput_readsAtMostConfiguredCap() throws Exception {
        Path output = Files.createTempFile("sandbox-output-limit-", ".json");
        try {
            Files.writeString(output, "x".repeat(8192), StandardCharsets.UTF_8);

            String result = executor.readStructuredOutput(output, 1024);

            assertThat(result.getBytes(StandardCharsets.UTF_8)).hasSize(1024);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    @DisplayName("真实执行：最小 Python 分析产生真实结构化输出，并清理临时目录与容器")
    void execute_realAnalysis_producesStructuredOutputAndCleansUp() throws Exception {
        assumeTrue(executor.available(), "仅在 Docker 守护进程可用时执行真实容器验证");
        Set<String> tmpBefore = sandboxTempDirs();
        Set<String> cidBefore = dockerContainerIds();

        SandboxRequest request = new SandboxRequest(
                "import json\n"
                        + "data={'charts':[{'chartType':'table','title':'t',"
                        + "'columns':[{'name':'c','dataType':'string'}],'data':[['ok']],'sourceRunId':'r'}],"
                        + "'summary':'ok'}\n"
                        + "open('/output/output.json','w').write(json.dumps(data))\n",
                "{}");
        SandboxLimits limits = new SandboxLimits(
                "python:3.12-slim", "python", 30, 256L * 1024 * 1024, 1.0, 64, 64 * 1024, 1024 * 1024);

        SandboxResult result = executor.execute(request, limits);

        assertThat(result.exitCode()).isZero();
        assertThat(result.errorCategory()).isEqualTo(SandboxErrorCategory.NONE);
        assertThat(result.structuredOutput()).contains("table", "ok");

        Set<String> tmpLeftover = sandboxTempDirs();
        tmpLeftover.removeAll(tmpBefore);
        assertThat(tmpLeftover).as("执行后不留 zzj-sandbox-* 临时目录").isEmpty();

        Set<String> cidLeftover = dockerContainerIds();
        cidLeftover.removeAll(cidBefore);
        assertThat(cidLeftover).as("执行后不留新容器").isEmpty();
    }

    @Test
    @DisplayName("真实执行：危险行为无法越界——非 root / 网络关闭 / 输入与根只读 / 仅输出可写")
    void execute_securityProbes_cannotEscapeSandbox() throws Exception {
        assumeTrue(executor.available(), "仅在 Docker 守护进程可用时执行真实容器验证");
        SandboxRequest request = new SandboxRequest(
                "import os, json, socket\n"
                        + "r = {}\n"
                        + "r['uid'] = os.getuid()\n"
                        + "try:\n"
                        + "    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM); s.settimeout(3)\n"
                        + "    s.connect(('8.8.8.8', 53)); s.close(); r['network'] = 'open'\n"
                        + "except Exception:\n"
                        + "    r['network'] = 'blocked'\n"
                        + "try:\n"
                        + "    open('/output/_w', 'w').close(); r['output_writable'] = True\n"
                        + "except Exception:\n"
                        + "    r['output_writable'] = False\n"
                        + "try:\n"
                        + "    open('/work/_w', 'w').close(); r['input_writable'] = True\n"
                        + "except Exception:\n"
                        + "    r['input_writable'] = False\n"
                        + "try:\n"
                        + "    open('/_w_root', 'w').close(); r['root_writable'] = True\n"
                        + "except Exception:\n"
                        + "    r['root_writable'] = False\n"
                        + "open('/output/output.json','w').write(json.dumps(r))\n",
                "{}");
        SandboxLimits limits = new SandboxLimits(
                "python:3.12-slim", "python", 30, 256L * 1024 * 1024, 1.0, 64, 64 * 1024, 1024 * 1024);

        SandboxResult result = executor.execute(request, limits);

        assertThat(result.exitCode()).isZero();
        String out = result.structuredOutput();
        assertThat(out).contains("65534");                     // 非 root
        assertThat(out).contains("\"network\": \"blocked\"");   // 网络关闭
        assertThat(out).contains("\"output_writable\": true");  // 仅输出可写
        assertThat(out).contains("\"input_writable\": false");  // 输入目录只读
        assertThat(out).contains("\"root_writable\": false");   // 容器根只读
    }

    @Test
    @DisplayName("真实执行：超时被强制终止（TIMEOUT），且容器与临时目录均被清理")
    void execute_timeout_isKilledAndCleansUp() throws Exception {
        assumeTrue(executor.available(), "仅在 Docker 守护进程可用时执行真实容器验证");
        Set<String> tmpBefore = sandboxTempDirs();
        Set<String> cidBefore = dockerContainerIds();

        SandboxRequest request = new SandboxRequest("while True:\n    pass\n", "{}");
        SandboxLimits limits = new SandboxLimits(
                "python:3.12-slim", "python", 3, 256L * 1024 * 1024, 1.0, 64, 64 * 1024, 1024 * 1024);

        SandboxResult result = executor.execute(request, limits);

        assertThat(result.errorCategory()).isEqualTo(SandboxErrorCategory.TIMEOUT);
        assertThat(result.exitCode()).isEqualTo(-1);

        // 前台 docker run 仅销毁 CLI 不会停容器；执行器须按名 docker rm -f 兜底清理。
        Set<String> cidLeftover = waitForNoNewContainers(cidBefore, 8000);
        assertThat(cidLeftover).as("超时后容器被强制清理").isEmpty();

        Set<String> tmpLeftover = sandboxTempDirs();
        tmpLeftover.removeAll(tmpBefore);
        assertThat(tmpLeftover).as("超时后临时目录被清理").isEmpty();
    }

    @Test
    @DisplayName("真实执行：stdout 输出大小上限生效，且大输出不因管道互阻而死锁")
    void execute_stdoutSizeLimit_isEnforced_noDeadlock() throws Exception {
        assumeTrue(executor.available(), "仅在 Docker 守护进程可用时执行真实容器验证");
        SandboxRequest request = new SandboxRequest(
                "import sys, json\n"
                        + "sys.stdout.write('x' * 300000)\n"
                        + "sys.stdout.flush()\n"
                        + "open('/output/output.json','w').write(json.dumps({'charts':[],'summary':'big'}))\n",
                "{}");
        SandboxLimits limits = new SandboxLimits(
                "python:3.12-slim", "python", 30, 256L * 1024 * 1024, 1.0, 64, 64 * 1024, 1024 * 1024);

        SandboxResult result = executor.execute(request, limits);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdoutTail().length()).isLessThanOrEqualTo(64 * 1024);
        assertThat(result.structuredOutput()).contains("big");
    }

    @Test
    @DisplayName("真实执行：内存上限生效——超限分配不会正常成功")
    void execute_memoryLimit_isEnforced() throws Exception {
        assumeTrue(executor.available(), "仅在 Docker 守护进程可用时执行真实容器验证");
        SandboxRequest request = new SandboxRequest(
                "b = bytearray(300 * 1024 * 1024)\n"
                        + "open('/output/output.json','w').write('{}')\n",
                "{}");
        SandboxLimits limits = new SandboxLimits(
                "python:3.12-slim", "python", 30, 64L * 1024 * 1024, 1.0, 64, 64 * 1024, 1024 * 1024);

        SandboxResult result = executor.execute(request, limits);

        assertThat(result.exitCode()).isNotZero();
    }

    // ---- 辅助 ----

    private static String indexValue(List<String> command, String flag) {
        int idx = command.indexOf(flag);
        assertThat(idx).as("flag %s present", flag).isGreaterThanOrEqualTo(0);
        return command.get(idx + 1);
    }

    private static List<String> collectFlagValues(List<String> command, String flag) {
        List<String> values = new java.util.ArrayList<>();
        for (int i = 0; i < command.size(); i++) {
            if (flag.equals(command.get(i)) && i + 1 < command.size()) {
                values.add(command.get(i + 1));
            }
        }
        return values;
    }

    private static Set<String> sandboxTempDirs() throws IOException {
        Set<String> names = new HashSet<>();
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var stream = Files.list(tmp)) {
            stream.filter(p -> p.getFileName().toString().startsWith("zzj-sandbox-"))
                    .forEach(p -> names.add(p.getFileName().toString()));
        }
        return names;
    }

    private static Set<String> dockerContainerIds() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "ps", "-aq")
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(15, TimeUnit.SECONDS);
        Set<String> ids = new HashSet<>();
        for (String line : out.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                ids.add(trimmed);
            }
        }
        return ids;
    }

    /** 轮询直到没有“执行前不存在”的新容器残留，或超时。 */
    private static Set<String> waitForNoNewContainers(Set<String> before, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> leftover = new HashSet<>();
        while (System.currentTimeMillis() < deadline) {
            Set<String> after = dockerContainerIds();
            leftover = new HashSet<>(after);
            leftover.removeAll(before);
            if (leftover.isEmpty()) {
                return leftover;
            }
            Thread.sleep(300);
        }
        return leftover;
    }
}
