package com.queryforge.application.conversation;
import com.queryforge.shared.pagination.Cursor;

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
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话业务边界：归属校验、分页、消息状态维护。
 * 生成状态只在真实完成时写 COMPLETED，断开/失败不会形成假成功记录。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MESSAGE_LIMIT = 200;
    private static final int STALE_MINUTES = 5;
    private static final int TITLE_MAX_CHARS = 60;
    private static final String DEFAULT_TITLE = "新会话";

    private final ConversationRepository repository;
    private final ConnectionRepository connectionRepository;
    private final com.queryforge.infrastructure.redis.ModelSessionAffinity modelSessionAffinity;

    public ConversationService(ConversationRepository repository, ConnectionRepository connectionRepository,
                               com.queryforge.infrastructure.redis.ModelSessionAffinity modelSessionAffinity) {
        this.repository = repository;
        this.connectionRepository = connectionRepository;
        this.modelSessionAffinity = modelSessionAffinity;
    }

    public ConversationSummaryResponse create(Long userId, CreateConversationRequest request) {
        ConnectionConfig connection = connectionRepository.findOwned(request.connectionId(), userId)
                .orElseThrow(ConversationService::connectionNotFound);
        String title = normalizeTitle(request.title());
        Long id = repository.insert(userId, connection.id(), connection.name(), title);
        Conversation created = requireOwned(id, userId);
        return toSummary(created);
    }

    public PageResponse<ConversationSummaryResponse> list(Long userId, Integer rawLimit, String rawCursor) {
        int limit = normalizeLimit(rawLimit);
        Cursor cursor = Cursor.decode(rawCursor);
        List<Conversation> rows = repository.findPage(
                userId,
                limit + 1,
                cursor == null ? null : cursor.time(),
                cursor == null ? null : cursor.id());
        boolean hasMore = rows.size() > limit;
        List<Conversation> page = hasMore ? rows.subList(0, limit) : rows;
        List<ConversationSummaryResponse> items = page.stream().map(ConversationService::toSummary).toList();
        String nextCursor = hasMore
                ? new Cursor(page.get(page.size() - 1).lastActivityAt(), page.get(page.size() - 1).id()).encode()
                : null;
        return new PageResponse<>(items, nextCursor);
    }

    public ConversationDetailResponse detail(Long userId, Long id) {
        Conversation conversation = requireOwned(id, userId);
        repository.failStaleMessages(id, LocalDateTime.now().minusMinutes(STALE_MINUTES));
        List<ConversationMessage> recent = repository.findRecentMessages(id, MESSAGE_LIMIT + 1);
        boolean truncated = recent.size() > MESSAGE_LIMIT;
        List<ConversationMessage> selected = truncated ? recent.subList(0, MESSAGE_LIMIT) : recent;
        List<ConversationMessage> ordered = new ArrayList<>(selected);
        Collections.reverse(ordered);
        List<ConversationMessageResponse> messages = ordered.stream()
                .map(message -> new ConversationMessageResponse(
                        message.id(), message.role(), message.content(), message.status(), message.createdAt()))
                .toList();
        return new ConversationDetailResponse(
                conversation.id(),
                conversation.title(),
                conversation.connectionId(),
                conversation.connectionName(),
                conversation.createdAt(),
                conversation.lastActivityAt(),
                messages,
                truncated);
    }

    public void delete(Long userId, Long id) {
        if (repository.delete(id, userId) == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, HttpStatus.NOT_FOUND, "会话不存在或无权访问");
        }
        // P12 S4：会话删除后解除该会话的模型亲和性绑定（fail-soft，不影响删除结果）。
        modelSessionAffinity.unbind(userId, id);
    }

    /** 生成开始：先落用户问题与 IN_PROGRESS 助手消息，再打开上游流。 */
    @Transactional
    public GenerationTicket beginGeneration(Long userId, Long conversationId, Long connectionId,
                                            String question, String requestId) {
        Conversation conversation = requireOwned(conversationId, userId);
        if (conversation.connectionId() == null || !conversation.connectionId().equals(connectionId)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    HttpStatus.BAD_REQUEST,
                    "会话与当前数据源不一致，请新建会话后再提问");
        }
        repository.insertMessage(conversationId, MessageRole.USER, question, MessageStatus.COMPLETED, null);
        Long assistantMessageId = repository.insertMessage(
                conversationId, MessageRole.ASSISTANT, "", MessageStatus.IN_PROGRESS, requestId);
        repository.touch(conversationId);
        return new GenerationTicket(conversationId, assistantMessageId);
    }

    @Transactional
    public void completeGeneration(GenerationTicket ticket, String sql) {
        repository.finishMessage(ticket.assistantMessageId(), sql, MessageStatus.COMPLETED);
        repository.touch(ticket.conversationId());
    }

    @Transactional
    public void cancelGeneration(GenerationTicket ticket, String partialSql) {
        repository.finishMessage(ticket.assistantMessageId(), partialSql, MessageStatus.CANCELED);
        repository.touch(ticket.conversationId());
    }

    @Transactional
    public void failGeneration(GenerationTicket ticket, String partialSql) {
        repository.finishMessage(ticket.assistantMessageId(), partialSql, MessageStatus.FAILED);
        repository.touch(ticket.conversationId());
    }

    /** Agent 运行写入用户问题或最终结论；调用方已完成归属校验。 */
    public void appendMessage(Long conversationId, MessageRole role, String content, MessageStatus status) {
        repository.insertMessage(conversationId, role, content, status, null);
        repository.touch(conversationId);
    }

    public String memorySummary(Long conversationId) {
        return repository.findMemorySummary(conversationId);
    }

    public void updateMemorySummary(Long conversationId, String summary) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        String safe = summary.length() > 1000 ? summary.substring(0, 1000) : summary;
        repository.updateMemorySummary(conversationId, safe);
    }

    /** 最近 N 轮已完成消息（正序、截断），供 Manager 组装多轮上下文。 */
    public List<String> recentCompletedMessages(Long conversationId, int limit) {
        return repository.findRecentMessages(conversationId, limit).stream()
                .filter(message -> message.status() == MessageStatus.COMPLETED)
                .sorted(Comparator.comparingLong(ConversationMessage::id))
                .map(message -> message.role().name() + "：" + truncate(message.content(), 200))
                .toList();
    }

    private static String truncate(String value, int maxChars) {
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }

    /**
     * 客户端取消生成：按 requestId 幂等更新，未命中不报错。
     * 断流检测与超时回收仍作为兜底。
     */
    public void cancelByRequestId(Long userId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        repository.cancelPendingByRequestId(userId, requestId);
    }

    /**
     * 执行前校验会话归属与数据源一致性；未传会话时返回 null。
     */
    public Long requireMatchingConversation(Long userId, Long conversationId, Long connectionId) {
        if (conversationId == null) {
            return null;
        }
        Conversation conversation = requireOwned(conversationId, userId);
        // connectionId 为空表示只校验归属（例如读取某会话的 Agent 运行列表）。
        if (connectionId == null) {
            return conversation.id();
        }
        if (conversation.connectionId() == null || !conversation.connectionId().equals(connectionId)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    HttpStatus.BAD_REQUEST,
                    "会话与当前数据源不一致，请重新选择数据源");
        }
        return conversation.id();
    }

    private Conversation requireOwned(Long id, Long userId) {
        return repository.findOwned(id, userId).orElseThrow(() -> {
            log.debug("conversation_not_found id={} userId={}", id, userId);
            return new ApiException(
                    ErrorCode.CONVERSATION_NOT_FOUND, HttpStatus.NOT_FOUND, "会话不存在或无权访问");
        });
    }

    private static ConversationSummaryResponse toSummary(Conversation conversation) {
        return new ConversationSummaryResponse(
                conversation.id(),
                conversation.title(),
                conversation.connectionId(),
                conversation.connectionName(),
                conversation.createdAt(),
                conversation.lastActivityAt());
    }

    private static String normalizeTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TITLE;
        }
        String title = raw.strip().replaceAll("\\s+", " ");
        return title.length() > TITLE_MAX_CHARS ? title.substring(0, TITLE_MAX_CHARS) : title;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static ApiException connectionNotFound() {
        return new ApiException(ErrorCode.CONNECTION_NOT_FOUND, HttpStatus.NOT_FOUND, "连接不存在或无权访问");
    }

    /** 一次生成对应的会话与助手消息 ID。 */
    public record GenerationTicket(Long conversationId, Long assistantMessageId) {
    }
}
