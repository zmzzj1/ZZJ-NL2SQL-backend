package com.queryforge.domain.agent.context;

/**
 * 分层压缩结果：注入模型的 system / user 上下文 + 压缩元数据。
 */
public record LayeredContextResult(
        String systemContext,
        String userContext,
        ContextCompactionMeta meta
) {
}
