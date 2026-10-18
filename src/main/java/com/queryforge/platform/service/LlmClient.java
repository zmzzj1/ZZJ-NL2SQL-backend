package com.queryforge.platform.service;

/**
 * 模型流式客户端契约。抽取接口的唯一目的是让 SqlGenerationService 能在
 * 单元测试中替换上游实现，不引入额外实现层或 Impl 包装。
 */
public interface LlmClient {

    LlmStream open(String userInput, String schemaContext);
}
