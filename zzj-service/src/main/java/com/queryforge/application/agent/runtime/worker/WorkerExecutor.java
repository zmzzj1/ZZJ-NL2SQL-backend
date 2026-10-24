package com.queryforge.application.agent.runtime.worker;

import com.queryforge.application.agent.runtime.AgentRunTaskContext;
import com.queryforge.application.agent.runtime.WorkerOutcome;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * P14-ARCH-01：标准 Worker 执行循环（Thinking → Review → Act → Verify）。
 *
 * <p>组合式架构（非继承体系）：每次 Worker 调用统一经过四个阶段——
 * <ul>
 *   <li><b>Thinking</b>：把授权任务、依赖结果引用、Schema/Memory/RAG 上下文、LLM 预算、
 *       错误反馈与 attemptId 交给 Action 的模型阶段；仅当模型提供真实 reasoning 流时才可
 *       流式发送 Thinking，没有推理流时不伪造；</li>
 *   <li><b>Review</b>：确定性检查输入合法性与角色权限（不额外增加 LLM Judge）；</li>
 *   <li><b>Act</b>：调用唯一真实业务入口，记录执行状态/结果/artifactId；</li>
 *   <li><b>Verify</b>：依据真实 Action 输出判定成败。</li>
 * </ul>
 *
 * <p>失败按 {@link WorkerOutcome#retryable()} 区分：可恢复失败携带受控诊断进入下一轮
 * Thinking（尝试次数受 {@code app.agent.max-worker-attempts} 约束）；
 * 不可恢复错误立即返回终态失败。对写工具、外部副作用或已 ACK 而状态未知的动作不盲重试。
 */
public class WorkerExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkerExecutor.class);
    private static final int MAX_DIAGNOSTIC_CHARS = 300;

    private final AgentProperties properties;

    public WorkerExecutor(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 执行一次完整 Worker 循环（含受控重试）。
     *
     * @param action    角色对应的真实业务执行体
     * @param verifier  结果验证器（可为 null，表示直接采信 Act 结果）
     * @param task      初始任务（attempt=1）
     * @param ctx       Graph Task Context
     * @return 最终结构化结果（成功或终态失败）
     */
    public WorkerOutcome execute(WorkerAction action, WorkerVerifier verifier, WorkerTask task,
                                 AgentRunTaskContext ctx) {
        MutableDiagnostics diagnostics = new MutableDiagnostics(task.diagnostics());
        int attempt = task.attempt();
        while (true) {
            WorkerTask current = new WorkerTask(task.role(), task.step(), task.attemptId(), attempt,
                    diagnostics.text(), task.route(), task.previousSummaries(), task.upstreamStepId());
            emitPhase(ctx, current, "thinking");
            // Review：确定性前置校验（输入/角色/预算），不引入 LLM 评审。
            String reviewProblem = review(current, ctx);
            if (reviewProblem != null) {
                return WorkerOutcome.failure(current.role(), current.stepId(), current.attemptId(),
                        false, "WORKER_INPUT_INVALID", reviewProblem);
            }
            emitPhase(ctx, current, "acting");
            WorkerOutcome outcome;
            try {
                outcome = action.act(current, ctx, diagnostics);
            } catch (ApiException ex) {
                // Act 已把业务错误结构化为 WorkerOutcome；走到这里说明是未结构化边界，按终态处理。
                outcome = WorkerOutcome.failure(current.role(), current.stepId(), current.attemptId(),
                        false, ex.getErrorCode().name(), safeMessage(ex.getMessage()));
            } catch (RuntimeException ex) {
                log.warn("worker_act_failed runId={} stepId={} attempt={} message={}",
                        ctx.runId(), current.stepId(), attempt, ex.getMessage());
                outcome = WorkerOutcome.failure(current.role(), current.stepId(), current.attemptId(),
                        true, "WORKER_ACT_ERROR", safeMessage(ex.getMessage()));
            }
            if (verifier != null) {
                outcome = verifier.verify(current, ctx, outcome);
            }
            emitPhase(ctx, current, outcome.success() ? "verified" : "verify_failed");
            if (outcome.success() || !outcome.retryable() || attempt >= maxAttempts()) {
                return outcome;
            }
            // 有界重试：携带真实诊断进入下一轮 Thinking，attemptId 保持不变以便审计关联。
            diagnostics.replace(outcome.failureReason());
            attempt++;
        }
    }

    /**
     * Review：确定性检查。任务/步骤/角色必须合法，运行上下文必须已授权。
     * 返回 null 表示通过；否则为可展示的拒绝原因（不泄露内部细节）。
     */
    private String review(WorkerTask task, AgentRunTaskContext ctx) {
        if (task == null || task.role() == null || task.role().isBlank()) {
            return "Worker 任务缺少受控角色";
        }
        String stepRole = task.step() == null ? null : normalizeRole(task.step().role());
        if (!task.role().equals(stepRole)) {
            // 计划步骤声明的角色与派发角色不一致：拒绝执行，不猜权。
            return "Worker 角色与计划步骤声明不一致";
        }
        if (ctx == null || ctx.runId() == null || ctx.userId() == null) {
            return "Worker 缺少授权运行上下文";
        }
        if (task.step() == null || task.step().objective() == null || task.step().objective().isBlank()) {
            return "Worker 任务缺少可执行目标";
        }
        return null;
    }

    private static String normalizeRole(String role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case "DATA_ANALYST", "SQL_AGENT" -> "DATA_SCIENTIST";
            case "CODE_ANALYST" -> "CODE_ASSISTANT";
            case "REPORT_ASSISTANT" -> "DASHBOARD";
            default -> role;
        };
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Worker 执行失败";
        }
        return truncate(message, MAX_DIAGNOSTIC_CHARS);
    }

    private int maxAttempts() {
        return Math.max(1, properties.maxWorkerAttempts());
    }

    private static void emitPhase(AgentRunTaskContext ctx, WorkerTask task, String phase) {
        SseEventWriter writer = ctx.writer();
        if (writer == null) {
            return;
        }
        try {
            writer.event("worker.thinking", new AgentStreamEvents.WorkerPhase(
                    ctx.runId(), task.stepId(), task.role(), phase, task.attemptId(), task.attempt()));
        } catch (IOException ignored) {
            // 阶段提示为可选信息，发送失败不阻断主流程
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }

    /** Act 阶段可写的受控诊断缓冲（长度有界、脱敏由各 Action 保证）。 */
    private static final class MutableDiagnostics implements WorkerDiagnosticSink {

        private String text;

        private MutableDiagnostics(String initial) {
            this.text = initial == null ? "" : truncate(initial, MAX_DIAGNOSTIC_CHARS * 2);
        }

        @Override
        public void accept(String phase, String diagnostic) {
            append(phase, diagnostic);
        }

        private void append(String phase, String diagnostic) {
            if (diagnostic == null || diagnostic.isBlank()) {
                return;
            }
            String entry = phase == null || phase.isBlank() ? diagnostic : phase + ": " + diagnostic;
            text = text.isBlank() ? entry : text + " | " + entry;
            text = truncate(text, MAX_DIAGNOSTIC_CHARS * 2);
        }

        private void replace(String next) {
            text = truncate(next == null ? "" : next, MAX_DIAGNOSTIC_CHARS * 2);
        }

        private String text() {
            return text;
        }

        @Override
        public String diagnostics() {
            return text;
        }
    }
}
