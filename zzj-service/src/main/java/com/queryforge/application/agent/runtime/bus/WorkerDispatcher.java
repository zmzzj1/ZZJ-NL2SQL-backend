package com.queryforge.application.agent.runtime.bus;

import com.queryforge.application.agent.runtime.AgentRunTaskContext;
import com.queryforge.application.agent.runtime.WorkerOutcome;
import com.queryforge.application.agent.runtime.worker.WorkerAction;
import com.queryforge.application.agent.runtime.worker.WorkerExecutor;
import com.queryforge.application.agent.runtime.worker.WorkerTask;
import com.queryforge.application.agent.runtime.worker.WorkerVerifier;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.infrastructure.web.SseEventWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * P14-ARCH-01：Worker 分派器（Graph Node 的唯一 Worker 入口）。
 *
 * <p>三种模式（{@code app.agent.dispatch-mode}）：
 * <ul>
 *   <li><b>OFF（默认）</b>：直接调用同一个 {@link WorkerExecutor}，不经总线；</li>
 *   <li><b>BYPASS</b>：真实执行与 OFF 相同（同一个 Executor），Bus 只旁路镜像分派与结果信息，
 *       旁路监听者不能真的再调用 Worker（零额外业务动作）；</li>
 *   <li><b>SWITCH</b>：通过 In-JVM Bus 把 TaskDispatch 发到 Worker Endpoint 队列，
 *       端点受理（ACK）后真实执行并回 {@link BusMessage.WorkerResult}，Dispatcher 收到
 *       实际 Worker 的 reply 才结束；Graph Node 在此模式下不另行直调 Worker。</li>
 * </ul>
 *
 * <p>与 Graph 的协调：Graph 管调度位置/断点；Bus 仅传递任务；MySQL/CAS 负责持久化去重；
 * 每步仅一个真实的 Action 执行者。BYPASS 绝不执行两次；SWITCH 端点未注册/投递失败/
 * 结果超时都是明确失败，不本地兜底执行（防止双执行）。
 */
