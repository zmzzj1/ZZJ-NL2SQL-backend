package com.zzj.sandbox;

/**
 * 一次受控执行的结果。
 *
 * @param exitCode         容器退出码（超时/被杀时为 -1）
 * @param stdoutTail       受上限约束的 stdout 摘要
 * @param stderrTail       受上限约束的 stderr 摘要
 * @param structuredOutput 受控结构化输出（output.json 内容，受 maxOutputBytes 约束）；未产出为 null
 * @param errorCategory    失败/异常类别；正常为 {@link SandboxErrorCategory#NONE}
 */
public record SandboxResult(
        int exitCode,
        String stdoutTail,
        String stderrTail,
        String structuredOutput,
        SandboxErrorCategory errorCategory
) {
}
