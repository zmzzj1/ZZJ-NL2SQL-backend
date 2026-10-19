package com.queryforge.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * MCP 服务接入：仅连接预配置受信服务，完成真实 tools/list + tools/call。
 * 不提供任意 URL/命令注册；工具输出视为不可信数据，只读调用。
 */
@Service
public class McpService {

    private static final Logger log = LoggerFactory.getLogger(McpService.class);

    private final McpProperties properties;
    private final ObjectMapper objectMapper;

    public McpService(McpProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<McpServerView> servers() {
        return properties.servers().stream()
                .map(server -> new McpServerView(server.name(), server.transport(), server.command()))
                .toList();
    }

    /** 测试连接并返回可用工具列表（真实 tools/list）。 */
    public List<McpTool> testServer(String name) {
        McpServerConfig server = requireServer(name);
        try (McpStdioClient client = new McpStdioClient(server, objectMapper)) {
            client.initialize();
            return client.listTools();
        } catch (IOException ex) {
            log.warn("mcp_server_test_failed name={} message={}", name, ex.getMessage());
            throw new ApiException(ErrorCode.MCP_CALL_ERROR, HttpStatus.BAD_GATEWAY, "MCP 服务不可用：" + ex.getMessage());
        }
    }

    /** 调用预配置服务上的只读工具（真实 tools/call）。 */
    public String callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpServerConfig server = requireServer(serverName);
        try (McpStdioClient client = new McpStdioClient(server, objectMapper)) {
            client.initialize();
            return client.callTool(toolName, arguments);
        } catch (IOException ex) {
            log.warn("mcp_tool_call_failed server={} tool={} message={}", serverName, toolName, ex.getMessage());
            throw new ApiException(ErrorCode.MCP_CALL_ERROR, HttpStatus.BAD_GATEWAY, "MCP 工具调用失败：" + ex.getMessage());
        }
    }

    private McpServerConfig requireServer(String name) {
        return properties.servers().stream()
                .filter(server -> server.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.MCP_SERVER_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "MCP 服务不存在或未配置"));
    }

    public record McpServerView(String name, String transport, String command) {
    }
}
