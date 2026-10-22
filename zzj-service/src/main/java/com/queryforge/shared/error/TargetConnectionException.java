package com.queryforge.shared.error;

/**
 * 目标数据库连接/元数据读取失败。对外只转换为安全摘要，不暴露账号或连接串。
 */
public class TargetConnectionException extends RuntimeException {

    public TargetConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
