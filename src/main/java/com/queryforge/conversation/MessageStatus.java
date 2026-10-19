package com.queryforge.conversation;

/**
 * 生成状态：只有收到完整 complete 才标记 COMPLETED；
 * 客户端断开为 CANCELED，上游/服务端错误为 FAILED。
 */
public enum MessageStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELED
}
