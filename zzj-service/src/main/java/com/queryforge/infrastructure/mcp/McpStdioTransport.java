package com.queryforge.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.queryforge.domain.mcp.McpTool;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 长效 MCP stdio 传输：启动一个受信命令进程并保持，复用同一进程完成多次 JSON-RPC 调用。
 *
 * <p>与一次性 {@code McpStdioClient} 不同，本类维持连接，使“已连接”成为可被管理的运行时事实。
 * 读取由独立后台线程完成，按 JSON-RPC id 将响应分发给对应的等待方，保证并发/交错调用
 * 不会错配结果。进程命令采用 {@code ProcessBuilder} 参数数组，禁止 shell 拼接；子进程
 * 退出或异常时失败所有挂起请求；{@link #close()} 确定性销毁进程。</p>
 */
public class McpStdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpStdioTransport.class);
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final List<String> command;
    private final Map<String, String> envMeta;
    private final ObjectMapper objectMapper;
    private final long timeoutMs;

    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final AtomicLong requestId = new AtomicLong(0);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private Thread readerThread;
    private volatile boolean closed = false;

    public McpStdioTransport(List<String> command, Map<String, String> envMeta, ObjectMapper objectMapper) {
        this(command, envMeta, objectMapper, DEFAULT_TIMEOUT_MS);
    }

    public McpStdioTransport(List<String> command, Map<String, String> envMeta, ObjectMapper objectMapper,
                             long timeoutMs) {
        this.command = command;
        this.envMeta = envMeta == null ? Map.of() : envMeta;
        this.objectMapper = objectMapper;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void connect() throws IOException {
        if (closed) {
            throw new IOException("MCP stdio 传输已关闭");
        }
        if (command == null || command.isEmpty() || command.get(0) == null || command.get(0).isBlank()) {
            throw new IOException("MCP stdio 命令为空，拒绝启动");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(envMeta);
        builder.redirectErrorStream(false);
        this.process = builder.start();
        this.reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.readerThread = new Thread(this::pump, "mcp-stdio-reader-" + requestId.get());
        this.readerThread.setDaemon(true);
        this.readerThread.start();
        initialize();
    }

    private void pump() {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (IOException parseError) {
                    continue; // 忽略无法解析的行（如 stderr 误入）
                }
                JsonNode idNode = node.get("id");
                if (idNode == null || idNode.isNull()) {
                    continue; // 通知（无 id）：忽略
                }
                Long id = idNode.asLong();
                CompletableFuture<JsonNode> future = pending.remove(id);
                if (future != null) {
                    future.complete(node);
                }
            }
        } catch (IOException ex) {
            if (!closed) {
                log.debug("mcp_stdio_reader_ended message={}", ex.getMessage());
            }
        } finally {
            failAllPending(new IOException("MCP stdio 读取线程结束"));
        }
    }

    private void initialize() throws IOException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId.incrementAndGet());
        request.put("method", "initialize");
        ObjectNode params = request.putObject("params");
        params.put("protocolVersion", "2024-11-05");
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "queryforge").put("version", "0.1.0");
        JsonNode response = sendAndAwait(request);
        if (response.hasNonNull("error")) {
            throw new IOException("MCP initialize 失败：" + response.path("error"));
        }
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        write(notification);
    }

    @Override
    public List<McpTool> listTools() throws IOException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId.incrementAndGet());
        request.put("method", "tools/list");
        JsonNode response = sendAndAwait(request);
        if (response.hasNonNull("error")) {
            throw new IOException("MCP tools/list 失败：" + response.path("error"));
        }
        List<McpTool> tools = new ArrayList<>();
        for (JsonNode tool : response.path("result").path("tools")) {
            tools.add(new McpTool(
                    tool.path("name").asText(),
                    tool.path("description").asText(""),
                    objectMapper.convertValue(tool.path("inputSchema"), Map.class)));
        }
        return tools;
    }

    @Override
    public String callTool(String name, Map<String, Object> arguments) throws IOException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId.incrementAndGet());
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", name);
        params.set("arguments", objectMapper.valueToTree(arguments == null ? Map.of() : arguments));
        JsonNode response = sendAndAwait(request);
        if (response.hasNonNull("error")) {
            throw new IOException("MCP tools/call 失败：" + response.path("error"));
        }
        JsonNode content = response.path("result").path("content");
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : content) {
                if (item.path("type").asText("").equals("text")) {
                    builder.append(item.path("text").asText());
                }
            }
            return builder.toString();
        }
        if (content.isTextual()) {
            return content.asText();
        }
        return content.toString();
    }

    private JsonNode sendAndAwait(ObjectNode request) throws IOException {
        if (closed || process == null) {
            throw new IOException("MCP stdio 传输未连接或已关闭");
        }
        long id = request.path("id").asLong();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            write(request);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            pending.remove(id);
            throw new IOException("MCP 调用超时（" + timeoutMs + "ms）");
        } catch (InterruptedException ex) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new IOException("MCP 调用被中断");
        } catch (java.util.concurrent.ExecutionException ex) {
            pending.remove(id);
            Throwable cause = ex.getCause();
            throw new IOException("MCP 调用失败：" + (cause == null ? ex.getMessage() : cause.getMessage()));
        }
    }

    private void write(JsonNode node) throws IOException {
        if (writer == null) {
            throw new IOException("MCP stdio 写入流不可用");
        }
        writer.write(objectMapper.writeValueAsString(node));
        writer.write("\n");
        writer.flush();
    }

    private void failAllPending(IOException error) {
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(error);
        }
        pending.clear();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        failAllPending(new IOException("MCP stdio 传输已关闭"));
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        }
        if (process != null) {
            process.destroy();
        }
    }
}
