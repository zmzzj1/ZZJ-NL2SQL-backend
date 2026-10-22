package com.queryforge.infrastructure.llm;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * P11 A1：Spring AI ChatClient 统一构建工厂。
 *
 * <p>把任意 OpenAI 兼容端点（含现有 DashScope qwen 系列）构建为 Spring AI {@link ChatModel}，
 * 统一消息映射、流式语义、结构化响应、token 用量与异常处理。实例按端点+模型缓存，避免每次调用重建客户端。
 *
 * <p>本工厂不实现任何路由/熔断/配额逻辑——那是 P9 ModelRouter/RoutedLlmClient 的职责，
 * 本类只提供“被统一调用的引擎”，符合指南“不创建第二套故障切换器”。
 */
public class SpringAiChatClientFactory {

    private final ConcurrentHashMap<String, ChatModel> cache = new ConcurrentHashMap<>();

    public ChatModel chatModel(String baseUrl, String apiKey, String model) {
        String key = baseUrl + "|" + apiKey + "|" + model;
        return cache.computeIfAbsent(key, k -> {
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(stripTrailingSlash(baseUrl))
                    .apiKey(apiKey == null ? "" : apiKey)
                    .build();
            OpenAiChatOptions options = OpenAiChatOptions.builder().model(model).temperature(0.0).build();
            return OpenAiChatModel.builder().openAiApi(api).defaultOptions(options).build();
        });
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
