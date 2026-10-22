package com.zzj.common.admin;

/**
 * P13-M3A：管理 RPC 的业务失败语义。
 *
 * <p>只描述"为什么失败"，不携带堆栈与内部细节（Provider 内部异常不上抛到 Admin 进程）。
 * Admin 按本枚举映射 HTTP 状态，绝不把它当作业务成功的一部分。
 */
public enum AdminRpcErrorCode {

    /** 调用主体不是平台管理员（Provider 以持久化 role 判定）。 */
    FORBIDDEN,
    /** 会话主体在业务库中已不存在或不可用（Admin 侧映射为 401）。 */
    UNAUTHORIZED,
    /** 参数不合法（越界分页、空主体等）。 */
    BAD_REQUEST,
    /** 依赖的数据不可用（数据库故障等）；与 RPC 通道不可用区分对待。 */
    UNAVAILABLE,
    /** 其它未分类失败。 */
    INTERNAL
}
