package com.queryforge.application.agent;

/**
 * 工具执行过程的轻量观察者。当前仅用于暴露参数自动修复的进度元数据
 * （供 {@code tool.repair} SSE 事件使用），不携带修正后的参数原文，避免敏感数据外泄。
 */
@FunctionalInterface
public interface ToolExecutionListener {

    /** 发生一次参数修复尝试时回调；attempt 从 1 开始，diagnostic 为触发的校验错误摘要。 */
    void onRepair(int attempt, String diagnostic);
}
