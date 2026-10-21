package com.queryforge.domain.model;

/**
 * 模型流式客户端契约。抽取接口的唯一目的是让 SqlGenerationService 能在
 * 单元测试中替换上游实现，不引入额外实现层或 Impl 包装。
 */
public interface LlmClient {

    LlmStream open(String userInput, String schemaContext);

    /**
     * 通用流式对话：Agent 的规划、SQL 生成、修复与总结各自提供 system prompt。
     * 返回值同为上游 SSE 行流，由调用方决定是否转发或聚合。
     */
    LlmStream openChat(String systemPrompt, String userPrompt);
}
