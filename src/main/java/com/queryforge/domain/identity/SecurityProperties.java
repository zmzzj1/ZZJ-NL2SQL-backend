package com.queryforge.domain.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 凭据加密配置。密钥由环境变量或未提交的本地配置提供，不与密文存放在同一张表。
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(String encryptionKey) {
}
