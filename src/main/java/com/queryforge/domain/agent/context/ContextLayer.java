package com.queryforge.domain.agent.context;

/**
 * 上下文分层（指南 §7.2）。只有前一层预算不足时才进入后一层。
 */
public enum ContextLayer {
    L1_RECENT,
    L2_SUMMARY,
    L3_RETRIEVAL,
    L4_EMERGENCY
}
