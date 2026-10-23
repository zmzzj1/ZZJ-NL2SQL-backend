package com.queryforge.application.mcp;

import static com.queryforge.application.mcp.McpTestFakes.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.queryforge.domain.mcp.McpRegisteredTool;
import com.queryforge.domain.mcp.McpTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 用户作用域连接生命周期：status（意图）与 connected（事实）分离、失败不得声称已连通、
 * 用户隔离、断连/删除撤销全部工具、关闭释放资源。
 */
class McpServerManagerTest {

    private static final List<McpTool> TOOLS = List.of(
            new McpTool("t1", "工具一", Map.of("type", "object")),
            new McpTool("t2", "工具二", Map.of("type", "object")));

    @Test
    void connectRegistersRealToolsAndReportsCount() {
        McpTestFakes.FakeTransport transport = new McpTestFakes.FakeTransport(TOOLS, false);
        McpServerManager manager = new McpServerManager(new McpTestFakes.FakeRepo(), new McpTestFakes.FakeFactory(transport));

        McpServerManager.ConnectionResult result = manager.connect(7L, entity(1L, 7L));

        assertTrue(result.connected(), "真实连接成功必须报告已连通");
        assertEquals(2, result.toolCount());
        assertNull(result.error());
        assertEquals(2, manager.listConnectedTools(7L).size());
        assertNotNull(manager.findTool(7L, 1L, "t1"));
        assertTrue(manager.isConnected(7L, 1L));
    }

    @Test
    void connectFailureReportsNotConnectedAndRegistersNothing() {
        McpTestFakes.FakeTransport transport = new McpTestFakes.FakeTransport(TOOLS, true);
        McpServerManager manager = new McpServerManager(new McpTestFakes.FakeRepo(), new McpTestFakes.FakeFactory(transport));

        McpServerManager.ConnectionResult result = manager.connect(7L, entity(1L, 7L));

        assertFalse(result.connected(), "连接失败绝不能声称已连通");
        assertEquals(0, result.toolCount());
        assertNotNull(result.error());
        assertFalse(manager.isConnected(7L, 1L));
        assertTrue(manager.listConnectedTools(7L).isEmpty(), "失败连接不得注册任何工具");
        assertTrue(transport.closed, "失败连接必须释放资源");
    }

    @Test
    void toolsOfOneUserAreInvisibleToAnother() {
        McpTestFakes.FakeTransport transport = new McpTestFakes.FakeTransport(TOOLS, false);
        McpServerManager manager = new McpServerManager(new McpTestFakes.FakeRepo(), new McpTestFakes.FakeFactory(transport));
        manager.connect(7L, entity(1L, 7L));

        assertNull(manager.findTool(8L, 1L, "t1"), "禁止跨用户解析工具");
        assertNull(manager.findToolAnyServer(8L, "t2"), "禁止跨用户解析工具");
        assertTrue(manager.listConnectedTools(8L).isEmpty());
        assertNull(manager.getTransport(8L, 1L));
    }

    @Test
    void findToolAnyServerResolvesWithinUserScope() {
        McpTestFakes.FakeTransport transport = new McpTestFakes.FakeTransport(TOOLS, false);
        McpServerManager manager = new McpServerManager(new McpTestFakes.FakeRepo(), new McpTestFakes.FakeFactory(transport));
        manager.connect(7L, entity(1L, 7L));

        McpRegisteredTool found = manager.findToolAnyServer(7L, "t2");
        assertNotNull(found);
        assertEquals(1L, found.serverId());
        assertEquals(7L, found.ownerUserId());
        assertNotNull(manager.getTransport(7L, 1L));
    }

    @Test
    void disconnectRevokesToolsAndClosesTransport() {
        McpTestFakes.FakeTransport transport = new McpTestFakes.FakeTransport(TOOLS, false);
        McpServerManager manager = new McpServerManager(new McpTestFakes.FakeRepo(), new McpTestFakes.FakeFactory(transport));
        manager.connect(7L, entity(1L, 7L));

        manager.disconnect(7L, 1L);

        assertFalse(manager.isConnected(7L, 1L));
        assertEquals(0, manager.getToolCount(7L, 1L));
        assertTrue(manager.listConnectedTools(7L).isEmpty(), "断连必须撤销该服务的全部工具");
        assertTrue(transport.closed);
    }

    @Test
    void removeOwnedRevokesTools() {
        McpTestFakes.FakeTransport transport = new McpTestFakes.FakeTransport(TOOLS, false);
        McpServerManager manager = new McpServerManager(new McpTestFakes.FakeRepo(), new McpTestFakes.FakeFactory(transport));
        manager.connect(7L, entity(1L, 7L));

        manager.removeOwned(7L, 1L);

        assertFalse(manager.isConnected(7L, 1L));
        assertTrue(manager.listConnectedTools(7L).isEmpty());
    }

    @Test
    void concurrentReconnectLeavesSingleActiveInstance() throws Exception {
        McpTestFakes.MultiFactory factory = new McpTestFakes.MultiFactory(TOOLS, false);
        McpServerManager manager = new McpServerManager(new McpTestFakes.FakeRepo(), factory);

        Thread first = new Thread(() -> manager.connect(7L, entity(1L, 7L)));
        Thread second = new Thread(() -> manager.connect(7L, entity(1L, 7L)));
        first.start();
        second.start();
        first.join();
        second.join();

        assertEquals(2, manager.getToolCount(7L, 1L), "并发重连不得叠加出多份工具注册");
        assertEquals(2, manager.listConnectedTools(7L).size());
        assertTrue(factory.created.stream().anyMatch(t -> t.closed), "被替换的连接必须被关闭释放");
    }

    @Test
    void shutdownReleasesActiveConnections() {
        McpTestFakes.FakeTransport transport = new McpTestFakes.FakeTransport(TOOLS, false);
        McpServerManager manager = new McpServerManager(new McpTestFakes.FakeRepo(), new McpTestFakes.FakeFactory(transport));
        manager.connect(7L, entity(1L, 7L));

        manager.shutdown();

        assertTrue(transport.closed);
        assertTrue(manager.listConnectedTools(7L).isEmpty());
    }
}
