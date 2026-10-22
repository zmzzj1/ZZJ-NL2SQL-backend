package com.zzj.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * P13-M2：网关侧拒绝响应——沿用核心服务 {@code ApiErrorResponse(code, message, requestId)} 形状，
 * 使既有前端 {@code api/http.ts} 能像识别 Service 的 401 一样识别网关拒绝，而不是收到 200 包裹的错误。
 */
@Component
public class GatewayErrorWriter {

    private final ObjectMapper objectMapper;

    public GatewayErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 写入 JSON 错误体；{@code requestId} 用请求关联 ID，不含任何凭据。 */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(
                    new GatewayErrorBody(code, message, exchange.getRequest().getId()));
        } catch (Exception ex) {
            payload = ("{\"code\":\"" + code + "\",\"message\":\"请求被拒绝\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBufferFactory factory = exchange.getResponse().bufferFactory();
        DataBuffer buffer = factory.wrap(payload);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** 与 {@code ApiErrorResponse} 字段一致的网关错误体。 */
    public record GatewayErrorBody(String code, String message, String requestId) {
    }
}
