package com.zzj.sandbox;

/**
 * 受控执行失败：运行时不可用、无法启动容器、被中断等失败关闭场景。
 * 携带真实原因，绝不包装成“成功结果”。
 */
public class SandboxException extends RuntimeException {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
