package com.queryforge.application.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.application.agent.AgentLlmCallLimiter;
import com.queryforge.application.tool.AgentRunToolPolicy;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.agent.roles.RoleRegistry;
import com.queryforge.domain.mcp.McpServerEntity;
import com.queryforge.domain.mcp.McpTool;
import com.queryforge.domain.model.LlmClient;
import com.queryforge.domain.model.LlmStream;
import com.queryforge.domain.tool.Tool;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.infrastructure.mcp.McpTransport;
import com.queryforge.infrastructure.persistence.McpServerRepository;
import com.queryforge.infrastructure.persistence.ToolInvocationRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/** P14-E 定向测试用的手写测试替身（不依赖 mock 框架，行为可读可断言）。 */
public final class McpTestFakes {

    private McpTestFakes() {
    }

    public static McpServerEntity entity(Long id, Long userId) {
        return new McpServerEntity(id, userId, "svc", "STDIO", "demo", "ACTIVE", null, null, null, null);
    }

    public static final class FakeTransport implements McpTransport {
        public final List<McpTool> tools;
        public final boolean failConnect;
        public volatile boolean closed;
        public volatile int callCount;
        public volatile String lastCallName;
        public volatile Map<String, Object> lastCallArgs;

        public FakeTransport(List<McpTool> tools, boolean failConnect) {
            this.tools = tools;
            this.failConnect = failConnect;
        }

        @Override
        public void connect() throws IOException {
            if (failConnect) {
                throw new IOException("连接失败");
            }
        }

        @Override
        public List<McpTool> listTools() {
            return tools;
        }

        @Override
        public String callTool(String name, Map<String, Object> arguments) {
            callCount++;
            lastCallName = name;
            lastCallArgs = arguments;
            return "ok:" + name;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    public static final class FakeFactory extends McpTransportFactory {
        private final FakeTransport transport;

        public FakeFactory(FakeTransport transport) {
            super(null, null, null);
            this.transport = transport;
        }

        @Override
        public McpTransport create(McpServerEntity entity) {
            return transport;
        }
    }

    /** 每次 create 返回新实例，用于验证并发重连不会留下多个活跃连接。 */
    public static final class MultiFactory extends McpTransportFactory {
        public final List<FakeTransport> created = new CopyOnWriteArrayList<>();
        private final List<McpTool> tools;
        private final boolean failConnect;

        public MultiFactory(List<McpTool> tools, boolean failConnect) {
            super(null, null, null);
            this.tools = tools;
            this.failConnect = failConnect;
        }

        @Override
        public McpTransport create(McpServerEntity entity) {
            FakeTransport transport = new FakeTransport(tools, failConnect);
            created.add(transport);
            return transport;
        }
    }

    public static final class FakeRepo extends McpServerRepository {
        public FakeRepo() {
            super(null);
        }

        @Override
        public List<McpServerEntity> listAllActive() {
            return List.of();
        }
    }

    public static final class FakeAudit extends ToolInvocationRepository {
        public final List<String> statuses = new ArrayList<>();
        public final List<String> errorCodes = new ArrayList<>();

        public FakeAudit() {
            super(null);
        }

        @Override
        public Long insert(ToolInvocation invocation, String status, Long durationMs, String errorCode) {
            statuses.add(status);
            errorCodes.add(errorCode);
            return null;
        }
    }

    public static final class FakeLimiter extends AgentLlmCallLimiter {
        public volatile boolean allow = true;

        public FakeLimiter() {
            super(null, null);
        }

        @Override
        public boolean tryAcquire(Long runId) {
            return allow;
        }
    }

    /** 返回一次 OpenAI 兼容的 data 帧，内容即模型“产出”的原始文本。 */
    public static final class FakeLlmClient implements LlmClient {
        public volatile String reply = "{}";
        public volatile int calls;
        public volatile String lastUserPrompt;

        public FakeLlmClient(String reply) {
            this.reply = reply;
        }

        @Override
        public LlmStream open(String userInput, String schemaContext) {
            return stream("");
        }

        @Override
        public LlmStream openChat(String systemPrompt, String userPrompt) {
            calls++;
            lastUserPrompt = userPrompt;
            return stream(reply);
        }

        private static LlmStream stream(String content) {
            try {
                String quoted = new ObjectMapper().writeValueAsString(content);
                return new LlmStream(Stream.of(
                        "data: {\"choices\":[{\"delta\":{\"content\":" + quoted + "}}]}"));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    public static final class FakeTool implements Tool {
        private final String name;
        public volatile int calls;

        public FakeTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "内置测试工具";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object");
        }

        @Override
        public Object execute(ToolInvocation invocation, Map<String, Object> args) {
            calls++;
            return Map.of("ok", true);
        }
    }

    public static final class FakeRoles extends RoleRegistry {
        public final List<String> required = new ArrayList<>();

        public FakeRoles() {
            super(null);
        }

        @Override
        public void requireAllowed(AgentRole role, String toolId) {
            required.add(role + ":" + toolId);
        }
    }

    /** 空白名单 = 不额外限制（与生产 ToolRegistry 的默认放行语义一致）。 */
    public static final class FakePolicy extends AgentRunToolPolicy {
        public FakePolicy() {
            super(null, null);
        }

        @Override
        public List<String> allowedToolNames(Long agentRunId) {
            return List.of();
        }
    }
}
