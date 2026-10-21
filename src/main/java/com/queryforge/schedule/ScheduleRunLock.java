package com.queryforge.schedule;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * P12 S4 场景四：定时任务互斥（Redisson RLock）。
 * 按 scheduleId 加锁，避免两个应用实例在同一时刻运行相同任务；只作并发争用缓解，
 * 最终幂等仍由 {@code ScheduleRepository.claimOccurrence} 的 DB 唯一约束 + 事务领取保证。
 */
@Component
public class ScheduleRunLock {

    private final RedissonClient redisson;

    public ScheduleRunLock(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /** 尝试立即获取某 schedule 的执行锁（不等待）。返回是否获得。 */
    public boolean tryLock(long scheduleId, Duration lease) {
        try {
            RLock lock = redisson.getLock(lockKey(scheduleId));
            return lock.tryLock(0, lease.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException ex) {
            // Redis 不可用时 fail closed：不返回“已加锁”，避免误判互斥已建立
            return false;
        }
    }

    public void unlock(long scheduleId) {
        try {
            RLock lock = redisson.getLock(lockKey(scheduleId));
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException ignored) {
            // 解锁失败不影响业务结果（锁有租约 TTL 兜底）
        }
    }

    private static String lockKey(long scheduleId) {
        return "schedule:lock:" + scheduleId;
    }
}
