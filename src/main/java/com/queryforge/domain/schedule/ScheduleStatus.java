package com.queryforge.domain.schedule;

/**
 * 定时调度的计划状态与触发运行状态机。
 *
 * 计划状态（Schedule.status）：ACTIVE / PAUSED，独立于运行状态。
 * 触发状态（Occurrence.state）推进：SCHEDULED -> CLAIMED -> WAITING_APPROVAL | RUNNING -> SUCCEEDED | FAILED | CANCELED | SKIPPED。
 * 终态不可被迟到的 Worker 覆写。
 *
 * 安全红线：调度器只「创建待审批 Run」，绝不代替审批人同意 SQL；
 * 修复后的 SQL 不继承旧批准；重复领取由数据库唯一约束 + 条件更新保证。
 */
public final class ScheduleStatus {

    private ScheduleStatus() {
    }

    /** 计划启停状态。 */
    public enum Schedule {
        ACTIVE, PAUSED;

        public boolean enabled() {
            return this == ACTIVE;
        }
    }

    /** 单次触发的运行状态。 */
    public enum Occurrence {
        SCHEDULED, CLAIMED, WAITING_APPROVAL, RUNNING, SUCCEEDED, FAILED, CANCELED, SKIPPED;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELED || this == SKIPPED;
        }
    }

    /** 错过触发策略。 */
    public enum MisfirePolicy {
        SKIP, FIRE_ONCE
    }

    /** 重叠执行策略。 */
    public enum OverlapPolicy {
        SKIP_WHILE_RUNNING, QUEUE_ONE
    }

    /** 触发来源。 */
    public enum TriggerSource {
        SCHEDULER, MANUAL
    }
}
