package com.queryforge.trigger.web;

import com.queryforge.trigger.web.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C0 健康检查。契约固定为 {"status":"UP"}，不附带环境或版本字段。
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public HealthResponse health() {
        return new HealthResponse("UP");
    }
}
