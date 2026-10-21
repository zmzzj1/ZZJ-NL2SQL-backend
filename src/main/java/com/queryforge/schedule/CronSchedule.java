package com.queryforge.schedule;

import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;

/**
 * Cron 表达式与 IANA 时区的解析、规范化与下次触发计算。
 *
 * 设计：
 *  - 接受 5 段（分 时 日 月 周）或 6 段（秒 分 时 日 月 周）表达式；5 段自动在秒位补 0。
 *  - 时区使用 IANA ZoneId；非法时区立即拒绝。
 *  - 下次触发基于 ZonedDateTime 计算（Spring {@link CronExpression#next(ZonedDateTime)} 要求传入
 *    ZonedDateTime，传入 Instant 会抛 UnsupportedTemporalTypeException）。
 *  - DST 语义由 Spring CronExpression 决定，具体行为由 {@code CronScheduleTest} 钉住：
 *    不存在本地时间（gap）时推进到下一次实际存在时刻；重复本地时间（overlap）时取较早偏移。
 */
public final class CronSchedule {

    private final CronExpression expression;
    private final ZoneId zoneId;
    private final String normalized;

    private CronSchedule(CronExpression expression, ZoneId zoneId, String normalized) {
        this.expression = expression;
        this.zoneId = zoneId;
        this.normalized = normalized;
    }

    public static CronSchedule of(String rawCron, String rawZoneId) {
        String normalized = normalize(rawCron);
        CronExpression parsed;
        try {
            parsed = CronExpression.parse(normalized);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.SCHEDULE_INVALID_CRON, HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cron 表达式不合法");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(rawZoneId.trim());
        } catch (java.time.DateTimeException ex) {
            throw new ApiException(ErrorCode.SCHEDULE_INVALID_TIMEZONE, HttpStatus.UNPROCESSABLE_ENTITY,
                    "时区不合法：" + rawZoneId);
        }
        return new CronSchedule(parsed, zone, normalized);
    }

    /** 5 段补齐秒位，6 段原样；其余（含 7 段、宏如 @daily）拒绝。 */
    public static String normalize(String rawCron) {
        if (rawCron == null || rawCron.isBlank()) {
            throw new ApiException(ErrorCode.SCHEDULE_INVALID_CRON, HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cron 表达式不能为空");
        }
        String[] parts = rawCron.trim().split("\\s+");
        if (parts.length == 5) {
            return "0 " + rawCron.trim();
        }
        if (parts.length == 6) {
            return rawCron.trim();
        }
        throw new ApiException(ErrorCode.SCHEDULE_INVALID_CRON, HttpStatus.UNPROCESSABLE_ENTITY,
                "Cron 表达式需为 5 段（分 时 日 月 周）或 6 段（秒 分 时 日 月 周）");
    }

    /** 返回严格晚于 {@code afterUtc} 的下一次触发 UTC 时刻。 */
    public Instant nextAfter(Instant afterUtc) {
        ZonedDateTime zoned = afterUtc.atZone(zoneId);
        ZonedDateTime next = expression.next(zoned);
        if (next == null) {
            throw new ApiException(ErrorCode.SCHEDULE_INVALID_CRON, HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cron 表达式无法计算出下一次触发时间");
        }
        return next.toInstant();
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    public String normalized() {
        return normalized;
    }
}
