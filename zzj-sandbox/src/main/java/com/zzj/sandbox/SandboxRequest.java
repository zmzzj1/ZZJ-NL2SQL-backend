package com.zzj.sandbox;

/**
 * 一次受控执行的输入：受限 Python 源码 + 输入数据。
 * 输入数据可以是列/行结构，或已由调用方序列化后的受控 JSON；
 * <b>绝不包含</b>用户凭据、数据库连接串、Token 或宿主敏感路径——容器不读取管理数据库或敏感环境变量。
 *
 * @param code      受限源码（模型/用户生成，仅在隔离容器内运行）
 * @param inputJson 已经调用方裁剪/授权的受控输入数据（序列化 JSON），可为 null
 */
public record SandboxRequest(String code, String inputJson) {
}
