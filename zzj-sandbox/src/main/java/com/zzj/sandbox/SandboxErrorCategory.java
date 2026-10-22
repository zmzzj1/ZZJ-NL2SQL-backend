package com.zzj.sandbox;

/**
 * 失败/异常类别，便于调用方把失败如实映射为明确的用户可读原因，而不是伪造成功。
 */
public enum SandboxErrorCategory {
    /** 正常结束（不代表一定成功，需结合 exitCode 判断）。 */
    NONE,
    /** 超过最大执行时间，已被强制销毁。 */
    TIMEOUT,
    /** 容器内代码非零退出（业务/脚本错误）。 */
    EXECUTION_FAILED,
    /** 隔离运行时不可用（如 Docker 未就绪）——失败关闭。 */
    SANDBOX_UNAVAILABLE,
    /** 受控结构化输出缺失或非法。 */
    OUTPUT_INVALID
}
