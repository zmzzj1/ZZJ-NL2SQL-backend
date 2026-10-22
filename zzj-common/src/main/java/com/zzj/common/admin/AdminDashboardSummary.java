package com.zzj.common.admin;

import java.io.Serializable;

/**
 * P13-M3A：平台统计概览。
 *
 * <p>只承载当前持久化里真实可算的指标（用户数、管理员数、近 24 小时活跃用户、
 * SQL 执行量、失败执行量、Agent 运行量）。没有真实来源的指标不占位、不填常量假数据。
 * 所有时间窗口由 Provider 按数据库时间计算，本对象只承载结果。
 */
public final class AdminDashboardSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    private long userTotal;
    private long adminTotal;
    private long activeUsers24h;
    private long queryExecutionsTotal;
    private long queryExecutions24h;
    private long failedExecutions24h;
    private long agentRunsTotal;
    private long agentRuns24h;
    private String generatedAt;

    public AdminDashboardSummary() {
    }

    public AdminDashboardSummary(long userTotal, long adminTotal, long activeUsers24h, long queryExecutionsTotal,
                                 long queryExecutions24h, long failedExecutions24h, long agentRunsTotal,
                                 long agentRuns24h, String generatedAt) {
        this.userTotal = userTotal;
        this.adminTotal = adminTotal;
        this.activeUsers24h = activeUsers24h;
        this.queryExecutionsTotal = queryExecutionsTotal;
        this.queryExecutions24h = queryExecutions24h;
        this.failedExecutions24h = failedExecutions24h;
        this.agentRunsTotal = agentRunsTotal;
        this.agentRuns24h = agentRuns24h;
        this.generatedAt = generatedAt;
    }

    public long getUserTotal() {
        return userTotal;
    }

    public void setUserTotal(long userTotal) {
        this.userTotal = userTotal;
    }

    public long getAdminTotal() {
        return adminTotal;
    }

    public void setAdminTotal(long adminTotal) {
        this.adminTotal = adminTotal;
    }

    public long getActiveUsers24h() {
        return activeUsers24h;
    }

    public void setActiveUsers24h(long activeUsers24h) {
        this.activeUsers24h = activeUsers24h;
    }

    public long getQueryExecutionsTotal() {
        return queryExecutionsTotal;
    }

    public void setQueryExecutionsTotal(long queryExecutionsTotal) {
        this.queryExecutionsTotal = queryExecutionsTotal;
    }

    public long getQueryExecutions24h() {
        return queryExecutions24h;
    }

    public void setQueryExecutions24h(long queryExecutions24h) {
        this.queryExecutions24h = queryExecutions24h;
    }

    public long getFailedExecutions24h() {
        return failedExecutions24h;
    }

    public void setFailedExecutions24h(long failedExecutions24h) {
        this.failedExecutions24h = failedExecutions24h;
    }

    public long getAgentRunsTotal() {
        return agentRunsTotal;
    }

    public void setAgentRunsTotal(long agentRunsTotal) {
        this.agentRunsTotal = agentRunsTotal;
    }

    public long getAgentRuns24h() {
        return agentRuns24h;
    }

    public void setAgentRuns24h(long agentRuns24h) {
        this.agentRuns24h = agentRuns24h;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(String generatedAt) {
        this.generatedAt = generatedAt;
    }
}
