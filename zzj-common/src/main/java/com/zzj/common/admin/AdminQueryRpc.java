package com.zzj.common.admin;

/**
 * P13-M3A：管理查询的跨服务 RPC 契约（最小集合）。
 *
 * <p>只暴露两条只读管理查询；Provider 由核心业务服务实现，Admin 侧只做消费。
 * 契约 JAR 不含数据库访问、HTTP Controller、Sa-Token 或任何业务 Bean，
 * 因此 Admin 不可能借本接口绕过核心服务的判权。
 *
 * <p>约定：{@code actor} 只用于判权，不用于数据过滤；Provider 必须以持久化 role 重新校验，
 * 客户端传入的 role 不具备任何效力。
 */
public interface AdminQueryRpc {

    /** 接口版本：Provider 与 Consumer 必须一致。 */
    String VERSION = "1.0.0";

    /** 服务分组：与业务 HTTP 路由无关，仅用于 RPC 注册隔离。 */
    String GROUP = "zzj-admin";

    AdminRpcResult<AdminUserPage> listUsers(AdminActor actor, AdminUserQuery query);

    AdminRpcResult<AdminDashboardSummary> dashboard(AdminActor actor);
}
