package com.queryforge.infrastructure.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P11 修正配置：只提供 Spring AI ChatClient 构建工厂（供 {@code SpringAiModelTransport} 使用）。
 *
 * <p>Spring AI 仅作为底层模型传输层（{@code app.llm.use-spring-ai=true} 时启用），
 * 不再注册任何 {@code @Primary LlmClient}——统一入口始终是 P9 {@code RoutedLlmClient}。
 */
@Configuration
public class SpringAiConfig {

    @Bean
    public SpringAiChatClientFactory springAiChatClientFactory() {
        return new SpringAiChatClientFactory();
    }
}
