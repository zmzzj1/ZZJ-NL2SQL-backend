package com.queryforge.agent.context;

/**
 * 上下文片段优先级。CRITICAL 在 L4 紧急压缩时仍被保留（审批/授权/当前指令/已确认约束）。
 */
public enum ContextPriority {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
