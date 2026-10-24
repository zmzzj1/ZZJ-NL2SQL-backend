package com.queryforge.application.agent.runtime.worker;

import com.queryforge.application.agent.runtime.WorkerOutcome;

/**
 * P14-ARCH-01：Worker 执行循环的可恢复诊断接收端。
 * Act 阶段通过它把受控、脱敏的中间状态（生成尝试、修复轮次、沙箱进度）反馈给
 * 下一次 Thinking，不携带 SQL 候选原文、凭据或完整 Prompt。
 */
public interface WorkerDiagnosticSink {

    void accept(String phase, String diagnostic);

    /** 当前已收到的诊断文本（进入下一次 Thinking 的受控反馈）。 */
    String diagnostics();
}
