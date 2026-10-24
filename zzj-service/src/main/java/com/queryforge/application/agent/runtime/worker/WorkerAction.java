package com.queryforge.application.agent.runtime.worker;

import com.queryforge.application.agent.runtime.AgentRunTaskContext;
import com.queryforge.application.agent.runtime.WorkerOutcome;

/**
 * P14-ARCH-01：Worker 的真实业务执行体（Act 阶段唯一入口）。
 *
 * <p>实现只能调用已有授权业务服务（SQL Guard+JDBC / Docker Python / MCP / Report），
 * 记录执行状态、结果与 artifactId，并返回结构化 {@link WorkerOutcome}；
 * 不得在实现内自行推进计划（调度归 Graph/Manager），也不得绕过权限与审批边界。
 */
public interface WorkerAction {

    /** 受控角色名（与图中节点派发角色一致）。 */
    String role();

    /**
     * 执行真实业务动作。
     *
     * @param task    任务描述（步骤、attemptId、受控诊断）
     * @param ctx     本次 Run 的 Graph Task Context（身份/配置/SSE/上下文）
     * @param sink    诊断反馈接收端（Think→Act→Verify 循环内共享）
     * @return 真实执行结果；成功必须来自真实 Action 输出验证
     */
    WorkerOutcome act(WorkerTask task, AgentRunTaskContext ctx, WorkerDiagnosticSink sink);
}
