package com.queryforge.testutil;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * Redisson 测试辅助：
 * {@link #noopRedisson()} 用于不关心 Redis 行为的单元测试（全部操作静默成功/空值）；
 * {@link #liveRedissonOrSkip()} 用于真实 Redis 集成验证，Redis 不可用时跳过（不冒充成功）。
 */
public final class RedissonFixtures {

    private RedissonFixtures() {
    }

    /** 无副作用 Redisson：getBucket 返回空 bucket，set/delete 静默，getLock 立即获得。 */
    public static RedissonClient noopRedisson() {
        RedissonClient client = mock(RedissonClient.class);
        lenient().when(client.<Object>getBucket(anyString())).thenAnswer(invocation -> {
            RBucket<Object> bucket = mock(RBucket.class);
            lenient().when(bucket.get()).thenReturn(null);
            lenient().doNothing().when(bucket).set(any(), any(Duration.class));
            lenient().when(bucket.delete()).thenReturn(false);
            return bucket;
        });
        RLock lock = mock(RLock.class);
        try {
            lenient().when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        lenient().when(lock.isHeldByCurrentThread()).thenReturn(true);
        lenient().when(client.getLock(anyString())).thenReturn(lock);
        return client;
    }

    /** 真实 Redisson 客户端；Redis 不可达时中止测试（assumption 跳过）。 */
    public static RedissonClient liveRedissonOrSkip() {
        if (!reachable()) {
            Assumptions.abort("Redis 127.0.0.1:6379 不可用，跳过 Redisson 集成验证");
        }
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4)
                .setRetryAttempts(1)
                .setRetryInterval(100);
        return Redisson.create(config);
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 1000);
            socket.setSoTimeout(1000);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
