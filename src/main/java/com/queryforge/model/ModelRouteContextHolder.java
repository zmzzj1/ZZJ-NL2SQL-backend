package com.queryforge.model;

/**
 * 模型路由上下文持有者（ThreadLocal）。
 * 业务层在发起 LLM 调用前设置 userId/workspaceId/policyId/runId，路由据此做授权与策略选择。
 * 请求结束后必须 clear，避免线程池复用导致上下文串扰。
 */
public final class ModelRouteContextHolder {

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private ModelRouteContextHolder() {
    }

    public record Context(Long userId, Long workspaceId, Long policyId, Long runId, Long agentId) {
    }

    public static void set(Context context) {
        CURRENT.set(context);
    }

    public static Context get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
