package com.queryforge.query.execution;

/**
 * SSE 事件数据体。字段名由协同文档 4.2 固定。
 */
public final class SqlStreamEvents {

    public record Start(String requestId) {
    }

    public record Delta(String text) {
    }

    public record Complete(String requestId) {
    }

    public record StreamError(String code, String message, String requestId) {
    }

    private SqlStreamEvents() {
    }
}
