package com.queryforge.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P12 S4 场景一：Agent 计划短期记忆（RBucket + TTL + RLock）。
 *
 * <p>保存一次运行（run）的计划步骤快照与推进进度，TTL 到期自动失效；
 * 同一 run 的读-改-写由 RLock 保护，避免并发审批请求互相覆盖进度。
 * 计划的事实来源仍是 MySQL {@code agent_steps}；Redis 只作短期状态与快速读取，
 * 不可用时相关操作静默跳过，不影响运行主流程。
 */
@Component
public class AgentPlanStore {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanStore.class);
    /** 计划快照 TTL：运行结束后由终态路径显式删除；异常残留由 TTL 兜底。 */
    private static final Duration TTL = Duration.ofHours(6);

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    public AgentPlanStore(RedissonClient redisson, ObjectMapper objectMapper) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
    }

    /** 计划步骤快照（sequence + objective + 已完成标记）。 */
    public record PlanStep(int sequence, String objective, boolean completed) {
    }

    public record PlanSnapshot(long runId, List<PlanStep> steps) {

        public static PlanSnapshot of(long runId, List<PlanStep> steps) {
            return new PlanSnapshot(runId, List.copyOf(steps));
        }

        public PlanSnapshot withCompleted(int sequence) {
            List<PlanStep> updated = new ArrayList<>();
            for (PlanStep step : steps) {
                updated.add(new PlanStep(step.sequence(), step.objective(),
                        step.completed() || step.sequence() == sequence));
            }
            return new PlanSnapshot(runId, List.copyOf(updated));
        }
    }

    /** 保存计划快照（覆盖写）。 */
    public void save(String runKey, PlanSnapshot snapshot) {
        try {
            redisson.<String>getBucket(key(runKey)).set(encode(snapshot), TTL);
        } catch (RuntimeException ex) {
            log.warn("agent_plan_save_failed runKey={} message={}", runKey, ex.getMessage());
        }
    }

    /** 读取计划快照；不存在/已过期/Redis 不可用为空。 */
    public Optional<PlanSnapshot> load(String runKey) {
        try {
            String json = redisson.<String>getBucket(key(runKey)).get();
            return json == null ? Optional.empty() : Optional.of(decode(json));
        } catch (RuntimeException ex) {
            log.warn("agent_plan_load_failed runKey={} message={}", runKey, ex.getMessage());
            return Optional.empty();
        }
    }

    /** 删除计划快照（运行终态）。 */
    public void remove(String runKey) {
        try {
            redisson.getBucket(key(runKey)).delete();
        } catch (RuntimeException ex) {
            log.warn("agent_plan_remove_failed runKey={} message={}", runKey, ex.getMessage());
        }
    }

    /**
     * 在 RLock 保护下执行读-改-写（如同一 run 的进度推进）。
     * 锁等待有界；未获取到锁时动作不执行（由 DB 状态保证正确性）。
     */
    public void withLock(String runKey, Runnable action) {
        RLock lock = redisson.getLock(lockKey(runKey));
        boolean acquired = false;
        try {
            acquired = lock.tryLock(2, 5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException ex) {
            log.warn("agent_plan_lock_failed runKey={} message={}", runKey, ex.getMessage());
            return;
        }
        if (!acquired) {
            return;
        }
        try {
            action.run();
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

    private String encode(PlanSnapshot snapshot) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", snapshot.runId());
            payload.put("steps", snapshot.steps().stream()
                    .map(step -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("sequence", step.sequence());
                        item.put("objective", step.objective());
                        item.put("completed", step.completed());
                        return item;
                    })
                    .toList());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("计划快照序列化失败", ex);
        }
    }

    private PlanSnapshot decode(String json) {
        try {
            Map<String, Object> payload = objectMapper.readValue(json, new TypeReference<>() {
            });
            long runId = ((Number) payload.get("runId")).longValue();
            List<?> raw = (List<?>) payload.getOrDefault("steps", List.of());
            List<PlanStep> steps = new ArrayList<>();
            for (Object item : raw) {
                Map<?, ?> map = (Map<?, ?>) item;
                steps.add(new PlanStep(
                        ((Number) map.get("sequence")).intValue(),
                        String.valueOf(map.get("objective")),
                        Boolean.TRUE.equals(map.get("completed"))));
            }
            return new PlanSnapshot(runId, List.copyOf(steps));
        } catch (Exception ex) {
            throw new IllegalStateException("计划快照反序列化失败", ex);
        }
    }

    private static String key(String runKey) {
        return "agent:plan:" + runKey;
    }

    private static String lockKey(String runKey) {
        return "agent:plan:lock:" + runKey;
    }
}
