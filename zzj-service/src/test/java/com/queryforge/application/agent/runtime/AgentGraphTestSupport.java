package com.queryforge.application.agent.runtime;

import com.queryforge.domain.model.LlmClient;
import com.queryforge.domain.model.LlmStream;
import com.queryforge.infrastructure.llm.LlmTextCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * P14-ARCH-01：六 Agent Graph 定向测试的公共受控替身。
 *
 * <p>真实部分：Spring AI Alibaba StateGraph/CompiledGraph、RedisSaver + 本机 Redis、
 * HITL interruptBefore/updateState+withResume。受控替身：Fake LLM（按 system prompt
 * 路由脚本响应）、Mockito JDBC/MCP/Report 替身。绝不 Mock Graph 本身。
 */
public final class AgentGraphTestSupport {

    private AgentGraphTestSupport() {
    }

    /** 本机 Redis（与项目运行环境一致；Testcontainers 不可用时的隔离实例替代）。 */
    public static RedissonClient redisClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        return Redisson.create(config);
    }

    /**
     * 脚本化 Fake LLM：按 system prompt 关键字路由到受控响应。
     * 每次调用记录 (systemPrompt, userPrompt) 供断言调用次数与内容。
     */
    public static final class ScriptedLlmClient implements LlmClient {

        private final Map<String, Function<String, String>> routes = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private String fallback = "{}";

        /** 注册路由：system prompt 包含 key 时用该响应。 */
        public ScriptedLlmClient on(String key, String response) {
            routes.put(key, user -> response);
            return this;
        }

        public ScriptedLlmClient on(String key, Function<String, String> responder) {
            routes.put(key, responder);
            return this;
        }

        public ScriptedLlmClient fallback(String response) {
            this.fallback = response;
            return this;
        }

        /** 调用次数（按路由 key 过滤）。 */
        public int calls(String key) {
            return (int) calls.stream().filter(call -> call.contains(key)).count();
        }

        public int totalCalls() {
            return calls.size();
        }

        public List<String> userPrompts(String key) {
            List<String> result = new ArrayList<>();
            for (String call : calls) {
                if (call.contains(key)) {
                    result.add(call);
                }
            }
            return result;
        }

        @Override
        public LlmStream open(String userInput, String schemaContext) {
            return openChat("", userInput);
        }

        @Override
        public LlmStream openChat(String systemPrompt, String userPrompt) {
            calls.add(systemPrompt + "\u0001" + userPrompt);
            String response = fallback;
            for (Map.Entry<String, Function<String, String>> route : routes.entrySet()) {
                if (systemPrompt.contains(route.getKey())) {
                    response = route.getValue().apply(userPrompt);
                    break;
                }
            }
            String payload = sseLine(response);
            return new LlmStream(Stream.of(payload, "data: [DONE]"));
        }

        private static String sseLine(String content) {
            String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "");
            return "data: {\"choices\":[{\"delta\":{\"content\":\"" + escaped + "\"}}]}";
        }
    }

    /** 采集 SSE 输出为字符串（与既有测试一致）。 */
    public static String drain(ByteArrayOutputStream output) {
        return output.toString(StandardCharsets.UTF_8);
    }

    /** 供 LlmTextCollector 兼容校验（保留引用避免未使用告警）。 */
    public static String collect(LlmStream stream, ObjectMapper objectMapper) {
        return LlmTextCollector.collect(stream, objectMapper);
    }
}
