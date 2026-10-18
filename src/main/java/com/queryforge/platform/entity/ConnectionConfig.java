package com.queryforge.platform.entity;

import java.time.LocalDateTime;

/** 管理库中的连接配置；密码字段只保存密文。 */
public record ConnectionConfig(
        Long id,
        Long userId,
        String name,
        String engine,
        String host,
        int port,
        String database,
        String username,
        String passwordCiphertext,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
