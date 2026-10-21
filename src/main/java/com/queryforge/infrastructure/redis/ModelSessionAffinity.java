package com.queryforge.infrastructure.redis;

import java.time.Duration;
import java.util.Optional;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P12 S4 场景三：模型会话亲和性（短期 Redis 绑定）。
 *
 * <p>同一会话（conversation）内优先复用已绑定的模型 profile，减少同一会话内模型频繁切换；
 * 绑定只是路由偏好，{@link ModelRouter} 每次仍会重新检查该 profile 的类型/启用/授权/熔断状态，
 * 不合格时按正常策略重新路由并换绑。绑定键有 TTL，会话删除或策略变更后自然失效。
 *
 * <p>亲和性是优化而非正确性依赖：Redis 不可用时静默跳过（不绑定、不解析），
 * 路由链路本身不要求亲和性存在。
 */
@Component
public class ModelSessionAffinity {

    private static final Logger log = LoggerFactory.getLogger(ModelSessionAffinity.class);
    /** 会话亲和绑定 TTL：滑动续期，超过该时间未调用的会话自动解除绑定。 */
    private static final Duration TTL = Duration.ofMinutes(30);

    private final RedissonClient redisson;

    public ModelSessionAffinity(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /** 绑定会话使用的模型 profile（滑动 TTL）。 */
    public void bind(Long userId, Long conversationId, Long profileId) {
        if (userId == null || conversationId == null || profileId == null) {
            return;
        }
        try {
            redisson.<Long>getBucket(key(userId, conversationId)).set(profileId, TTL);
        } catch (RuntimeException ex) {
            log.warn("model_affinity_bind_failed userId={} conversationId={} message={}",
                    userId, conversationId, ex.getMessage());
        }
    }

    /** 读取会话绑定的模型 profile；无绑定或 Redis 不可用时为空。 */
    public Optional<Long> resolve(Long userId, Long conversationId) {
        if (userId == null || conversationId == null) {
            return Optional.empty();
        }
        try {
            Long profileId = redisson.<Long>getBucket(key(userId, conversationId)).get();
            return Optional.ofNullable(profileId);
        } catch (RuntimeException ex) {
            log.warn("model_affinity_resolve_failed userId={} conversationId={} message={}",
                    userId, conversationId, ex.getMessage());
            return Optional.empty();
        }
    }

    /** 解除绑定（会话删除等场景）。 */
    public void unbind(Long userId, Long conversationId) {
        if (userId == null || conversationId == null) {
            return;
        }
        try {
            redisson.getBucket(key(userId, conversationId)).delete();
        } catch (RuntimeException ex) {
            log.warn("model_affinity_unbind_failed userId={} conversationId={} message={}",
                    userId, conversationId, ex.getMessage());
        }
    }

    private static String key(Long userId, Long conversationId) {
        return "model:affinity:" + userId + ":" + conversationId;
    }
}
