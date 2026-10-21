package com.queryforge.domain.model;

/**
 * 调用结果健康上报端口：基础设施侧（调用尝试记录）在落库后上报成败与耗时，
 * 应用侧由 {@code ModelHealthRegistry} 实现，供路由能力过滤与熔断使用。
 *
 * <p>存在的目的是让基础设施不反向依赖应用服务：记录器只依赖本端口。
 */
public interface ModelHealthSink {

    void recordSuccess(Long profileId, long latencyMs);

    void recordFailure(Long profileId, long latencyMs);
}
