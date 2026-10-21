package com.queryforge.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.queryforge.domain.mcp.McpServerConfig;
import com.queryforge.domain.mcp.McpTool;

/**
 * 真实 MCP stdio 客户端：实现 JSON-RPC 2.0 over stdio 的 initialize/tools/list/tools/call。
 * 仅连接受信、预配置的本地/测试服务；不提供任意 URL 注册或任意命令执行。
 * 服务进程由本类管理生命周期，调用后关闭。
 */
public class McpStdioClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpStdioClient.class);

    private final McpServerConfig config;
    private final ObjectMapper objectMapper;
    private final Process process;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final AtomicLong requestId = new AtomicLong(0);

    public McpStdioClient(McpServerConfig config, ObjectMapper objectMapper) throws IOException {
        this.config = config;
        this.objectMapper = objectMapper;
        List<String> command = new ArrayList<>();
        command.add(config.command());
        command.addAll(resolveArgs(config.args()));
        this.process = new ProcessBuilder(command).start();
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    /** 握手：initialize + notifications/initialized。 */
    public void initialize() throws IOException {
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
        // 发送 initialized 通知（无 id）
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        write(notification);
    }

    /** tools/list：返回工具名 + 描述 + 输入 schema。 */
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

    /** tools/call：返回 { content: [...] }，把文本内容合并返回。 */
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
        return content.toString();
    }

    private JsonNode sendAndAwait(JsonNode request) throws IOException {
        write(request);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = objectMapper.readTree(line);
            if (node.hasNonNull("id")) {
                return node;
            }
            // 忽略通知（无 id）
        }
        throw new IOException("MCP 服务未返回响应");
    }

    private void write(JsonNode node) throws IOException {
        writer.write(objectMapper.writeValueAsString(node));
        writer.write("\n");
        writer.flush();
    }

    /**
     * 把 args 中的相对文件路径解析为绝对路径，避免依赖 JVM 工作目录。
     * 候选基准依次为：当前 user.dir、user.dir 的上级目录（项目根）、上级的上级。
     * 只有「看起来像文件路径且相对路径存在」的参数才被改写；纯命令参数原样保留。
     */
    private static List<String> resolveArgs(List<String> args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>();
        java.io.File userDir = new java.io.File(System.getProperty("user.dir", "."));
        List<java.io.File> bases = new ArrayList<>();
        bases.add(userDir);
        java.io.File parent = userDir.getParentFile();
        if (parent != null) {
            bases.add(parent);
            if (parent.getParentFile() != null) {
                bases.add(parent.getParentFile());
            }
        }
        for (String arg : args) {
            String replaced = arg;
            if (arg != null && (arg.endsWith(".mjs") || arg.endsWith(".js") || arg.endsWith(".py")
                    || arg.endsWith(".sh") || arg.endsWith(".cmd"))) {
                java.io.File candidate = new java.io.File(arg);
                if (!candidate.isAbsolute()) {
                    for (java.io.File base : bases) {
                        java.io.File abs = new java.io.File(base, arg);
                        if (abs.isFile()) {
                            replaced = abs.getAbsolutePath();
                            break;
                        }
                    }
                }
            }
            resolved.add(replaced);
        }
        return resolved;
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException ignored) {
        }
        process.destroy();
    }
}
