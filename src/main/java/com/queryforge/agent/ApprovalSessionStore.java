package com.queryforge.agent;

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
import org.redisson.api.RKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P12 S4 场景二：HITL 待审批会话的短期授权上下文（RBucket + TTL + RLock）。
 *
 * <p>步骤进入 WAITING_APPROVAL 时登记一个短生命周期令牌（runId/stepId/sqlVersion/database），
 * 用户继续执行前先消费该令牌：RLock 保护下读取并删除，保证同一待审批令牌不会在并发
 * 继续请求中被消费两次；令牌缺失（过期或已被消费）时拒绝继续。
 *
 * <p>边界：MySQL {@code agent_steps} 的原子 claim（sqlVersion 条件更新）仍是双消费的
 * 最终保证；Redis 令牌提供 TTL 过期与跨实例快速协调。Redis 整体不可用时返回
 * {@link Claim#UNAVAILABLE}，由 DB claim 独立裁决，不伪造"已加锁"。
 */
@Component
public class ApprovalSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ApprovalSessionStore.class);
    /** 待审批令牌 TTL：短生命周期；过期后需重新发起运行（DB 步骤不会无限悬挂可执行）。 */
    private static final Duration TTL = Duration.ofHours(6);

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    public ApprovalSessionStore(RedissonClient redisson, ObjectMapper objectMapper) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
    }

    /** 令牌消费结果。 */
    public enum Claim {
        /** 令牌有效且已被本次请求消费。 */
        CLAIMED,
        /** 令牌不存在（未登记、已过期或已被消费）。 */
        MISSING,
        /** Redis 不可用或锁争用，无法裁决（由 DB claim 兜底）。 */
        UNAVAILABLE
    }

    /** 登记待审批令牌（步骤进入 WAITING_APPROVAL / SQL 修复后重新待审批时）。 */
    public void register(Long runId, Long stepId, int sqlVersion, String database) {
        if (runId == null || stepId == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", runId);
            payload.put("stepId", stepId);
            payload.put("sqlVersion", sqlVersion);
            payload.put("database", database);
            payload.put("issuedAt", Instant.now().toString());
            redisson.<String>getBucket(key(runId, stepId))
                    .set(objectMapper.writeValueAsString(payload), TTL);
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("approval_session_register_failed runId={} stepId={} message={}",
                    runId, stepId, ex.getMessage());
        }
    }

    /**
     * 消费待审批令牌（RLock 保护的一次性读取+删除）。
     * 返回 {@link Claim#CLAIMED} 时调用方获得继续执行的短期授权上下文。
     */
    public Claim claim(Long runId, Long stepId) {
        if (runId == null || stepId == null) {
            return Claim.MISSING;
        }
        RLock lock = redisson.getLock(lockKey(runId, stepId));
        boolean acquired = false;
        try {
            acquired = lock.tryLock(2, 5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Claim.UNAVAILABLE;
        } catch (RuntimeException ex) {
            log.warn("approval_session_lock_failed runId={} stepId={} message={}",
                    runId, stepId, ex.getMessage());
            return Claim.UNAVAILABLE;
        }
        if (!acquired) {
            // 并发继续请求正在消费同一令牌：不阻塞业务，交回 DB claim 裁决。
            return Claim.UNAVAILABLE;
        }
        try {
            RBucket<String> bucket = redisson.getBucket(key(runId, stepId));
            if (bucket.get() == null) {
                return Claim.MISSING;
            }
            bucket.delete();
            return Claim.CLAIMED;
        } catch (RuntimeException ex) {
            log.warn("approval_session_claim_failed runId={} stepId={} message={}",
                    runId, stepId, ex.getMessage());
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

    /** 删除单个步骤的令牌（拒绝/取消/终态）。 */
    public void remove(Long runId, Long stepId) {
        if (runId == null || stepId == null) {
            return;
        }
        try {
            redisson.getBucket(key(runId, stepId)).delete();
        } catch (RuntimeException ex) {
            log.warn("approval_session_remove_failed runId={} stepId={} message={}",
                    runId, stepId, ex.getMessage());
        }
    }

    /** 删除某运行的全部令牌（运行取消/终态）。 */
    public void removeRun(Long runId) {
        if (runId == null) {
            return;
        }
        try {
            RKeys keys = redisson.getKeys();
            keys.deleteByPattern("agent:approval:" + runId + ":*");
        } catch (RuntimeException ex) {
            log.warn("approval_session_remove_run_failed runId={} message={}", runId, ex.getMessage());
        }
    }

    private static String key(Long runId, Long stepId) {
        return "agent:approval:" + runId + ":" + stepId;
    }

    private static String lockKey(Long runId, Long stepId) {
        return "agent:approval:lock:" + runId + ":" + stepId;
    }
}
