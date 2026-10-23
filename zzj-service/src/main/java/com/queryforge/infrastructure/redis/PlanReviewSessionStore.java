package com.queryforge.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P14-D：计划级 HITL 待审核会话的短期授权上下文（RBucket + TTL + RLock）。
 *
 * <p>与 SQL 审批 {@link ApprovalSessionStore} 使用完全独立的 key 命名空间（{@code agent:planreview:}），
 * 避免两个层次混淆。步骤进入 WAITING_PLAN_REVIEW 时登记一个短生命周期令牌
 * （runId/userId/conversationId/workspaceId/connectionId/version/route），用户提交审核决定前先消费该令牌：
 * RLock 保护下读取并删除，保证同一待审核版本不会被并发决定消费两次；令牌缺失（过期或已消费）时拒绝继续。
 *
 * <p>边界：MySQL {@code agent_runs} 的原子 CAS（version 条件更新）仍是双消费的最终保证；
 * Redis 令牌提供 TTL 过期与跨实例快速协调。Redis 整体不可用时返回 {@link Claim#UNAVAILABLE}，
 * 由 DB CAS 独立裁决，不伪造"已领取"。
 */
@Component
public class PlanReviewSessionStore {

    private static final Logger log = LoggerFactory.getLogger(PlanReviewSessionStore.class);
    /** 待审核令牌 TTL：短生命周期；过期后需重新发起运行（DB 步骤不会无限悬挂可执行）。 */
    private static final Duration TTL = Duration.ofMinutes(30);
    /** 待审核令牌上限 64 KiB（实际负载远低于此）。 */
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    public PlanReviewSessionStore(RedissonClient redisson, ObjectMapper objectMapper) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
    }

    /** 令牌消费结果（Redis 层；FORBIDDEN/STALE_VERSION/EXPIRED 由服务映射为最终 HTTP 语义）。 */
    public enum Claim {
        /** 令牌有效且已被本次请求消费。 */
        CLAIMED,
        /** 用户不是该 Run 的所属用户（越权）。 */
        FORBIDDEN,
        /** 令牌不存在（未登记、已过期或已被消费）。 */
        EXPIRED,
        /** 令牌记录的版本与本次提交的版本不一致。 */
        STALE_VERSION,
        /** Redis 不可用或锁争用，无法裁决（由 DB CAS 兜底）。 */
        UNAVAILABLE
    }

    /** 登记待审核令牌（步骤进入 WAITING_PLAN_REVIEW 时）。幂等覆盖写同一 run+version。 */
    public void register(Long runId, Long userId, Long conversationId, Long workspaceId, Long connectionId,
                         int planReviewVersion, String route) {
        if (runId == null || userId == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", runId);
            payload.put("userId", userId);
            payload.put("conversationId", conversationId);
            payload.put("workspaceId", workspaceId);
            payload.put("connectionId", connectionId);
            payload.put("planReviewVersion", planReviewVersion);
            payload.put("route", route);
            payload.put("issuedAt", Instant.now().toString());
            String json = objectMapper.writeValueAsString(payload);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                log.warn("plan_review_session_too_large runId={} version={}", runId, planReviewVersion);
                return;
            }
            redisson.<String>getBucket(key(runId, planReviewVersion))
                    .set(json, TTL);
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("plan_review_session_register_failed runId={} version={} message={}",
                    runId, planReviewVersion, ex.getMessage());
        }
    }

    /**
     * 消费待审核令牌（RLock 保护的一次性读取+删除）。
     * 返回 {@link Claim#CLAIMED} 时调用方获得继续执行的短期授权上下文；
     * 返回 {@link Claim#FORBIDDEN}/{@link Claim#STALE_VERSION}/{@link Claim#EXPIRED} 时拒绝；
     * 返回 {@link Claim#UNAVAILABLE} 时交回 DB CAS 裁决。
     */
    public Claim claim(Long runId, Long userId, int planReviewVersion) {
        if (runId == null || userId == null) {
            return Claim.EXPIRED;
        }
        RLock lock = redisson.getLock(lockKey(runId, planReviewVersion));
        boolean acquired = false;
        try {
            acquired = lock.tryLock(2, 5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Claim.UNAVAILABLE;
        } catch (RuntimeException ex) {
            log.warn("plan_review_session_lock_failed runId={} version={} message={}",
                    runId, planReviewVersion, ex.getMessage());
            return Claim.UNAVAILABLE;
        }
        if (!acquired) {
            return Claim.UNAVAILABLE;
        }
        try {
            RBucket<String> bucket = redisson.getBucket(key(runId, planReviewVersion));
            String json = bucket.get();
            if (json == null) {
                return Claim.EXPIRED;
            }
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            Long sessionUserId = toLong(payload.get("userId"));
            int sessionVersion = (Integer) payload.getOrDefault("planReviewVersion", 0);
            if (sessionUserId == null || !sessionUserId.equals(userId)) {
                return Claim.FORBIDDEN;
            }
            if (sessionVersion != planReviewVersion) {
                return Claim.STALE_VERSION;
            }
            bucket.delete();
            return Claim.CLAIMED;
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("plan_review_session_claim_failed runId={} version={} message={}",
                    runId, planReviewVersion, ex.getMessage());
            return Claim.UNAVAILABLE;
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException ignored) {
                // 锁有租约 TTL 兜底
            }
        }
    }

    /** 删除某个待审核版本的令牌（拒绝/取消/终态/可重试恢复时）。 */
    public void remove(Long runId, int planReviewVersion) {
        if (runId == null) {
            return;
        }
        try {
            redisson.getBucket(key(runId, planReviewVersion)).delete();
        } catch (RuntimeException ex) {
            log.warn("plan_review_session_remove_failed runId={} version={} message={}",
                    runId, planReviewVersion, ex.getMessage());
        }
    }

    /** 删除某运行全部待审核令牌（运行取消/终态）。 */
    public void removeRun(Long runId) {
        if (runId == null) {
            return;
        }
        try {
            redisson.getKeys().deleteByPattern("agent:planreview:" + runId + ":*");
        } catch (RuntimeException ex) {
            log.warn("plan_review_session_remove_run_failed runId={} message={}", runId, ex.getMessage());
        }
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String key(Long runId, int planReviewVersion) {
        return "agent:planreview:" + runId + ":" + planReviewVersion;
    }

    private static String lockKey(Long runId, int planReviewVersion) {
        return "agent:planreview:lock:" + runId + ":" + planReviewVersion;
    }
}
