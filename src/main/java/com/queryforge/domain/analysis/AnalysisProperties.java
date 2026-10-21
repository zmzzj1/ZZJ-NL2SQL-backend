package com.queryforge.domain.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分析沙箱的资源预算与输入裁剪上限。所有数值都有硬上限，防止失控。
 */
@ConfigurationProperties(prefix = "app.analysis")
public record AnalysisProperties(
        int maxInputRows,
        int maxInputBytes,
        int maxCodeChars,
        long timeoutSeconds,
        long memoryBytes,
        double cpus,
        String sandboxImage,
        String sandboxCommand
) {
    public AnalysisProperties {
        maxInputRows = maxInputRows <= 0 ? 200 : Math.min(maxInputRows, 1000);
        maxInputBytes = maxInputBytes <= 0 ? 256 * 1024 : Math.min(maxInputBytes, 1024 * 1024);
        maxCodeChars = maxCodeChars <= 0 ? 4000 : Math.min(maxCodeChars, 20000);
        timeoutSeconds = timeoutSeconds <= 0 ? 20 : Math.min(timeoutSeconds, 120);
        memoryBytes = memoryBytes <= 0 ? 256L * 1024 * 1024 : memoryBytes;
        cpus = cpus <= 0 ? 1.0 : Math.min(cpus, 2.0);
        sandboxImage = sandboxImage == null || sandboxImage.isBlank() ? "python:3.12-slim" : sandboxImage;
        sandboxCommand = sandboxCommand == null || sandboxCommand.isBlank() ? "python" : sandboxCommand;
    }
}
