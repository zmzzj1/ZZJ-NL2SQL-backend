package com.queryforge.query.history;
import com.queryforge.shared.QueryContext;
import com.queryforge.shared.pagination.Cursor;

import com.queryforge.shared.pagination.PageResponse;
import com.queryforge.query.history.QueryHistoryResponse;
import com.queryforge.query.execution.QueryResultResponse;
import com.queryforge.datasource.model.ConnectionTarget;
import com.queryforge.query.history.QueryHistoryRecord;
import com.queryforge.query.history.QueryStatus;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.query.history.QueryHistoryRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 查询历史由后端在执行流程中写入，浏览器无法伪造成功记录。
 * 只保存元数据与脱敏错误摘要，不保存结果行、凭据或异常堆栈。
 */
@Service
public class QueryHistoryService {

    private static final Logger log = LoggerFactory.getLogger(QueryHistoryService.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int ERROR_MESSAGE_MAX_CHARS = 300;

    private final QueryHistoryRepository repository;

    public QueryHistoryService(QueryHistoryRepository repository) {
        this.repository = repository;
    }

    public void recordSuccess(QueryContext context, ConnectionTarget target, String sql,
                              long durationMs, QueryResultResponse result) {
        insertSafely(new QueryHistoryRecord(
                null,
                context.userId(),
                context.runId(),
                context.stepId(),
                context.conversationId(),
                target.id(),
                target.name(),
                sql,
                QueryStatus.SUCCESS,
                LocalDateTime.now(),
                normalizeDuration(durationMs),
                result.rowCount(),
                result.truncated(),
                null,
                null));
    }

    public void recordFailure(QueryContext context, ConnectionTarget target, String sql,
                              long durationMs, ErrorCode errorCode, String errorMessage) {
        insertSafely(new QueryHistoryRecord(
                null,
                context.userId(),
                context.runId(),
                context.stepId(),
                context.conversationId(),
                target.id(),
                target.name(),
                sql,
                QueryStatus.FAILED,
                LocalDateTime.now(),
                normalizeDuration(durationMs),
                null,
                null,
                errorCode.name(),
                truncate(errorMessage, ERROR_MESSAGE_MAX_CHARS)));
    }

    public PageResponse<QueryHistoryResponse> list(Long userId, Integer rawLimit, String rawCursor,
                                                   Long connectionId) {
        int limit = normalizeLimit(rawLimit);
        Cursor cursor = Cursor.decode(rawCursor);
        List<QueryHistoryRecord> rows = repository.findPage(
                userId,
                limit + 1,
                connectionId,
                cursor == null ? null : cursor.time(),
                cursor == null ? null : cursor.id());
        boolean hasMore = rows.size() > limit;
        List<QueryHistoryRecord> page = hasMore ? rows.subList(0, limit) : rows;
        List<QueryHistoryResponse> items = page.stream().map(QueryHistoryService::toResponse).toList();
        String nextCursor = hasMore
                ? new Cursor(page.get(page.size() - 1).executedAt(), page.get(page.size() - 1).id()).encode()
                : null;
        return new PageResponse<>(items, nextCursor);
    }

    public QueryHistoryResponse detail(Long userId, Long id) {
        return repository.findOwned(id, userId)
                .map(QueryHistoryService::toResponse)
                .orElseThrow(QueryHistoryService::notFound);
    }

    public void delete(Long userId, Long id) {
        if (repository.delete(id, userId) == 0) {
            throw notFound();
        }
    }

    /** 历史写入失败不应回滚用户已经看到的查询结果，但必须留痕以便排查。 */
    private void insertSafely(QueryHistoryRecord record) {
        try {
            repository.insert(record);
        } catch (RuntimeException ex) {
            log.error("query_history_write_failed userId={} connectionId={} status={}",
                    record.userId(), record.connectionId(), record.status(), ex);
        }
    }

    private static QueryHistoryResponse toResponse(QueryHistoryRecord record) {
        return new QueryHistoryResponse(
                record.id(),
                record.runId(),
                record.stepId(),
                record.conversationId(),
                record.connectionId(),
                record.connectionName(),
                record.sql(),
                record.status(),
                record.executedAt(),
                record.durationMs(),
                record.rowCount(),
                record.truncated(),
                record.errorCode(),
                record.errorMessage());
    }

    private static int normalizeDuration(long durationMs) {
        if (durationMs < 0) {
            return 0;
        }
        return (int) Math.min(durationMs, Integer.MAX_VALUE);
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.HISTORY_NOT_FOUND, HttpStatus.NOT_FOUND, "查询记录不存在或无权访问");
    }
}
