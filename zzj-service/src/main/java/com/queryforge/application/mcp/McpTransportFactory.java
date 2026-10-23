package com.queryforge.application.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.application.model.OutboundUrlGuard;
import com.queryforge.domain.mcp.McpProperties;
import com.queryforge.domain.mcp.McpServerConfig;
import com.queryforge.domain.mcp.McpServerEntity;
import com.queryforge.infrastructure.mcp.McpHttpTransport;
import com.queryforge.infrastructure.mcp.McpStdioTransport;
import com.queryforge.infrastructure.mcp.McpTransport;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 依据用户级 MCP 配置构造真实的 {@link McpTransport}。
 *
 * <p>STDIO 模式：{@code endpoint} 必须是服务端预配置白名单中的受信服务名（来自
 * {@link McpProperties}），命令与参数数组由白名单提供，绝不接受用户拼写的 shell 片段；
 * 用户只能传入受控的非敏感环境元数据。SSE/HTTP 模式：{@code endpoint} 为 URL，先经
 * {@link OutboundUrlGuard} 阻断内网/loopback/metadata/未受信地址，再交由
 * {@link McpHttpTransport} 完成协议级调用。</p>
 */
@Component
public class McpTransportFactory {

    private final McpProperties properties;
    private final OutboundUrlGuard urlGuard;
    private final ObjectMapper objectMapper;

    public McpTransportFactory(McpProperties properties, OutboundUrlGuard urlGuard, ObjectMapper objectMapper) {
        this.properties = properties;
        this.urlGuard = urlGuard;
        this.objectMapper = objectMapper;
    }

    public McpTransport create(McpServerEntity entity) {
        String transport = normalize(entity.transport());
        if ("STDIO".equals(transport)) {
            return createStdio(entity);
        }
        if ("SSE".equals(transport) || "HTTP".equals(transport)) {
            return createSse(entity);
        }
        throw new ApiException(ErrorCode.MCP_UNSUPPORTED, HttpStatus.BAD_REQUEST,
                "不支持的 MCP 传输类型：" + entity.transport());
    }

    private McpTransport createStdio(McpServerEntity entity) {
        String approvedName = entity.endpoint();
        McpServerConfig config = properties.servers().stream()
                .filter(server -> server.name().equalsIgnoreCase(approvedName))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.MCP_SERVER_NOT_APPROVED, HttpStatus.BAD_REQUEST,
                        "未批准的 MCP 命令/服务，拒绝连接：" + approvedName));
        List<String> command = new ArrayList<>();
        command.add(config.command());
        if (config.args() != null) {
            command.addAll(config.args());
        }
        Map<String, String> envMeta = parseEnvMeta(entity.envMetaJson());
        return new McpStdioTransport(command, envMeta, objectMapper);
    }

    private McpTransport createSse(McpServerEntity entity) {
        String url = entity.endpoint();
        urlGuard.validate(url); // 阻断内网/loopback/metadata/未受信 URL；不通过则抛 ApiException
        return new McpHttpTransport(url, objectMapper);
    }

    private Map<String, String> parseEnvMeta(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isObject()) {
                node.fields().forEachRemaining(entry ->
                        result.put(entry.getKey(), entry.getValue().asText()));
            }
        } catch (Exception ex) {
            return Map.of();
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
