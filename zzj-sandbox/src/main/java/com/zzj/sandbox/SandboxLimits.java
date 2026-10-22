package com.zzj.sandbox;

/**
 * 执行限制：镜像、命令与各类资源/输出硬上限。所有上限都必须真正生效，防止失控。
 *
 * @param image          隔离镜像（如 python:3.12-slim）
 * @param command        镜像内入口命令（如 python）
 * @param timeoutSeconds 最大执行时间（秒）
 * @param memoryBytes    内存/交换上限（字节，memory == memory-swap，禁交换）
 * @param cpus           CPU 配额
 * @param pidsLimit      最大进程/线程数
 * @param maxStdoutBytes 单条 stdout/stderr 采集上限（字节，超出部分丢弃但仍排空防阻塞）
 * @param maxOutputBytes 受控结构化输出文件读取上限（字节）
 */
public record SandboxLimits(
        String image,
        String command,
        long timeoutSeconds,
        long memoryBytes,
        double cpus,
        int pidsLimit,
        long maxStdoutBytes,
        long maxOutputBytes
) {
    public SandboxLimits {
        image = image == null || image.isBlank() ? "python:3.12-slim" : image;
        command = command == null || command.isBlank() ? "python" : command;
        timeoutSeconds = timeoutSeconds <= 0 ? 20 : timeoutSeconds;
        memoryBytes = memoryBytes <= 0 ? 256L * 1024 * 1024 : memoryBytes;
        cpus = cpus <= 0 ? 1.0 : cpus;
        pidsLimit = pidsLimit <= 0 ? 64 : pidsLimit;
        maxStdoutBytes = maxStdoutBytes <= 0 ? 64L * 1024 : maxStdoutBytes;
        maxOutputBytes = maxOutputBytes <= 0 ? 1024L * 1024 : maxOutputBytes;
    }
}
