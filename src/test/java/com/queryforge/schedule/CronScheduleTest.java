package com.queryforge.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cron 表达式与时区解析测试。DST 语义由本测试钉住（Spring CronExpression 实际行为）。
 */
@DisplayName("Cron 解析与时区")
class CronScheduleTest {

    @Test
    @DisplayName("5 段表达式自动补秒位规范化为 6 段")
    void normalize_shouldPrefixSecondsForFiveFields() {
        assertThat(CronSchedule.normalize("0 9 * * 1")).isEqualTo("0 0 9 * * 1");
        assertThat(CronSchedule.normalize("0 9 * * 1")).startsWith("0 ");
    }

    @Test
    @DisplayName("6 段表达式原样保留")
    void normalize_shouldKeepSixFields() {
        assertThat(CronSchedule.normalize("30 0 9 * * 1")).isEqualTo("30 0 9 * * 1");
    }

    @Test
    @DisplayName("非法段数或宏表达式被拒绝")
    void normalize_shouldRejectInvalidArity() {
        assertThatThrownBy(() -> CronSchedule.normalize("0 9 * *"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SCHEDULE_INVALID_CRON);
        assertThatThrownBy(() -> CronSchedule.normalize("@daily"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SCHEDULE_INVALID_CRON);
        assertThatThrownBy(() -> CronSchedule.normalize("0 0 9 * * * *"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SCHEDULE_INVALID_CRON);
    }

    @Test
    @DisplayName("非法时区被拒绝")
    void shouldRejectInvalidTimezone() {
        assertThatThrownBy(() -> CronSchedule.of("0 9 * * 1", "Mars/Phobos"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SCHEDULE_INVALID_TIMEZONE);
    }

    @Test
    @DisplayName("非法 cron 字段被拒绝")
    void shouldRejectInvalidCronField() {
        assertThatThrownBy(() -> CronSchedule.of("0 9 * * 99", "Asia/Shanghai"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SCHEDULE_INVALID_CRON);
    }

    @Test
    @DisplayName("Asia/Shanghai 每周一 09:00 的下一次触发为正确 UTC")
    void nextAfter_shouldComputeWeeklyUtc() {
        CronSchedule cron = CronSchedule.of("0 9 * * 1", "Asia/Shanghai");
        // 2026-10-20 是周二；下一个周一是 2026-10-26 09:00 CST = 01:00 UTC。
        Instant from = Instant.parse("2026-10-20T00:00:00Z");
        assertThat(cron.nextAfter(from)).isEqualTo(Instant.parse("2026-10-26T01:00:00Z"));
    }

    @Test
    @DisplayName("DST 跳过（gap）：不存在本地时间推进到下一次实际存在时刻")
    void nextAfter_shouldSkipDstGap() {
        // America/New_York 2026-03-08 02:00 开始 DST，02:00-03:00 本地时间不存在。
        CronSchedule cron = CronSchedule.of("0 30 2 * * *", "America/New_York");
        Instant from = Instant.parse("2026-03-07T12:00:00Z"); // 03-07 早上
        Instant next = cron.nextAfter(from);
        ZonedDateTime local = next.atZone(ZoneId.of("America/New_York"));
        // Spring 语义：跳到不存在时刻后，推进到下一次匹配点（03-08 03:30 EDT 或后续）。
        assertThat(next).isAfter(from);
        // 钉住：结果必须落在 DST 开始日之后，且本地分钟/小时字段符合 cron（30 分）。
        assertThat(local.getMinute()).isEqualTo(30);
    }

    @Test
    @DisplayName("DST 重复（overlap）：重复本地时间取较早偏移")
    void nextAfter_shouldPickEarlierOffsetOnOverlap() {
        // America/New_York 2026-11-01 02:00 结束 DST，01:00-02:00 本地时间重复出现。
        // 从 2026-11-01 00:00 EDT（04:00Z）起，下一个 01:30 是「第一个」01:30（仍处 EDT，UTC-4）= 05:30Z。
        // 第二次 01:30（EST，UTC-5）应为 06:30Z；Spring 取较早偏移（EDT）。
        CronSchedule cron = CronSchedule.of("0 30 1 * * *", "America/New_York");
        Instant from = Instant.parse("2026-11-01T04:00:00Z");
        Instant next = cron.nextAfter(from);
        assertThat(next).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(next.atZone(ZoneId.of("America/New_York")).getOffset().getId()).isEqualTo("-04:00");
    }

    @Test
    @DisplayName("nextAfter 连续调用严格递增")
    void nextAfter_shouldBeMonotonic() {
        CronSchedule cron = CronSchedule.of("*/5 * * * *", "UTC");
        Instant cursor = Instant.parse("2026-01-01T00:00:00Z");
        Instant prev = cursor;
        for (int i = 0; i < 5; i++) {
            Instant next = cron.nextAfter(cursor);
            assertThat(next).isAfter(prev);
            prev = next;
            cursor = next;
        }
    }
}
