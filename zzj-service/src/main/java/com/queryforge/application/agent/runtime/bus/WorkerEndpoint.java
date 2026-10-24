package com.queryforge.application.agent.runtime.bus;

import com.queryforge.application.agent.runtime.AgentRunTaskContext;
import com.queryforge.application.agent.runtime.WorkerOutcome;
import com.queryforge.application.agent.runtime.worker.WorkerAction;
import com.queryforge.application.agent.runtime.worker.WorkerExecutor;
import com.queryforge.application.agent.runtime.worker.WorkerTask;
import com.queryforge.application.agent.runtime.worker.WorkerVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P14-ARCH-01：Worker 端点（SWITCH 模式下经总线接收任务并真实执行）。
 *
 * <p>去重契约：以 {@code correlationId}（runId/stepId/attemptId 维度）为幂等键，
 * 已处理过的 correlationId 直接重放缓存的 WorkerResult，不重复调用真实业务。
 * ACK 之后执行结果未知时禁止换 correlationId 盲重试真实业务——由 MySQL claim 与
 * Graph checkpoint 共同保证唯一执行。
 *
 * <p>执行闭包（Action/Verifier/Task/Context）只在进程内随消息传递，不进入序列化消息体；
 * 消息本身只携带受限任务描述。迟到/重复消息按 correlationId 去重。
 */
public class WorkerEndpoint {

    private static final Logger log = LoggerFactory.getLogger(WorkerEndpoint.class);
    private static final int MAX_PROCESSED_CORRELATIONS = 4096;
    private static final String TOPIC_RESULT = "agent.bus.result";
    private static final String TOPIC_ACK = "agent.bus.ack";

    private final String endpointId;
    private final AgentMessageBus bus;
    private final WorkerExecutor executor;
    private final Map<String, PendingExecution> pending = new ConcurrentHashMap<>();
    private final Map<String, WorkerOutcome> processedResults = new ConcurrentHashMap<>();

    public WorkerEndpoint(String endpointId, AgentMessageBus bus, WorkerExecutor executor) {
        this.endpointId = endpointId;
        this.bus = bus;
        this.executor = executor;
    }

    public String endpointId() {
        return endpointId;
    }

    /** 一次待执行任务的进程内闭包（随 SWITCH 分派登记，消费后即取走）。 */
    public record PendingExecution(WorkerAction action, WorkerVerifier verifier, WorkerTask task,
                                   AgentRunTaskContext ctx) {
    }

    /** 登记待执行闭包（分派前调用；与消息投递同一线程顺序保证）。 */
    public void registerPending(String correlationId, PendingExecution execution) {
        pending.put(correlationId, execution);
    }

    /**
     * 处理一条 TaskDispatch：送达 → 受理（ACK）→ 执行 → 结果（发布到总线）。
     * 重复 correlationId 直接重放已缓存结果（不重复执行）。
     */
    public BusMessage.WorkerResult handle(BusMessage.TaskDispatch dispatch) {
        WorkerOutcome cached = processedResults.get(dispatch.correlationId());
        if (cached != null) {
            log.info("worker_endpoint_replay endpoint={} correlationId={}",
                    endpointId, dispatch.correlationId());
            return publishResult(dispatch, cached);
        }
        PendingExecution execution = pending.remove(dispatch.correlationId());
        if (execution == null) {
            // 没有对应执行闭包：拒受（不执行业务，不猜测任务）。
            bus.publish(TOPIC_ACK, new BusMessage.TaskNack(
                    UUID.randomUUID().toString(), dispatch.correlationId(), dispatch.runId(), dispatch.stepId(),
                    dispatch.attemptId(), endpointId, dispatch.sender(), "无待执行任务上下文"));
            return null;
        }
        // 受理回执：Worker 已解析并接纳任务（尚未执行业务）。
        bus.publish(TOPIC_ACK, new BusMessage.TaskAck(
                UUID.randomUUID().toString(), dispatch.correlationId(), dispatch.runId(), dispatch.stepId(),
                dispatch.attemptId(), endpointId, dispatch.sender()));
        WorkerOutcome outcome = executor.execute(execution.action(), execution.verifier(),
                execution.task(), execution.ctx());
        processedResults.put(dispatch.correlationId(), outcome);
        if (processedResults.size() > MAX_PROCESSED_CORRELATIONS) {
            processedResults.clear();
        }
        return publishResult(dispatch, outcome);
    }

    /** 已缓存的执行结果（供迟到/重复消息重放）。 */
    public WorkerOutcome cachedResult(String correlationId) {
        return processedResults.get(correlationId);
    }

    private BusMessage.WorkerResult publishResult(BusMessage.TaskDispatch dispatch, WorkerOutcome outcome) {
        BusMessage.WorkerResult result = new BusMessage.WorkerResult(
                UUID.randomUUID().toString(), dispatch.correlationId(), dispatch.runId(), dispatch.stepId(),
                dispatch.attemptId(), endpointId, dispatch.sender(), outcome);
        bus.publish(TOPIC_RESULT, result);
        return result;
    }
}
