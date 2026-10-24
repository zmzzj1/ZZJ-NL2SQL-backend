package com.queryforge.application.mcp;

import com.queryforge.domain.mcp.McpRegisteredTool;
import com.queryforge.domain.mcp.McpServerEntity;
import com.queryforge.domain.mcp.McpTool;
import com.queryforge.infrastructure.mcp.McpTransport;
import com.queryforge.infrastructure.persistence.McpServerRepository;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 用户作用域的 MCP 连接生命周期与动态工具注册表。
 *
 * <p>{@code status}（配置是否启用）是<b>持久意图</b>；{@code connected}（连接是否真实在线）
 * 是<b>运行时事实</b>，二者分别维护、不得混用。同一用户下以
 * {@code (userId, serverId)} 唯一寻址连接，禁止跨用户覆盖映射；并发重连不能在同 ID 上
 * 留下多个活跃实例。断连/删除必须撤销该 server 的全部工具与连接；应用关闭释放子进程/HTTP 资源。</p>
 */
@Component
public class McpServerManager {

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);

    private final McpServerRepository repository;
    private final McpTransportFactory transportFactory;

    // userId -> (serverId -> Connection)
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, Connection>> connections = new ConcurrentHashMap<>();
    // 每个 (userId, serverId) 的独立锁，避免并发重连产生多个活跃实例
    private final ConcurrentHashMap<String, ReentrantLock> serverLocks = new ConcurrentHashMap<>();

    public McpServerManager(McpServerRepository repository, McpTransportFactory transportFactory) {
        this.repository = repository;
        this.transportFactory = transportFactory;
    }

    /**
     * 落库前的静态校验：未批准的 STDIO 服务名、不受信的 SSE 地址在这里就被明确拒绝，
     * 避免把必然无法连接的配置写成 {@code ACTIVE} 留下孤儿行。不建立真实连接。
     */
    public void validate(McpServerEntity entity) {
        transportFactory.create(entity);
    }

    /**
     * 尝试连接并真实发现工具，注册到用户作用域。保存成功但连接失败时必须返回
     * {@code connected=false} 与受控错误，绝不声称已连通。
     */
    public ConnectionResult connect(Long userId, McpServerEntity entity) {
        ReentrantLock lock = lockFor(userId, entity.id());
        lock.lock();
        try {
            closeConnection(userId, entity.id());
            McpTransport transport = transportFactory.create(entity);
            try {
                transport.connect();
                List<McpTool> tools = transport.listTools();
                List<McpRegisteredTool> registered = new ArrayList<>();
                for (McpTool tool : tools) {
                    registered.add(new McpRegisteredTool(
                            entity.id(), userId, tool.name(), tool.description(),
                            tool.inputSchema(), false));
                }
                connections.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                        .put(entity.id(), new Connection(transport, registered));
                return new ConnectionResult(true, registered.size(), null);
            } catch (Exception ex) {
                safeClose(transport);
                return new ConnectionResult(false, 0, sanitize(ex.getMessage()));
            }
        } finally {
            lock.unlock();
        }
    }

    public void disconnect(Long userId, Long serverId) {
        ReentrantLock lock = lockFor(userId, serverId);
        lock.lock();
        try {
            closeConnection(userId, serverId);
        } finally {
            lock.unlock();
        }
    }

    /** 删除配置时的清理：断连并撤销动态注册。 */
    public void removeOwned(Long userId, Long serverId) {
        disconnect(userId, serverId);
    }

    public boolean isConnected(Long userId, Long serverId) {
        Map<Long, Connection> userMap = connections.get(userId);
        return userMap != null && userMap.containsKey(serverId);
    }

    public int getToolCount(Long userId, Long serverId) {
        Map<Long, Connection> userMap = connections.get(userId);
        if (userMap == null) {
            return 0;
        }
        Connection conn = userMap.get(serverId);
        return conn == null ? 0 : conn.tools().size();
    }

    /** 当前用户所有已连接服务的真实工具（供统一目录使用）。 */
    public List<McpRegisteredTool> listConnectedTools(Long userId) {
        Map<Long, Connection> userMap = connections.get(userId);
        if (userMap == null) {
            return List.of();
        }
        List<McpRegisteredTool> all = new ArrayList<>();
        for (Connection conn : userMap.values()) {
            all.addAll(conn.tools());
        }
        return all;
    }

    /** 在用户作用域唯一解析 (serverId, toolName)，禁止跨用户串台。 */
    public McpRegisteredTool findTool(Long userId, Long serverId, String toolName) {
        Map<Long, Connection> userMap = connections.get(userId);
        if (userMap == null) {
            return null;
        }
        Connection conn = userMap.get(serverId);
        if (conn == null) {
            return null;
        }
        for (McpRegisteredTool tool : conn.tools()) {
            if (tool.toolName().equals(toolName)) {
                return tool;
            }
        }
        return null;
    }

    /** 在用户作用域全部已连接服务中解析 (serverId, toolName)，禁止跨用户串台。 */
    public McpRegisteredTool findToolAnyServer(Long userId, String toolName) {
        Map<Long, Connection> userMap = connections.get(userId);
        if (userMap == null) {
            return null;
        }
        for (Connection conn : userMap.values()) {
            for (McpRegisteredTool tool : conn.tools()) {
                if (tool.toolName().equals(toolName)) {
                    return tool;
                }
            }
        }
        return null;
    }

    /** 返回该 server 当前真实 transport（供受控 tools/call 使用）；未连接返回 null。 */
    public McpTransport getTransport(Long userId, Long serverId) {
        Map<Long, Connection> userMap = connections.get(userId);
        if (userMap == null) {
            return null;
        }
        Connection conn = userMap.get(serverId);
        return conn == null ? null : conn.transport();
    }

    /** 应用启动后对 ACTIVE 配置逐项恢复；单个失败不得阻断其他连接恢复。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverActiveConnections() {
        List<McpServerEntity> active;
        try {
            active = repository.listAllActive();
        } catch (Exception ex) {
            log.warn("mcp_recover_list_failed message={}", ex.getMessage());
            return;
        }
        for (McpServerEntity entity : active) {
            try {
                ConnectionResult result = connect(entity.userId(), entity);
                if (!result.connected()) {
                    log.info("mcp_recover_skipped serverId={} reason={}", entity.id(), result.error());
                }
            } catch (Exception ex) {
                log.warn("mcp_recover_failed serverId={} message={}", entity.id(), ex.getMessage());
            }
        }
    }

    /** 应用关闭释放活跃子进程/HTTP 资源。 */
    @PreDestroy
    public void shutdown() {
        for (Map<Long, Connection> userMap : connections.values()) {
            for (Connection conn : userMap.values()) {
                safeClose(conn.transport());
            }
            userMap.clear();
        }
        connections.clear();
    }

    private void closeConnection(Long userId, Long serverId) {
        Map<Long, Connection> userMap = connections.get(userId);
        if (userMap == null) {
            return;
        }
        Connection conn = userMap.remove(serverId);
        if (conn != null) {
            safeClose(conn.transport());
        }
    }

    private void safeClose(McpTransport transport) {
        if (transport == null) {
            return;
        }
        try {
            transport.close();
        } catch (Exception ignored) {
            // 关闭失败不应阻断后续清理
        }
    }

    private ReentrantLock lockFor(Long userId, Long serverId) {
        String key = userId + ":" + serverId;
        return serverLocks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    private static String sanitize(String message) {
        if (message == null) {
            return "连接失败";
        }
        // 仅保留首行可读信息，避免 env 路径等敏感细节外泄
        return message.lines().findFirst().orElse("连接失败");
    }

    /** 单次连接的运行时记录：活跃 transport + 最新真实工具清单。 */
    private record Connection(McpTransport transport, List<McpRegisteredTool> tools) {
    }

    /** 连接尝试的真实结果：connected 为运行时事实，error 为受控脱敏信息。 */
    public record ConnectionResult(boolean connected, int toolCount, String error) {
    }
}
