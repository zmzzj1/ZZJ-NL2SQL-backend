package com.queryforge.infrastructure.analysis;

import com.zzj.sandbox.DockerPythonSandboxExecutor;
import com.zzj.sandbox.SandboxExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P13-M4：把 {@code zzj-sandbox} 的执行能力接入 Spring 上下文。
 * {@link DockerPythonSandboxExecutor} 位于 {@code com.zzj.sandbox}（不在 {@code com.queryforge} 组件扫描范围），
 * 故显式声明 Bean；{@link SandboxPythonEngine} 由此注入 {@link SandboxExecutor}。
 */
@Configuration
public class SandboxConfig {

    @Bean
    public SandboxExecutor sandboxExecutor() {
        return new DockerPythonSandboxExecutor();
    }
}
