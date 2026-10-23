package com.queryforge.domain.agent;

public enum RunStatus {
    PLANNING,
    WAITING_APPROVAL,
    WAITING_PLAN_REVIEW,
    RUNNING,
    CLARIFYING,
    COMPLETED,
    FAILED,
    CANCELED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }
}
