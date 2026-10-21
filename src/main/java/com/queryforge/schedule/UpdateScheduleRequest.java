package com.queryforge.schedule;

import jakarta.validation.constraints.NotNull;

/**
 * 更新计划请求：修改 cron/时区/策略/启停状态等；revision 由路径参数传入做乐观锁。
 * workflowId/connectionId 不可变更（计划绑定固定工作流与数据源）。
 */
public record UpdateScheduleRequest(
        String cron,
        String timezone,
        String misfirePolicy,
        String overlapPolicy,
        String status,
        String userInput
) {
    public boolean hasCron() {
        return cron != null && !cron.isBlank();
    }

    public boolean hasTimezone() {
        return timezone != null && !timezone.isBlank();
    }
}
