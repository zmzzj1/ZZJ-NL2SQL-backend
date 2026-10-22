package com.queryforge.application.model;

import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 出站模型供应商地址防护（SSRF 缓解）。
 * 只允许 http/https、禁止解析到回环/私有/保留网段，仅信任管理员显式配置的 Provider URL。
 */
@Component
public class OutboundUrlGuard {

    public void validate(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "供应商地址格式非法");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "仅允许 http/https 协议");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "供应商地址缺少主机名");
        }
        if (uri.getUserInfo() != null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "供应商地址不允许包含用户信息");
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (isForbidden(address)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                        "供应商地址不允许指向内网或保留网段");
            }
        } catch (UnknownHostException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "供应商主机无法解析");
        }
    }

    private static boolean isForbidden(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }
}
