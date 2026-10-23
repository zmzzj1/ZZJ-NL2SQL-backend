package com.queryforge.infrastructure.mcp;

import com.queryforge.domain.mcp.McpTool;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * MCP 传输层契约：同一连接上完成 initialize、tools/list 与 tools/call。
 *
 * <p>实现必须保证请求/响应的 JSON-RPC id 关联（并发或交错时不能把别的请求结果
 * 错配到当前请求），并在 {@link #close()} 中确定性释放子进程或 HTTP 资源。
 * 服务进程/连接由实现自身管理生命周期；调用方在断连/删除时负责 close。</p>
 */
public interface McpTransport extends AutoCloseable {

    /** 握手：initialize + notifications/initialized。 */
    void connect() throws IOException;

    /** 返回该连接的真实工具清单（来自 tools/list）。 */
    List<McpTool> listTools() throws IOException;

    /**
     * 调用工具并返回文本内容（合并 content[].text）。
     * 实现应在内部按 JSON-RPC 结果/错误分流；外部分类参数错误时给出明确异常。
     */
    String callTool(String name, Map<String, Object> arguments) throws IOException;

    @Override
    void close();
}
