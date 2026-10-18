package com.queryforge.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 连接目标白名单。V0 仅允许本地/管理员显式配置的主机和端口，
 * 防止把连接接口变成任意地址探测（SSRF）工具。
 */
@ConfigurationProperties(prefix = "app.connection-policy")
public record ConnectionPolicyProperties(
        java.util.List<String> allowedHosts,
        java.util.List<Integer> allowedPorts,
        int connectTimeoutMs,
        int socketTimeoutMs
) {

    public ConnectionPolicyProperties {
        allowedHosts = (allowedHosts == null || allowedHosts.isEmpty())
                ? java.util.List.of("127.0.0.1", "localhost")
                : java.util.List.copyOf(allowedHosts);
        allowedPorts = (allowedPorts == null || allowedPorts.isEmpty())
                ? java.util.List.of(3306, 3307, 3308, 3309, 3310)
                : java.util.List.copyOf(allowedPorts);
        connectTimeoutMs = connectTimeoutMs <= 0 ? 3000 : connectTimeoutMs;
        socketTimeoutMs = socketTimeoutMs <= 0 ? 10000 : socketTimeoutMs;
    }
}
