package com.queryforge.application.agent.runtime.worker;

import com.queryforge.application.agent.runtime.AgentRunTaskContext;
import com.queryforge.application.agent.runtime.WorkerOutcome;
import org.springframework.stereotype.Component;

/**
 * P14-ARCH-01：默认 Worker 验证器（确定性检查，不引入 LLM Judge）。
 *
 * <p>Act 阶段已对真实业务输出做验证（Guard/JDBC/沙箱/MCP/Report 的真实结果）；
 * 本验证器只做最终结构化校验：成功结果必须携带非空摘要或工件引用，
 * 失败结果必须携带错误码。空结果不自动判错（合法空集由 Action 的产品语义处理）。
 */
@Component
public class DefaultWorkerVerifier implements WorkerVerifier {

    @Override
    public WorkerOutcome verify(WorkerTask task, AgentRunTaskContext ctx, WorkerOutcome outcome) {
        if (outcome == null) {
            return WorkerOutcome.failure(task.role(), task.stepId(), task.attemptId(), false,
                    "WORKER_NO_OUTCOME", "Worker 未产出结构化结果");
        }
        if (outcome.success()) {
            boolean hasEvidence = (outcome.summary() != null && !outcome.summary().isBlank())
                    || outcome.resultRef() != null;
            if (!hasEvidence) {
                return WorkerOutcome.failure(task.role(), task.stepId(), task.attemptId(), false,
                        "WORKER_UNVERIFIED", "执行结果缺少可验证证据");
            }
            return outcome;
        }
        if (outcome.failureCode() == null || outcome.failureCode().isBlank()) {
            return WorkerOutcome.failure(task.role(), task.stepId(), task.attemptId(), outcome.retryable(),
                    "WORKER_ACT_ERROR", outcome.failureReason());
        }
        return outcome;
    }
}
