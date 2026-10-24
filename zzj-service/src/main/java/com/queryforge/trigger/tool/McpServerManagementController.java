package com.queryforge.trigger.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.application.mcp.McpServerManager;
import com.queryforge.application.mcp.McpServerManager.ConnectionResult;
import com.queryforge.domain.identity.AppUserPrincipal;
import com.queryforge.domain.mcp.McpServerEntity;
import com.queryforge.infrastructure.persistence.McpServerRepository;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P14-E：用户级 MCP 服务管理。所有写入与读取严格绑定已认证 {@code userId}；
 * 配置归属由服务端校验，绝不接受前端伪造的 userId / serverId。
 *
 * <p>{@code status} 是配置是否启用的持久意图；{@code connected} / {@code toolCount}
 * 来自 McpServerManager 的真实运行时事实。保存成功但连接失败只返回 {@code connected=false}
 * 与受控错误，绝不伪造连通。环境元数据只接受非敏感键，secret/token 等一律拒绝持久化。</p>
 */
@RestController
@RequestMapping("/api/v1/mcp-servers")
public class McpServerManagementController {

    private static final Set<String> ALLOWED_TRANSPORTS = Set.of("STDIO", "SSE");
    private static final Set<String> SENSITIVE_ENV_KEYS = Set.of(
            "password", "passwd", "secret", "token", "apikey", "api_key",
            "access_key", "accesskey", "credential", "private_key", "privatekey");
    private static final int MAX_NAME = 100;
    private static final int MAX_ENDPOINT = 300;
    private static final int MAX_ENV_KEYS = 16;
    private static final int MAX_ENV_VALUE = 256;

    private final McpServerRepository repository;
    private final McpServerManager manager;
    private final ObjectMapper objectMapper;

    public McpServerManagementController(McpServerRepository repository, McpServerManager manager,
                                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.manager = manager;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<McpServerResponse> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        Long userId = principal.id();
        return repository.listOwned(userId).stream()
                .map(entity -> toResponse(entity, manager.isConnected(userId, entity.id()),
                        manager.getToolCount(userId, entity.id()), null))
                .toList();
    }

    @PostMapping
    public McpServerResponse create(@AuthenticationPrincipal AppUserPrincipal principal,
                                    @Valid @RequestBody CreateMcpServerRequest request) {
        Long userId = principal.id();
        String transport = normalizeTransport(request.transportType());
        String argsJson = null;
        String envMetaJson = serializeEnv(request.env());
        // 未批准命令 / 不受信地址在落库前拒绝，避免留下必然连不上的 ACTIVE 配置。
        manager.validate(new McpServerEntity(null, userId, request.name().trim(), transport,
                request.endpoint().trim(), "ACTIVE", argsJson, envMetaJson, null, null));
        Long id = repository.insertOwned(userId, request.name().trim(), transport,
                request.endpoint().trim(), "ACTIVE", argsJson, envMetaJson);
        McpServerEntity entity = new McpServerEntity(id, userId, request.name().trim(), transport,
                request.endpoint().trim(), "ACTIVE", argsJson, envMetaJson, null, null);
        ConnectionResult result = manager.connect(userId, entity);
        return toResponse(entity, result.connected(), result.toolCount(), result.error());
    }

