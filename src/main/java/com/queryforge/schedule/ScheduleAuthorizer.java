package com.queryforge.schedule;

import com.queryforge.config.RuntimeConfigResolver;
import com.queryforge.datasource.ConnectionService;
import com.queryforge.schedule.ScheduleRepository.ScheduleRow;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.workflow.WorkflowRepository;
import com.queryforge.workspace.WorkspaceAccessPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 定时计划触发前的统一重鉴权。被 ScheduleService（创建/手动触发）与 ScheduleTriggerRunner（自动触发）共同复用，
 * 避免两者重复判权逻辑或形成循环依赖。
 *
 * 每次触发实时查库判权：撤权/资源失效立即生效，不缓存旧授权。
 */
@Service
public class ScheduleAuthorizer {

    private final WorkflowRepository workflowRepository;
    private final ConnectionService connectionService;
    private final WorkspaceAccessPolicy workspaceAccessPolicy;
    private final RuntimeConfigResolver configResolver;

    public ScheduleAuthorizer(WorkflowRepository workflowRepository, ConnectionService connectionService,
                              WorkspaceAccessPolicy workspaceAccessPolicy, RuntimeConfigResolver configResolver) {
        this.workflowRepository = workflowRepository;
        this.connectionService = connectionService;
        this.workspaceAccessPolicy = workspaceAccessPolicy;
        this.configResolver = configResolver;
    }

    /** 完整重鉴权，任一失败抛 ApiException。 */
    public void requireAuthorized(ScheduleRow schedule) {
        workflowRepository.findWorkflowOwned(schedule.workflowId(), schedule.ownerUserId())
                .filter(w -> "ACTIVE".equals(w.status()))
                .orElseThrow(() -> new ApiException(ErrorCode.SCHEDULE_FORBIDDEN, HttpStatus.FORBIDDEN,
                        "工作流不存在、已停用或无权访问"));
        connectionService.summary(schedule.ownerUserId(), schedule.connectionId());
        if (schedule.workspaceId() != null) {
            workspaceAccessPolicy.requireMember(schedule.workspaceId(), schedule.ownerUserId());
        }
        if (schedule.agentVersionId() != null) {
            configResolver.resolvePublishedByVersion(schedule.ownerUserId(), schedule.agentVersionId());
        }
    }

    /** 重鉴权失败返回错误码（供调度器标记 SKIPPED），成功返回 null。 */
    public String errorIfUnauthorized(ScheduleRow schedule) {
        try {
            requireAuthorized(schedule);
            return null;
        } catch (ApiException ex) {
            return ex.getErrorCode().name();
        }
    }
}
