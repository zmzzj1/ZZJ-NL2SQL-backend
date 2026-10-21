package com.queryforge.infrastructure.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryforge.testutil.RedissonFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

/**
 * P12 S2：RedissonSaTokenDao 的真实 Redis 验证（P1207）。
 *
 * <p>只验证字符串通道（对象/会话层由 Sa-Token 官方默认适配派生，键约定一致）；
 * Redis 不可达时跳过，不冒充成功。所有键带专属前缀，结束后清理。
 */
class RedissonSaTokenDaoTest {

    private static final String KEY_PREFIX = "sa-token-test:";

    private RedissonClient redisson;
    private RedissonSaTokenDao dao;

    @BeforeEach
    void setUp() {
        redisson = RedissonFixtures.liveRedissonOrSkip();
        dao = new RedissonSaTokenDao(redisson);
    }

    @AfterEach
    void tearDown() {
        if (redisson != null) {
            redisson.getKeys().deleteByPattern(KEY_PREFIX + "*");
            redisson.shutdown();
        }
    }

    private String key() {
        return KEY_PREFIX + UUID.randomUUID();
    }

    @Test
    @DisplayName("set + get 完成字符串往返")
    void setThenGetRoundTrips() {
        String key = key();

        dao.set(key, "v1", 600);

        assertThat(dao.get(key)).isEqualTo("v1");
    }

    @Test
    @DisplayName("set 遇到 0/-2 超时不写入；update 不创建缺失键")
    void invalidTimeoutAndMissingUpdateDoNotCreateKeys() {
        String zeroTimeout = key();
        String notValueExpire = key();
        String missingUpdate = key();

        dao.set(zeroTimeout, "v1", 0);
        dao.set(notValueExpire, "v2", -2);
        dao.update(missingUpdate, "v3");

        assertThat(dao.get(zeroTimeout)).isNull();
        assertThat(dao.get(notValueExpire)).isNull();
        assertThat(dao.get(missingUpdate)).isNull();
    }

    @Test
    @DisplayName("TTL 透传到 Redis，delete 后键消失")
    void ttlAndDeleteBehave() {
        String key = key();
        dao.set(key, "v1", 600);

        long timeout = dao.getTimeout(key);
        assertThat(timeout).isBetween(1L, 600L);

        dao.delete(key);

        assertThat(dao.get(key)).isNull();
        assertThat(dao.getTimeout(key)).isEqualTo(-2);
    }

    @Test
    @DisplayName("update 保留原 TTL，不把过期时间清零")
    void updateKeepsRemainingTtl() {
        String key = key();
        dao.set(key, "v1", 600);

        dao.update(key, "v2");

        assertThat(dao.get(key)).isEqualTo("v2");
        assertThat(dao.getTimeout(key)).isBetween(1L, 600L);
    }

    @Test
    @DisplayName("updateTimeout 可续期；永不过期值 getTimeout 返回 -1")
    void updateTimeoutWorks() {
        String key = key();
        dao.set(key, "v1", 600);
        dao.updateTimeout(key, 1200);
        assertThat(dao.getTimeout(key)).isBetween(600L, 1200L);

        dao.updateTimeout(key, -1);
        assertThat(dao.getTimeout(key)).isEqualTo(-1);
    }

    @Test
    @DisplayName("searchData 按前缀 SCAN 命中，关键字过滤与分页生效")
    void searchDataScansPrefix() {
        String prefix = KEY_PREFIX + "scan:";
        String keyA = prefix + "alpha";
        String keyB = prefix + "beta";
        String keyC = prefix + "gamma";
        dao.set(keyA, "same-value", 600);
        dao.set(keyB, "same-value", 600);
        dao.set(keyC, "same-value", 600);

        List<String> all = dao.searchData(prefix, "", 0, 100, false);
        assertThat(all).containsExactlyInAnyOrder(keyA, keyB, keyC);

        // 关键字按 key 过滤，这是 Sa-Token searchData 的官方契约。
        List<String> filtered = dao.searchData(prefix, "et", 0, 100, false);
        assertThat(filtered).containsExactly(keyB);

        List<String> page = dao.searchData(prefix, "", 0, 2, false);
        assertThat(page).hasSize(2);

        List<String> unlimited = dao.searchData(prefix, "", 0, -1, true);
        assertThat(unlimited).containsExactlyInAnyOrder(keyA, keyB, keyC);
    }
}
