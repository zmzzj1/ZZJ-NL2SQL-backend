package com.queryforge.application.agent.sql;

/**
 * P14-B：一次 SQL 候选选优请求。身份/Workspace/连接/Agent 配置只能来自可信的
 * Run/Session 与 P14-A 已授权上下文，不允许取浏览器自定义角色或用户头。
 *
 * @param runId              当前运行
 * @param stepId             当前步骤
 * @param route              本次 run 的最终路由（DB 事实，非单例共享开关）
 * @param userQuestion       用户原问题
 * @param stepObjective      当前步骤目标
 * @param schema             授权 Schema 上下文文本
 * @param database           当前连接数据库名（Guard 的 allowedSchema）
 * @param previousSummaries  前序步骤真实结果摘要
 * @param knowledge          业务知识 + 长期记忆（受控上下文）
 * @param authorizedContext  P14-A 分层压缩后的有效上下文（L1–L4）
 * @param skillGuidance      选中的 Skill 指引（不授予权限）
 * @param systemPrompt       Agent 自定义系统提示（只作补充，不覆盖安全约束）
 * @param dialect            目标 SQL 方言
 */
public record SqlSelectionRequest(
        Long runId,
        Long stepId,
        String route,
        String userQuestion,
        String stepObjective,
        String schema,
        String database,
        String previousSummaries,
        String knowledge,
        String authorizedContext,
        String skillGuidance,
        String systemPrompt,
        String dialect
) {
}