    @PutMapping("/{id}")
    public McpServerResponse update(@AuthenticationPrincipal AppUserPrincipal principal,
                                    @PathVariable Long id,
                                    @Valid @RequestBody CreateMcpServerRequest request) {
        Long userId = principal.id();
        McpServerEntity existing = repository.findOwned(id, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.MCP_SERVER_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "MCP 服务不存在或无权访问"));
        String transport = normalizeTransport(request.transportType());
        String envMetaJson = serializeEnv(request.env());
        // 配置本身非法（未批准命令/不受信地址）时不改库、不断开既有连接。
        manager.validate(new McpServerEntity(id, userId, request.name().trim(), transport,
                request.endpoint().trim(), "ACTIVE", existing.argsJson(), envMetaJson, null, null));
        boolean endpointChanged = !existing.endpoint().equals(request.endpoint().trim())
                || !existing.transport().equals(transport)
                || !safeEquals(existing.envMetaJson(), envMetaJson);
        // 避免“坏配置写成已生效、旧连接还活着”的矛盾：先撤销旧连接，再按新配置重连。
        manager.removeOwned(userId, id);
        int updated = repository.updateOwned(id, userId, request.name().trim(), transport,
                request.endpoint().trim(), "ACTIVE", existing.argsJson(), envMetaJson);
        if (updated == 0) {
            throw new ApiException(ErrorCode.MCP_SERVER_NOT_FOUND, HttpStatus.NOT_FOUND,
                    "MCP 服务不存在或无权访问");
        }
        McpServerEntity entity = new McpServerEntity(id, userId, request.name().trim(), transport,
                request.endpoint().trim(), "ACTIVE", existing.argsJson(), envMetaJson, null, null);
        ConnectionResult result = manager.connect(userId, entity);
        if (!endpointChanged && !result.connected()) {
            // 配置未变却连接失败：保持已保存配置，仅反映真实错误
            return toResponse(entity, false, 0, result.error());
        }
        return toResponse(entity, result.connected(), result.toolCount(), result.error());
    }

    @DeleteMapping("/{id}")
    public void delete(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id) {
        Long userId = principal.id();
        if (repository.findOwned(id, userId).isEmpty()) {
            throw new ApiException(ErrorCode.MCP_SERVER_NOT_FOUND, HttpStatus.NOT_FOUND,
                    "MCP 服务不存在或无权访问");
        }
        manager.removeOwned(userId, id);
        repository.deleteOwned(id, userId);
    }

    @PostMapping("/{id}/connect")
    public McpServerResponse connect(@AuthenticationPrincipal AppUserPrincipal principal,
                                     @PathVariable Long id) {
        Long userId = principal.id();
        McpServerEntity entity = repository.findOwned(id, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.MCP_SERVER_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "MCP 服务不存在或无权访问"));
        ConnectionResult result = manager.connect(userId, entity);
        return toResponse(entity, result.connected(), result.toolCount(), result.error());
    }

    @PostMapping("/{id}/disconnect")
    public McpServerResponse disconnect(@AuthenticationPrincipal AppUserPrincipal principal,
                                        @PathVariable Long id) {
        Long userId = principal.id();
        McpServerEntity entity = repository.findOwned(id, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.MCP_SERVER_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "MCP 服务不存在或无权访问"));
        manager.disconnect(userId, id);
        return toResponse(entity, false, 0, null);
    }

    @GetMapping("/{id}/status")
    public McpServerResponse status(@AuthenticationPrincipal AppUserPrincipal principal,
                                    @PathVariable Long id) {
        Long userId = principal.id();
        McpServerEntity entity = repository.findOwned(id, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.MCP_SERVER_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "MCP 服务不存在或无权访问"));
        return toResponse(entity, manager.isConnected(userId, id), manager.getToolCount(userId, id), null);
    }

    private McpServerResponse toResponse(McpServerEntity entity, boolean connected, int toolCount, String error) {
        return new McpServerResponse(entity.id(), entity.name(), entity.transport(), entity.endpoint(),
                entity.status(), connected, toolCount, error);
    }

    private String normalizeTransport(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "传输类型不能为空");
        }
        String upper = value.trim().toUpperCase();
        if (!ALLOWED_TRANSPORTS.contains(upper)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                    "传输类型仅支持 STDIO / SSE");
        }
        return upper;
    }

    private String serializeEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return null;
        }
        if (env.size() > MAX_ENV_KEYS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "环境变量数量超过上限");
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase();
            if (key.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "环境变量键不能为空");
            }
            if (SENSITIVE_ENV_KEYS.stream().anyMatch(key::contains)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                        "拒绝持久化敏感环境变量：" + entry.getKey());
            }
            if (entry.getValue() == null || entry.getValue().length() > MAX_ENV_VALUE) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                        "环境变量值超长或非法：" + entry.getKey());
            }
        }
        try {
            return objectMapper.writeValueAsString(env);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "环境变量序列化失败");
        }
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public record CreateMcpServerRequest(
            @NotBlank(message = "名称不能为空") @Size(max = MAX_NAME, message = "名称最长 100 字符") String name,
            @NotBlank(message = "传输类型不能为空") String transportType,
            @NotBlank(message = "endpoint 不能为空") @Size(max = MAX_ENDPOINT, message = "endpoint 超长") String endpoint,
            Map<String, String> env
    ) {
    }

    public record McpServerResponse(
            Long id, String name, String transportType, String endpoint, String status,
            boolean connected, int toolCount, String error
    ) {
    }
}