public class WorkerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WorkerDispatcher.class);
    private static final String TOPIC_DISPATCH = "agent.bus.dispatch";
    private static final String TOPIC_RESULT = "agent.bus.result";
    private static final long SWITCH_RESULT_TIMEOUT_MS = 120_000L;

    private final AgentProperties properties;
    private final AgentMessageBus bus;
    private final WorkerEndpoint endpoint;
    private final WorkerExecutor executor;

    public WorkerDispatcher(AgentProperties properties, AgentMessageBus bus, WorkerEndpoint endpoint,
                            WorkerExecutor executor) {
        this.properties = properties;
        this.bus = bus;
        this.endpoint = endpoint;
        this.executor = executor;
    }

    public String mode() {
        return properties.dispatchMode();
    }

    /**
     * 分派并执行一个 Worker 任务（三种模式语义一致：最终业务结果与执行次数相同）。
     *
     * @param action   真实业务执行体
     * @param verifier 结果验证器
     * @param task     任务描述
     * @param ctx      Graph Task Context
     * @return 真实业务执行结果
     */
    public WorkerOutcome dispatch(WorkerAction action, WorkerVerifier verifier, WorkerTask task,
                                  AgentRunTaskContext ctx) {
        String mode = properties.dispatchMode();
        String correlationId = ctx.runId() + "/" + task.stepId() + "/" + task.attemptId();
        String messageId = UUID.randomUUID().toString();
        // OFF：不经总线，因此也不总线事件（事件本身反映"确实走了一条总线路径"这一事实）。
        if (!AgentProperties.DISPATCH_OFF.equals(mode)) {
            emitBusEvent(ctx, task, mode, messageId, correlationId, "dispatch");
        }
        return switch (mode) {
            case AgentProperties.DISPATCH_SWITCH -> dispatchViaBus(action, verifier, task, ctx, messageId,
                    correlationId);
            case AgentProperties.DISPATCH_BYPASS -> {
                mirrorDispatch(messageId, correlationId, task);
                WorkerOutcome outcome = executor.execute(action, verifier, task, ctx);
                mirrorResult(messageId, correlationId, task, outcome);
                yield outcome;
            }
            default -> executor.execute(action, verifier, task, ctx);
        };
    }

    /**
     * SWITCH：经总线发任务到 Worker Endpoint，收到实际 Worker 的 reply 才结束。
     */
    private WorkerOutcome dispatchViaBus(WorkerAction action, WorkerVerifier verifier, WorkerTask task,
                                         AgentRunTaskContext ctx, String messageId, String correlationId) {
        if (!bus.hasEndpoint(endpoint.endpointId())) {
            return WorkerOutcome.failure(task.role(), task.stepId(), task.attemptId(), false,
                    "BUS_NO_CONSUMER", "Worker 端点未注册，拒绝分派");
        }
        endpoint.registerPending(correlationId,
                new WorkerEndpoint.PendingExecution(action, verifier, task, ctx));
        CompletableFuture<BusMessage.WorkerResult> reply = new CompletableFuture<>();
        String subscriptionId = bus.subscribe(TOPIC_RESULT, message -> {
            if (message instanceof BusMessage.WorkerResult result
                    && correlationId.equals(result.correlationId())) {
                reply.complete(result);
            }
        });
        try {
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("role", task.role());
            description.put("objective", task.step() == null ? "" : task.step().objective());
            description.put("attempt", task.attempt());
            BusMessage.TaskDispatch dispatch = new BusMessage.TaskDispatch(
                    messageId, correlationId, ctx.runId(), task.stepId(), task.attemptId(),
                    "graph-dispatcher", endpoint.endpointId(), task.role(), description);
            boolean sent = bus.sendToEndpoint(endpoint.endpointId(), dispatch);
            if (!sent) {
                return WorkerOutcome.failure(task.role(), task.stepId(), task.attemptId(), false,
                        "BUS_DELIVERY_FAILED", "任务投递失败（无接收者或队列已满）");
            }
            // 送达 ≠ 受理 ≠ 执行：此处仅记录消息已进入端点队列。
            emitBusEvent(ctx, task, AgentProperties.DISPATCH_SWITCH, messageId, correlationId, "delivered");
            BusMessage.WorkerResult result = reply.get(SWITCH_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            emitBusEvent(ctx, task, AgentProperties.DISPATCH_SWITCH, messageId, correlationId, "result");
            return result.outcome();
        } catch (TimeoutException ex) {
            log.warn("bus_result_timeout runId={} correlationId={}", ctx.runId(), correlationId);
            return WorkerOutcome.failure(task.role(), task.stepId(), task.attemptId(), true,
                    "BUS_RESULT_TIMEOUT", "Worker 结果等待超时");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return WorkerOutcome.failure(task.role(), task.stepId(), task.attemptId(), false,
                    "BUS_INTERRUPTED", "分派被中断");
        } catch (ExecutionException ex) {
            log.warn("bus_result_failed runId={} correlationId={} message={}",
                    ctx.runId(), correlationId, ex.getMessage());
            return WorkerOutcome.failure(task.role(), task.stepId(), task.attemptId(), true,
                    "BUS_RESULT_ERROR", "Worker 结果异常");
        } finally {
            bus.unsubscribe(TOPIC_RESULT, subscriptionId);
        }
    }

    /** BYPASS：只镜像分派信息，旁路监听者不能触发真实执行。 */
    private void mirrorDispatch(String messageId, String correlationId, WorkerTask task) {
        bus.publish(TOPIC_DISPATCH, new BusMessage.DeliveryReceipt(
                messageId, correlationId, null, task.stepId(), task.attemptId(),
                "graph-dispatcher", "bypass-mirror"));
    }

    /** BYPASS：只镜像结果信息（真实执行已由 Executor 完成一次）。 */
    private void mirrorResult(String messageId, String correlationId, WorkerTask task, WorkerOutcome outcome) {
        bus.publish(TOPIC_RESULT, new BusMessage.WorkerResult(
                messageId, correlationId, null, task.stepId(), task.attemptId(),
                "graph-dispatcher", "bypass-mirror", outcome));
    }

    private void emitBusEvent(AgentRunTaskContext ctx, WorkerTask task, String mode, String messageId,
                              String correlationId, String kind) {
        SseEventWriter writer = ctx.writer();
        if (writer == null) {
            return;
        }
        try {
            writer.event("bus.dispatched", new AgentStreamEvents.BusDispatched(
                    ctx.runId(), task.stepId(), task.role(), mode, messageId, correlationId, kind));
        } catch (IOException ignored) {
            // 总线提示为可选信息，发送失败不阻断主流程
        }
    }
}
