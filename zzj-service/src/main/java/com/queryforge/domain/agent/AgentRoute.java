package com.queryforge.domain.agent;

/**
 * Manager 的结构化路由结果。
 *
 * <ul>
 *   <li>{@link #CHITCHAT}：寒暄/闲聊，由 Manager 自己做自然语言回答。</li>
 *   <li>{@link #CLARIFY}：关键业务口径缺失，等待用户补充，不生成 SQL。</li>
 *   <li>{@link #KNOWLEDGE}：授权业务知识解释。</li>
 *   <li>{@link #SIMPLE}：一条只读 SQL 即可，走单步审批链。</li>
 *   <li>{@link #COMPLEX}：需要图表/报告/多步分析，走 Planner。</li>
 *   <li>{@link #MEDIUM}：分类器异常/超时/解析失败的安全降级，走 Planner（不静默放行 SQL）。</li>
 *   <li>{@link #TOOL_INVOCATION}：用户显式选择工具的快捷路径，不由模型返回此值触发。</li>
 * </ul>
 */
public enum AgentRoute {
    CHITCHAT,
    CLARIFY,
    KNOWLEDGE,
    SIMPLE,
    COMPLEX,
    MEDIUM,
    TOOL_INVOCATION
}
