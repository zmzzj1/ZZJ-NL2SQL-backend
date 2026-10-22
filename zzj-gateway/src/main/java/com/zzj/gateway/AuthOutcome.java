package com.zzj.gateway;

/**
 * 网关入口认证拒绝原因。
 */
public enum AuthOutcome {

    /** 已有有效登录会话（由核心服务建立的 Sa-Token 会话）。 */
    AUTHENTICATED,

    /** 无 Cookie、token 不存在或已失效/撤销：按未登录处理。 */
    UNAUTHENTICATED,

    /** 会话存储不可用（Redis 故障/超时/反序列化异常）：失败关闭，不放行。 */
    UNAVAILABLE
}
