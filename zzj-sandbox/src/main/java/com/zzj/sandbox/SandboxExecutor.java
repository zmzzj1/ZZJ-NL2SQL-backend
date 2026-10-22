package com.zzj.sandbox;

/**
 * 受控执行契约：把不可信代码（例如模型生成的 Python）放进一次性隔离容器执行。
 * 实现必须保证：
 * <ul>
 *   <li>可用性真实：{@link #available()} 只在隔离运行时（如 Docker 守护进程）就绪时返回 true；</li>
 *   <li>失败关闭：运行时不可用、超时、输出异常时明确失败，绝不返回伪造成功，也绝不回退宿主机；</li>
 *   <li>输入不泄漏：{@link SandboxRequest} 只含受限源码与受控数据，不含凭据/连接串/Token/宿主敏感路径。</li>
 * </ul>
 * 本接口不依赖任何 Spring/Nacos/Dubbo/持久化，供 Analysis 与后续 Code Agent 复用同一执行能力。
 */
public interface SandboxExecutor {

    /** 隔离运行时是否当前可用（例如 Docker 守护进程是否就绪）。 */
    boolean available();

    /**
     * 在隔离容器内执行受限代码，返回受控结构化输出与退出摘要。
     *
     * @param request 受限源码 + 受控输入数据（列/行或序列化后的受控 JSON）
     * @param limits  镜像、命令、超时、CPU、内存、进程数、输出大小等执行限制
     * @throws SandboxException 运行时不可用、无法启动容器、被中断等失败关闭场景
     */
    SandboxResult execute(SandboxRequest request, SandboxLimits limits) throws SandboxException;
}
