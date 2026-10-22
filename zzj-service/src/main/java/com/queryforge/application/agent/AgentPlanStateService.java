package com.queryforge.application.agent;

import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.infrastructure.redis.AgentPlanStore;
import com.queryforge.infrastructure.redis.AgentPlanStore.PlanStep;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * P12 S4：Agent 计划短期状态应用服务。
 *
 * <p>事实来源仍是 MySQL {@code agent_steps}；Redis 快照只承担运行中的计划视图与进度，
 * 所有操作 fail-soft：Redis 不可用时静默跳过，不影响运行主流程与审批幂等。
 */
@Service
public class AgentPlanStateService {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanStateService.class);

    private final AgentPlanStore planStore;

    public AgentPlanStateService(AgentPlanStore planStore) {
        this.planStore = planStore;
    }

    /** 场景一：保存计划快照（TTL 6h；终态路径删除）。 */
    public void save(Long runId, List<AgentStep> steps) {
        try {
            List<PlanStep> planSteps = steps.stream()
                    .map(step -> new PlanStep(step.sequence(), step.objective(),
                            step.status() == StepStatus.SUCCEEDED))
                    .toList();
            planStore.save(String.valueOf(runId), AgentPlanStore.PlanSnapshot.of(runId, planSteps));
        } catch (RuntimeException ex) {
            log.debug("agent_plan_snapshot_skipped runId={} message={}", runId, ex.getMessage());
        }
    }

    /** 场景一：推进计划快照进度（RLock 保护的读-改-写；Redis 不可用时跳过）。 */
    public void advance(Long runId, int completedSequence) {
        try {
            planStore.withLock(String.valueOf(runId), () -> planStore.load(String.valueOf(runId)).ifPresent(
                    snapshot -> planStore.save(String.valueOf(runId), snapshot.withCompleted(completedSequence))));
        } catch (RuntimeException ex) {
            log.debug("agent_plan_progress_skipped runId={} message={}", runId, ex.getMessage());
        }
    }

    /** 场景一：运行终态清理计划快照。 */
    public void clear(Long runId) {
        try {
            planStore.remove(String.valueOf(runId));
        } catch (RuntimeException ex) {
            log.debug("agent_plan_clear_skipped runId={} message={}", runId, ex.getMessage());
        }
    }
}
