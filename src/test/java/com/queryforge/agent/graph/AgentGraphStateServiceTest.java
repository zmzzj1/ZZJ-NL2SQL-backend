package com.queryforge.agent.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.agent.AgentRunRepository;
import com.queryforge.query.execution.SseEventWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A5/A6：统一状态图协调器的确定性测试。覆盖检查点持久化、SSE 节点事件、
 * 终态进入、非法迁移拒绝与恢复（load）。不依赖外部 StateGraph，直接验证图状态机。
 */
@ExtendWith(MockitoExtension.class)
class AgentGraphStateServiceTest {

    @Mock
    private AgentRunRepository repository;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, String> store = new ConcurrentHashMap<>();
    private AgentGraphStateService service;

    /** 用真实 SseEventWriter 写入内存流，解析出事件名序列供断言。 */
    static final class CapturingSink {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final SseEventWriter writer;

        CapturingSink(ObjectMapper mapper) {
            this.writer = new SseEventWriter(out, mapper);
        }

        List<String> eventNames() {
            String content = new String(out.toByteArray(), StandardCharsets.UTF_8);
            List<String> names = new ArrayList<>();
            for (String frame : content.split("\n\n")) {
                for (String line : frame.split("\n")) {
                    if (line.startsWith("event: ")) {
                        names.add(line.substring("event: ".length()).trim());
                    }
                }
            }
            return names;
        }
    }

