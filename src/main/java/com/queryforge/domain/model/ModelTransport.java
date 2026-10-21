package com.queryforge.domain.model;

import java.io.IOException;
import java.util.stream.Stream;

/**
 * P11 修正：底层模型调用传输层。
 *
 * <p>只负责「把 RoutedLlmClient 选中的 Provider/Model 的 chat 流式请求真正发出去，并返回
 * OpenAI 兼容 SSE 行流」。不负责路由、授权、配额、审计、熔断与故障转移——那是
 * {@code RoutedLlmClient} 与 P9 治理链路（ModelRouter/InvocationRecorder/ModelHealthRegistry/QuotaService）的职责。
 *
 * <p>两个实现互斥装配：
 * <ul>
 *   <li>{@code HttpModelTransport}（默认，{@code java.net.http}）；</li>
 *   <li>{@code SpringAiModelTransport}（可选，{@code app.llm.use-spring-ai=true}，基于 Spring AI ChatClient）。
 *       二者都只作底层执行引擎，绝不替换或绕过 RoutedLlmClient。</li>
 * </ul>
 */
public interface ModelTransport {

    /** 一次底层流式调用的结果：HTTP 状态码 + OpenAI 兼容 SSE 行流。 */
    record Response(int statusCode, Stream<String> lines) {
    }

    /**
     * 发起底层流式 chat 调用。
     *
     * @return 状态码与 SSE 行流；200 表示成功，其余由调用方（RoutedLlmClient）按可重试性决定是否故障转移。
     */
    Response stream(String baseUrl, String apiKey, String model, String systemPrompt, String userPrompt)
            throws IOException, InterruptedException;
}
