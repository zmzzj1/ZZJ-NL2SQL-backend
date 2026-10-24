package com.queryforge.application.agent.runtime.bus;

import com.queryforge.application.agent.runtime.WorkerOutcome;

import java.util.Map;

/**
 * P14-ARCH-01：In-JVM Agent 消息总线的消息族（自主设计，不照搬参考实现）。
 *
 * <p>三种语义严格区分，不得混用：
 * <ul>
 *   <li>{@link DeliveryReceipt}：仅表示消息消费者的 callback 收到事件（送达）；</li>
 *   <li>{@link TaskAck}/{@link TaskNack}：Worker 已解析并接纳/拒绝受理（受理）；</li>
 *   <li>{@link WorkerResult}：真实业务执行结果（执行）。</li>
 * </ul>
 *
 * <p>所有消息都携带唯一 {@code messageId} 与稳定 {@code correlationId}
 * （runId/stepId/attemptId 维度），重投保持 correlationId 并由 Worker 去重。
 */
public sealed interface BusMessage permits BusMessage.TaskDispatch, BusMessage.DeliveryReceipt,
        BusMessage.TaskAck, BusMessage.TaskNack, BusMessage.WorkerResult {

    String messageId();

    String correlationId();

    Long runId();

    Long stepId();

    String attemptId();

    String sender();

    String receiver();

    /** 任务分派：Dispatcher → Worker Endpoint（受限任务描述）。 */
    record TaskDispatch(
            String messageId,
            String correlationId,
            Long runId,
            Long stepId,
            String attemptId,
            String sender,
            String receiver,
            String role,
            Map<String, Object> taskDescription
    ) implements BusMessage {
    }

    /** 送达回执：总线消费者收到事件（不代表受理，更不代表执行）。 */
    record DeliveryReceipt(
            String messageId,
            String correlationId,
            Long runId,
            Long stepId,
            String attemptId,
            String sender,
            String receiver
    ) implements BusMessage {
    }

    /** 受理回执：Worker 已解析并接纳任务。 */
    record TaskAck(
            String messageId,
            String correlationId,
            Long runId,
            Long stepId,
            String attemptId,
            String sender,
            String receiver
    ) implements BusMessage {
    }

    /** 拒受回执：Worker 拒绝受理（带原因，不执行业务）。 */
    record TaskNack(
            String messageId,
            String correlationId,
            Long runId,
            Long stepId,
            String attemptId,
            String sender,
            String receiver,
            String reason
    ) implements BusMessage {
    }

    /** 真实业务执行结果（唯一表示“业务已完成”的消息）。 */
    record WorkerResult(
            String messageId,
            String correlationId,
            Long runId,
            Long stepId,
            String attemptId,
            String sender,
            String receiver,
            WorkerOutcome outcome
    ) implements BusMessage {
    }
}
