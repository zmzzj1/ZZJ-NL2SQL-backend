package com.queryforge.platform.service;

import com.queryforge.platform.config.ConnectionPolicyProperties;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 连接目标白名单与字段格式校验，避免任意 JDBC 地址/参数注入（SSRF）。
 */
@Component
public class ConnectionTargetPolicy {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    private final ConnectionPolicyProperties properties;

    public ConnectionTargetPolicy(ConnectionPolicyProperties properties) {
        this.properties = properties;
    }

    public String normalizeHost(String host) {
        String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (!properties.allowedHosts().contains(normalized)) {
            throw reject("该主机不在允许连接的白名单内");
        }
        return normalized;
    }

    public int validatePort(int port) {
        if (!properties.allowedPorts().contains(port)) {
            throw reject("该端口不在允许连接的白名单内");
        }
        return port;
    }

    public String validateIdentifier(String value, String fieldName) {
        String trimmed = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(trimmed).matches()) {
            throw reject(fieldName + "只能包含字母、数字和下划线，且不超过 64 字符");
        }
        return trimmed;
    }

    private static ApiException reject(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, message);
    }
}
