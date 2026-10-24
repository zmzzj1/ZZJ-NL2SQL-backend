package com.queryforge.application.agent.runtime.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * P14-ARCH-01：In-JVM 消息总线（进程内 pub/sub + 点对点端点队列）。
 *
 * <p>职责边界：Graph 管调度位置/断点，Bus 仅传递任务；MySQL/CAS 负责持久化去重；
 * 每步仅一个真实的 Action 执行者。不因本地 Bus 宣传成跨进程可靠消息队列：
 * 进程重启即丢失在途消息（恢复以 Graph checkpoint 为准）。
 *
 * <p>旁路监听者（BYPASS 模式）只能收到事件镜像，不能通过本总线触发真实业务执行。
 */
public class AgentMessageBus {

    private static final Logger log = LoggerFactory.getLogger(AgentMessageBus.class);
    /** 端点队列容量有界，超出时拒绝新任务（快速失败，不无限堆积）。 */
    private static final int ENDPOINT_QUEUE_CAPACITY = 256;

    private final Map<String, CopyOnWriteArrayList<Subscription>> topicListeners =
            new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<BusMessage>> endpointQueues = new ConcurrentHashMap<>();
    private final java.util.Set<String> consumingEndpoints = ConcurrentHashMap.newKeySet();

    /** 订阅记录（id + 消费者）。 */
    private record Subscription(String id, Consumer<BusMessage> listener) {

        @Override
        public String toString() {
            return id;
        }
    }

    /** 订阅主题（旁路镜像/观测）。返回订阅 id。 */
    public String subscribe(String topic, Consumer<BusMessage> listener) {
        String subscriptionId = UUID.randomUUID().toString();
        topicListeners.computeIfAbsent(topic, key -> new CopyOnWriteArrayList<>())
                .add(new Subscription(subscriptionId, listener));
        return subscriptionId;
    }

    public void unsubscribe(String topic, String subscriptionId) {
        List<Subscription> listeners = topicListeners.get(topic);
        if (listeners != null && listeners.removeIf(sub -> subscriptionId.equals(sub.id()))) {
            if (listeners.isEmpty()) {
                topicListeners.remove(topic);
            }
        }
    }

    /**
     * 发布事件到主题的全部监听者（广播语义）。
     *
     * @return 实际收到事件的监听者数量（送达计数，不代表受理/执行）
     */
    public int publish(String topic, BusMessage message) {
        List<Subscription> listeners = topicListeners.get(topic);
        if (listeners == null || listeners.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (Subscription subscription : listeners) {
            try {
                subscription.listener().accept(message);
                delivered++;
            } catch (RuntimeException ex) {
                log.warn("bus_listener_failed topic={} messageId={} message={}",
                        topic, message.messageId(), ex.getMessage());
            }
        }
        return delivered;
    }

    /** 注册 Worker 端点（SWITCH 模式的真实执行接收方）。 */
    public void registerEndpoint(String endpointId) {
        endpointQueues.computeIfAbsent(endpointId, key -> new LinkedBlockingQueue<>(ENDPOINT_QUEUE_CAPACITY));
    }

    public boolean hasEndpoint(String endpointId) {
        return endpointQueues.containsKey(endpointId);
    }

    /**
     * 点对点投递到 Worker 端点队列。
     *
     * @return true=已入队（送达）；false=无接收者或队列已满
     */
    public boolean sendToEndpoint(String endpointId, BusMessage message) {
        BlockingQueue<BusMessage> queue = endpointQueues.get(endpointId);
        if (queue == null) {
            return false;
        }
        boolean offered = queue.offer(message);
        if (!offered) {
            log.warn("bus_endpoint_queue_full endpoint={} messageId={}", endpointId, message.messageId());
        }
        return offered;
    }

    /** 从端点队列取消息（Worker 侧阻塞消费）。 */
    public BusMessage takeFromEndpoint(String endpointId, long timeout, TimeUnit unit)
            throws InterruptedException {
        BlockingQueue<BusMessage> queue = endpointQueues.get(endpointId);
        if (queue == null) {
            return null;
        }
        return queue.poll(timeout, unit);
    }

    /**
     * 启动端点消费线程（守护线程，幂等：同一 endpoint 只启动一次）。
     * 进程重启即丢失在途消息——恢复以 Graph checkpoint 为准，不依赖 Bus 持久化。
     */
    public void startConsumer(String endpointId, java.util.function.Consumer<BusMessage> handler) {
        BlockingQueue<BusMessage> queue = endpointQueues.get(endpointId);
        if (queue == null) {
            return;
        }
        if (!consumingEndpoints.add(endpointId)) {
            return;
        }
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    BusMessage message = queue.poll(1, TimeUnit.SECONDS);
                    if (message != null) {
                        handler.accept(message);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException ex) {
                    log.warn("bus_consumer_failed endpoint={} message={}", endpointId, ex.getMessage());
                }
            }
        }, "agent-bus-consumer-" + endpointId);
        thread.setDaemon(true);
        thread.start();
    }
}
