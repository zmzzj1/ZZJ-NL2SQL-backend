package com.queryforge.application.agent.runtime.worker;

import com.queryforge.application.agent.runtime.AgentRunTaskContext;
import com.queryforge.application.agent.runtime.WorkerOutcome;

/**
 * P14-ARCH-01：Worker 执行结果验证器（Verify 阶段，确定性检查）。
 *
 * <p>只依据真实 Action 输出与必要字段做判定：无 Action、异常、沙箱失败、MCP isError、
 * 报告未持久化均不能成功；空结果按产品语义显式处理（合法空集不自动代表 SQL 错），
 * 不盲目重试制造虚假错误。默认实现直接采信 Act 返回的结构化结果。
 */
public interface WorkerVerifier {

    /**
     * @param task    任务描述
     * @param ctx     Graph Task Context
     * @param outcome Act 阶段产出的原始结果
     * @return 验证后的最终结果（可实现为原样返回或修正判定）
     */
    WorkerOutcome verify(WorkerTask task, AgentRunTaskContext ctx, WorkerOutcome outcome);
}
