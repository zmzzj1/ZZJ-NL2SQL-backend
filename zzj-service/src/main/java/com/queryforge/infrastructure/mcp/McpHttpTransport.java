package com.queryforge.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.queryforge.domain.mcp.McpTool;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 远程 HTTP/SSE 型 MCP 传输：向 endpoint 发送 JSON-RPC POST，接收 JSON 或
 * {@code text/event-stream} 的结果体。对事件流必须按 {@code data:} 事件提取合法
 * JSON-RPC 响应，而非把整个事件流字符串误作工具内容。
 *
 * <p>SSRF 防护由 {McpTransportFactory} 在创建前完成（拒绝内网/loopback/metadata/未受信 URL）；
 * 本类只负责协议级 Content-Type、超时与返回码校验，以及 HTTP 非 2xx / malformed
 * JSON-RPC / MCP {@code isError} 的正确分类。连接为无状态请求-响应，{@link #close()}
 * 不保留任何外部资源。</p>
 */
public class McpHttpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpHttpTransport.class);
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final String endpoint;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final long timeoutMs;
    private final AtomicLong requestId = new AtomicLong(0);

    public McpHttpTransport(String endpoint, ObjectMapper objectMapper) {
        this(endpoint, objectMapper, DEFAULT_TIMEOUT_MS);
    }

    public McpHttpTransport(String endpoint, ObjectMapper objectMapper, long timeoutMs) {
        this.endpoint = endpoint;
        this.objectMapper = objectMapper;
        this.timeoutMs = timeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Override
    public void connect() throws IOException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId.incrementAndGet());
        request.put("method", "initialize");
        ObjectNode params = request.putObject("params");
        params.put("protocolVersion", "2024-11-05");
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "queryforge").put("version", "0.1.0");
        JsonNode response = postRpc(request);
        if (response.hasNonNull("error")) {
            throw new IOException("MCP HTTP initialize 失败：" + response.path("error"));
        }
    }

    @Override
    public List<McpTool> listTools() throws IOException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId.incrementAndGet());
        request.put("method", "tools/list");
        JsonNode response = postRpc(request);
        if (response.hasNonNull("error")) {
            throw new IOException("MCP HTTP tools/list 失败：" + response.path("error"));
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
        JsonNode response = postRpc(request);
        if (response.hasNonNull("error")) {
            throw new IOException("MCP HTTP tools/call 失败：" + response.path("error"));
        }
        JsonNode result = response.path("result");
        if (result.path("isError").asBoolean(false)) {
            throw new IOException("MCP HTTP tools/call 业务错误：" + result.path("content"));
        }
        JsonNode content = result.path("content");
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

    private JsonNode postRpc(ObjectNode request) throws IOException {
        String body = objectMapper.writeValueAsString(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP HTTP 请求被中断");
        } catch (IOException ex) {
            throw new IOException("MCP HTTP 连接失败：" + ex.getMessage());
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("MCP HTTP 返回非 2xx 状态码：" + status);
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
        String raw = readBody(response.body(), contentType);
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IOException("MCP HTTP 返回非合法 JSON-RPC：" + raw);
        }
    }

    private String readBody(InputStream stream, String contentType) throws IOException {
        if (contentType.contains("text/event-stream")) {
            JsonNode lastJson = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder dataBuffer = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).strip();
                        if (!data.isEmpty()) {
                            dataBuffer.setLength(0);
                            dataBuffer.append(data);
                        }
                    } else if (line.isEmpty()) {
                        if (dataBuffer.length() > 0) {
                            try {
                                JsonNode node = objectMapper.readTree(dataBuffer.toString());
                                if (node.hasNonNull("id") || node.hasNonNull("result") || node.hasNonNull("error")) {
                                    lastJson = node;
                                }
                            } catch (Exception ignored) {
                                // 跳过非法 data 事件
                            }
                            dataBuffer.setLength(0);
                        }
                    }
                }
            }
            if (lastJson != null) {
                return objectMapper.writeValueAsString(lastJson);
            }
            throw new IOException("MCP SSE 响应中未找到合法 JSON-RPC data 事件");
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, n);
            }
        }
        return sb.toString();
    }

    @Override
    public void close() {
        // HttpClient 为无状态、可共享资源，无需显式关闭；保持 close 幂等安全。
    }
}