    @BeforeEach
    void setup() {
        lenient().doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(repository).upsertGraphCheckpoint(anyLong(), anyString(), anyLong());
        lenient().when(repository.findGraphCheckpointJson(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        service = new AgentGraphStateService(repository, mapper);
    }

    @Test
    @DisplayName("初始化写入 CREATED 检查点，load 可恢复")
    void initializeCreatesRecoverableCheckpoint() {
        service.initialize(1L, 10L, 20L, 30L, 40L, 50L, "{}", 8000);

        Optional<AgentGraphState> loaded = service.load(1L);
        assertTrue(loaded.isPresent(), "初始化后必须能恢复检查点");
        assertEquals(GraphNode.CREATED, loaded.get().node());
        assertEquals(10L, loaded.get().ownerUserId());
        assertEquals(40L, loaded.get().agentId());
    }

    @Test
    @DisplayName("进入 ROUTING 发出 node.started 并持久化")
    void enterEmitsNodeStartedAndPersists() {
        service.initialize(2L, 10L, 20L, 30L, 40L, 50L, "{}", 8000);
        CapturingSink sink = new CapturingSink(mapper);

        service.enter(2L, GraphNode.ROUTING, sink.writer);

        assertTrue(sink.eventNames().contains("node.started"), "必须发出 node.started 事件");
        AgentGraphState loaded = service.load(2L).orElseThrow();
        assertEquals(GraphNode.ROUTING, loaded.node());
        assertEquals(2L, loaded.stateRevision()); // CREATED(1) -> ROUTING(2)
    }

    @Test
    @DisplayName("从 CONTEXT_BUILDING 进入 RUNNING 时先发前驱 node.completed 再发 node.started")
    void enterEmitsPriorCompletedThenStarted() {
        service.initialize(3L, 10L, 20L, 30L, 40L, 50L, "{}", 8000);
        service.enter(3L, GraphNode.ROUTING, new CapturingSink(mapper).writer);
        service.enter(3L, GraphNode.PLANNING, new CapturingSink(mapper).writer);
        service.enter(3L, GraphNode.CONTEXT_BUILDING, new CapturingSink(mapper).writer);
        CapturingSink sink = new CapturingSink(mapper);

        service.enter(3L, GraphNode.RUNNING, sink.writer);

        List<String> names = sink.eventNames();
        int completedIdx = names.indexOf("node.completed");
        int startedIdx = names.indexOf("node.started");
        assertTrue(completedIdx >= 0, "必须发出前驱 CONTEXT_BUILDING 的 node.completed");
        assertTrue(startedIdx >= 0, "必须发出 node.started(RUNNING)");
        assertTrue(completedIdx < startedIdx, "先完成前驱节点再进入新节点");
        assertEquals(GraphNode.RUNNING, service.load(3L).orElseThrow().node());
    }

    @Test
    @DisplayName("进入终态 COMPLETED 同时发出 node.started 与 node.completed")
    void terminalNodeEmitsBothStartedAndCompleted() {
        service.initialize(4L, 10L, 20L, 30L, 40L, 50L, "{}", 8000);
        service.enter(4L, GraphNode.ROUTING, new CapturingSink(mapper).writer);
        service.enter(4L, GraphNode.PLANNING, new CapturingSink(mapper).writer);
        service.enter(4L, GraphNode.CONTEXT_BUILDING, new CapturingSink(mapper).writer);
        service.enter(4L, GraphNode.RUNNING, new CapturingSink(mapper).writer);
        CapturingSink sink = new CapturingSink(mapper);

        service.enter(4L, GraphNode.COMPLETED, sink.writer);

        List<String> names = sink.eventNames();
        assertTrue(names.contains("node.started"));
        assertTrue(names.contains("node.completed"));
        assertEquals(GraphNode.COMPLETED, service.load(4L).orElseThrow().node());
    }

    @Test
    @DisplayName("进入 WAITING_SQL_APPROVAL 后 markWaitingApproval 发出 node.waiting_approval")
    void waitingApprovalEmitsWaitingEvent() {
        service.initialize(5L, 10L, 20L, 30L, 40L, 50L, "{}", 8000);
        service.enter(5L, GraphNode.ROUTING, new CapturingSink(mapper).writer);
        service.enter(5L, GraphNode.PLANNING, new CapturingSink(mapper).writer);
        service.enter(5L, GraphNode.CONTEXT_BUILDING, new CapturingSink(mapper).writer);
        service.enter(5L, GraphNode.RUNNING, new CapturingSink(mapper).writer);
        service.enter(5L, GraphNode.SQL_GENERATION, new CapturingSink(mapper).writer);
        CapturingSink sink = new CapturingSink(mapper);

        service.enter(5L, GraphNode.WAITING_SQL_APPROVAL, sink.writer);
        service.markWaitingApproval(5L, GraphNode.WAITING_SQL_APPROVAL, sink.writer);

        assertTrue(sink.eventNames().contains("node.waiting_approval"));
    }

    @Test
    @DisplayName("非法迁移（CREATED 直接到 COMPLETED）被拒绝且检查点不变")
    void illegalTransitionRejected() {
        service.initialize(6L, 10L, 20L, 30L, 40L, 50L, "{}", 8000);

        assertThrows(IllegalStateException.class,
                () -> service.enter(6L, GraphNode.COMPLETED, new CapturingSink(mapper).writer));
        assertEquals(GraphNode.CREATED, service.load(6L).orElseThrow().node(), "非法迁移后检查点应保持 CREATED");
    }

    @Test
    @DisplayName("终态后拒绝迟到回调迁移（P1104/P1119 硬门槛）")
    void lateCallbackAfterTerminalRejected() {
        service.initialize(7L, 10L, 20L, 30L, 40L, 50L, "{}", 8000);
        service.enter(7L, GraphNode.ROUTING, new CapturingSink(mapper).writer);
        service.enter(7L, GraphNode.COMPLETED, new CapturingSink(mapper).writer);

        assertThrows(IllegalStateException.class,
                () -> service.enter(7L, GraphNode.PLANNING, new CapturingSink(mapper).writer));
        assertEquals(GraphNode.COMPLETED, service.load(7L).orElseThrow().node());
    }

    @Test
    @DisplayName("无检查点时 load 返回空（恢复安全失败，不冒充成功 P1130）")
    void loadReturnsEmptyWhenAbsent() {
        assertTrue(service.load(999L).isEmpty());
    }
}
