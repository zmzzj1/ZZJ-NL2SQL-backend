package com.queryforge.domain.model;

/**
 * 模型路由上下文持有者（ThreadLocal）。
 * 业务层在发起 LLM 调用前设置 userId/workspaceId/policyId/runId/agentId/conversationId，
 * 路由据此做授权、策略选择与会话亲和性绑定。
 * 请求结束后必须 clear，避免线程池复用导致上下文串扰。
 */
public final class ModelRouteContextHolder {

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private ModelRouteContextHolder() {
    }

    public record Context(Long userId, Long workspaceId, Long policyId, Long runId, Long agentId,
                          Long conversationId) {

        /** 兼容旧构造：无会话上下文（SQL Console 等非会话调用）。 */
        public Context(Long userId, Long workspaceId, Long policyId, Long runId, Long agentId) {
            this(userId, workspaceId, policyId, runId, agentId, null);
        }
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
