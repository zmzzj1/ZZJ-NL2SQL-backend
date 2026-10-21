package com.queryforge.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.queryforge.domain.conversation.ConversationDetailResponse;
import com.queryforge.domain.conversation.ConversationMessageResponse;
import com.queryforge.domain.conversation.ConversationSummaryResponse;
import com.queryforge.domain.conversation.CreateConversationRequest;
import com.queryforge.shared.pagination.PageResponse;
import com.queryforge.domain.datasource.ConnectionConfig;
import com.queryforge.domain.conversation.Conversation;
import com.queryforge.domain.conversation.ConversationMessage;
import com.queryforge.domain.conversation.MessageRole;
import com.queryforge.domain.conversation.MessageStatus;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.infrastructure.persistence.ConnectionRepository;
import com.queryforge.infrastructure.persistence.ConversationRepository;
import com.queryforge.infrastructure.redis.ModelSessionAffinity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("会话服务")
class ConversationServiceTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;
    private static final long CONNECTION_ID = 10L;
    private static final long CONVERSATION_ID = 20L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 6, 0);

    @Mock
    private ConversationRepository repository;

    @Mock
    private ConnectionRepository connectionRepository;

    @Mock
    private ModelSessionAffinity modelSessionAffinity;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationService(repository, connectionRepository, modelSessionAffinity);
    }

    @Test
    @DisplayName("创建会话时使用本人连接的名称快照")
    void create_shouldSnapshotOwnedConnectionName() {
        when(connectionRepository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.of(connection()));
        when(repository.insert(USER_ID, CONNECTION_ID, "A 商店库", "统计订单数量")).thenReturn(CONVERSATION_ID);
        when(repository.findOwned(CONVERSATION_ID, USER_ID)).thenReturn(Optional.of(conversation("统计订单数量")));

        ConversationSummaryResponse response =
                service.create(USER_ID, new CreateConversationRequest(CONNECTION_ID, "统计订单数量"));

        assertThat(response.id()).isEqualTo(CONVERSATION_ID);
        assertThat(response.connectionName()).isEqualTo("A 商店库");
        assertThat(response.title()).isEqualTo("统计订单数量");
    }

    @Test
    @DisplayName("创建会话时不接受他人的连接")
    void create_shouldRejectForeignConnection() {
        when(connectionRepository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER_ID, new CreateConversationRequest(CONNECTION_ID, null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_FOUND));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("空标题使用默认标题，超长标题被截断")
    void create_shouldNormalizeTitle() {
        when(connectionRepository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.of(connection()));
        when(repository.insert(USER_ID, CONNECTION_ID, "A 商店库", "新会话")).thenReturn(CONVERSATION_ID);
        when(repository.findOwned(CONVERSATION_ID, USER_ID)).thenReturn(Optional.of(conversation("新会话")));

        ConversationSummaryResponse response =
                service.create(USER_ID, new CreateConversationRequest(CONNECTION_ID, "  "));

        assertThat(response.title()).isEqualTo("新会话");
    }

    @Test
    @DisplayName("读取他人会话返回 CONVERSATION_NOT_FOUND")
    void detail_shouldRejectForeignConversation() {
        when(repository.findOwned(CONVERSATION_ID, OTHER_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(OTHER_USER_ID, CONVERSATION_ID))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("会话详情按时间正序返回消息，并收敛超时未完成状态")
    void detail_shouldReturnMessagesInOrderAndReapStale() {
        when(repository.findOwned(CONVERSATION_ID, USER_ID)).thenReturn(Optional.of(conversation("标题")));
        when(repository.findRecentMessages(CONVERSATION_ID, 201)).thenReturn(List.of(
                message(2L, MessageRole.ASSISTANT, "SELECT 1", MessageStatus.COMPLETED),
                message(1L, MessageRole.USER, "统计", MessageStatus.COMPLETED)));

        ConversationDetailResponse detail = service.detail(USER_ID, CONVERSATION_ID);

        assertThat(detail.messages()).extracting(ConversationMessageResponse::id).containsExactly(1L, 2L);
        assertThat(detail.messagesTruncated()).isFalse();
        verify(repository).failStaleMessages(eq(CONVERSATION_ID), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("消息超过上限时只返回最近 200 条并标记截断")
    void detail_shouldTruncateMessages() {
        when(repository.findOwned(CONVERSATION_ID, USER_ID)).thenReturn(Optional.of(conversation("标题")));
        List<ConversationMessage> recent = java.util.stream.LongStream.rangeClosed(1, 201)
                .mapToObj(index -> message(index, MessageRole.USER, "q" + index, MessageStatus.COMPLETED))
                .sorted((left, right) -> Long.compare(right.id(), left.id()))
                .toList();
        when(repository.findRecentMessages(CONVERSATION_ID, 201)).thenReturn(recent);

        ConversationDetailResponse detail = service.detail(USER_ID, CONVERSATION_ID);

        assertThat(detail.messages()).hasSize(200);
        assertThat(detail.messagesTruncated()).isTrue();
        assertThat(detail.messages().get(0).id()).isEqualTo(2L);
        assertThat(detail.messages().get(199).id()).isEqualTo(201L);
    }

    @Test
    @DisplayName("分页有更多数据时返回 nextCursor")
    void list_shouldReturnNextCursor_whenMoreRowsExist() {
        when(repository.findPage(USER_ID, 3, null, null)).thenReturn(List.of(
                conversation(3L, "最近"), conversation(2L, "其次"), conversation(1L, "最早")));

        PageResponse<ConversationSummaryResponse> page = service.list(USER_ID, 2, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNotBlank();
    }

    @Test
    @DisplayName("cursor 非法时返回 VALIDATION_ERROR")
    void list_shouldRejectInvalidCursor() {
        assertThatThrownBy(() -> service.list(USER_ID, 20, "not-a-cursor"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("生成开始时先写入用户问题与 IN_PROGRESS 助手消息")
    void beginGeneration_shouldPersistUserAndPendingAssistant() {
        when(repository.findOwned(CONVERSATION_ID, USER_ID)).thenReturn(Optional.of(conversation("标题")));
        when(repository.insertMessage(
                CONVERSATION_ID, MessageRole.USER, "统计订单数量", MessageStatus.COMPLETED, null)).thenReturn(1L);
        when(repository.insertMessage(
                CONVERSATION_ID, MessageRole.ASSISTANT, "", MessageStatus.IN_PROGRESS, "req-1")).thenReturn(2L);

        ConversationService.GenerationTicket ticket =
                service.beginGeneration(USER_ID, CONVERSATION_ID, CONNECTION_ID, "统计订单数量", "req-1");

        assertThat(ticket.assistantMessageId()).isEqualTo(2L);
        verify(repository).touch(CONVERSATION_ID);
    }

    @Test
    @DisplayName("会话与当前数据源不一致时拒绝生成，避免串库")
    void beginGeneration_shouldRejectConnectionMismatch() {
        when(repository.findOwned(CONVERSATION_ID, USER_ID))
                .thenReturn(Optional.of(conversation(20L, CONNECTION_ID + 1, "另一个库", "标题", NOW, NOW)));

        assertThatThrownBy(() ->
                service.beginGeneration(USER_ID, CONVERSATION_ID, CONNECTION_ID, "统计", "req-1"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                });
        verifyNoInteractions(connectionRepository);
    }

    @Test
    @DisplayName("数据源已删除的会话不允许继续生成")
    void beginGeneration_shouldRejectDeletedConnection() {
        when(repository.findOwned(CONVERSATION_ID, USER_ID))
                .thenReturn(Optional.of(conversation(20L, null, "已删连接", "标题", NOW, NOW)));

        assertThatThrownBy(() ->
                service.beginGeneration(USER_ID, CONVERSATION_ID, CONNECTION_ID, "统计", "req-1"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("客户端取消按 requestId 幂等收敛，空 requestId 不触库")
    void cancelByRequestId_shouldDelegate() {
        service.cancelByRequestId(USER_ID, "req-1");
        verify(repository).cancelPendingByRequestId(USER_ID, "req-1");

        service.cancelByRequestId(USER_ID, "   ");
        verify(repository, org.mockito.Mockito.times(1)).cancelPendingByRequestId(USER_ID, "req-1");
    }

    @Test
    @DisplayName("删除会话时按归属校验，未命中返回 404")
    void delete_shouldRejectMissingOrForeignConversation() {
        when(repository.delete(CONVERSATION_ID, OTHER_USER_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.delete(OTHER_USER_ID, CONVERSATION_ID))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));
    }

    @Test
    @DisplayName("删除会话成功后解除该会话的模型亲和性绑定（P12 S4）")
    void delete_shouldUnbindModelAffinity() {
        when(repository.delete(CONVERSATION_ID, USER_ID)).thenReturn(1);

        service.delete(USER_ID, CONVERSATION_ID);

        verify(modelSessionAffinity).unbind(USER_ID, CONVERSATION_ID);
    }

    private static ConnectionConfig connection() {
        return new ConnectionConfig(
                CONNECTION_ID, USER_ID, "A 商店库", "mysql", "127.0.0.1", 3306,
                "sql_demo_a", "demo_a_reader", "cipher", NOW, NOW);
    }

    private static Conversation conversation(String title) {
        return conversation(CONVERSATION_ID, CONNECTION_ID, "A 商店库", title, NOW, NOW);
    }

    private static Conversation conversation(long id, String title) {
        return conversation(id, CONNECTION_ID, "A 商店库", title, NOW, NOW);
    }

    private static Conversation conversation(long id, Long connectionId, String connectionName,
                                             String title, LocalDateTime createdAt, LocalDateTime lastActivityAt) {
        return new Conversation(id, USER_ID, connectionId, connectionName, title, createdAt, lastActivityAt);
    }

    private static ConversationMessage message(Long id, MessageRole role, String content, MessageStatus status) {
        return new ConversationMessage(id, CONVERSATION_ID, role, content, status, null, NOW);
    }
}
