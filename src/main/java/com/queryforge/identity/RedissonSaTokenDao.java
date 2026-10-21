package com.queryforge.identity;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.auto.SaTokenDaoByObjectFollowString;
import cn.dev33.satoken.util.SaFoxUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * P12 S2：Sa-Token 的 Redis 持久化实现（应用内唯一登录态存储）。
 *
 * <p>字符串键值通道（get/set/update/delete/getTimeout/updateTimeout/searchData）用 Redisson
 * {@link RBucket} 直接落地，TTL 透传为 Redis 过期时间；对象与会话层由 Sa-Token 官方
 * {@link SaTokenDaoByObjectFollowString} 默认适配派生（JSON 序列化走 SaManager 序列化器，
 * 随 starter 的 sa-token-jackson 插件提供），键约定与官方实现完全一致。
 *
 * <p>不引入 spring-data-redis 第二套连接：复用 S4 已有的 RedissonClient（应用内唯一 Redis
 * 协调入口）。Redis 不可用时操作直接抛错，由上层 fail-closed 处理——不冒充登录、不放行匿名
 * 请求、不沿用任何本地缓存身份。
 */
@Component
public class RedissonSaTokenDao implements SaTokenDaoByObjectFollowString {

    /** Sa-Token 约定：值永不过期。 */
    private static final long NEVER_EXPIRE = SaTokenDao.NEVER_EXPIRE;

    private final RedissonClient redissonClient;

    public RedissonSaTokenDao(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public String get(String key) {
        RBucket<String> bucket = redissonClient.getBucket(key);
        return bucket.get();
    }

    @Override
    public void set(String key, String value, long timeout) {
        // 与 SaTimedCache 官方语义一致：0 或 NOT_VALUE_EXPIRE(-2) 不写入。
        if (timeout == 0 || timeout <= SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        RBucket<String> bucket = redissonClient.getBucket(key);
        if (timeout == NEVER_EXPIRE) {
            bucket.set(value);
        } else {
            bucket.set(value, timeout, TimeUnit.SECONDS);
        }
    }

    @Override
    public void update(String key, String value) {
        // update 契约：保留原 TTL。先读剩余存活时间再按剩余时间写回，避免续期被误清零。
        RBucket<String> bucket = redissonClient.getBucket(key);
        long remainMs = bucket.remainTimeToLive();
        if (remainMs == -2 || remainMs == 0) {
            // update 不创建新键；不存在或恰好过期时保持缺失。
            return;
        }
        if (remainMs == -1) {
            // 原键永久有效，更新后仍保持永久。
            bucket.set(value);
            return;
        }
        bucket.set(value, remainMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void delete(String key) {
        redissonClient.getBucket(key).delete();
    }

    @Override
    public long getTimeout(String key) {
        long remainMs = redissonClient.getBucket(key).remainTimeToLive();
        // Sa-Token 约定：-2 = 键不存在，-1 = 永久；其余为剩余秒数。
        if (remainMs == -1) {
            return -1;
        }
        if (remainMs == -2 || remainMs == 0) {
            return -2;
        }
        return remainMs / 1000;
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        RBucket<String> bucket = redissonClient.getBucket(key);
        if (timeout == NEVER_EXPIRE) {
            bucket.clearExpire();
        } else {
            bucket.expire(timeout, TimeUnit.SECONDS);
        }
    }

    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        // 前缀扫描走 SCAN（getKeysByPattern），不用 KEYS：大库下 KEYS 会阻塞 Redis。
        RKeys keys = redissonClient.getKeys();
        List<String> candidates = new ArrayList<>();
        for (String key : keys.getKeysByPattern(prefix + "*")) {
            candidates.add(key);
        }
        // Sa-Token 契约检索的是 key，不是 value；复用官方工具保持 keyword、分页、size=-1
        // 与 sortType 的语义一致。
        return SaFoxUtil.searchList(candidates, prefix, keyword, start, size, sortType);
    }
}
