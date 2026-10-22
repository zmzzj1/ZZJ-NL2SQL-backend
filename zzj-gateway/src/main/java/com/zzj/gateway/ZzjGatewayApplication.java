package com.zzj.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * P13-M1：独立网关进程。
 *
 * <p>职责边界（ADR-M1-002）：只做 `/api/**` 的路由与协议透传（含 SSE 逐帧转发），
 * 不实现任何业务身份、不做权限判定、不复制业务逻辑。
 * 认证仍由核心服务的 Sa-Token + Redis 会话裁决（ADR-M1-002：不建立第二套主体）。
 */
@SpringBootApplication
public class ZzjGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZzjGatewayApplication.class, args);
    }
}
