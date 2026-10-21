package com.queryforge.application.agent.roles;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.roles.AgentRole;

/**
 * 角色委派：把规划步骤/用户意图映射到负责的专业角色（指南 §6）。
 * 真实协作而非前端贴标签：Manager 路由后，复杂任务由 Planner 分解，每一步按语义分配角色。
 */
@Service
public class RoleAssigner {

    private static final List<String> REPORT_HINTS = List.of(
            "报告", "简报", "汇报", "生成文档", "总结成", "report", "brief");
    private static final List<String> ANALYSIS_HINTS = List.of(
            "分析", "诊断", "趋势", "波动", "可视化", "相关", "聚类", "预测", "回归", "analyze", "chart", "trend");
    private static final List<String> SQL_HINTS = List.of(
            "查询", "取数", "统计销售额", "订单", "sql", "select", "指标", "明细", "列表");

    public AgentRole assign(String objective) {
        if (objective == null || objective.isBlank()) {
            return AgentRole.DATA_ANALYST;
        }
        String lower = objective.toLowerCase(Locale.ROOT);
        if (matches(lower, REPORT_HINTS)) {
            return AgentRole.REPORT_ASSISTANT;
        }
        if (matches(lower, ANALYSIS_HINTS)) {
            return AgentRole.CODE_ANALYST;
        }
        if (matches(lower, SQL_HINTS)) {
            return AgentRole.DATA_ANALYST;
        }
        return AgentRole.DATA_ANALYST;
    }

    private static boolean matches(String lower, List<String> hints) {
        for (String hint : hints) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
