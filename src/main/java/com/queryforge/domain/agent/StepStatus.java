package com.queryforge.domain.agent;

public enum StepStatus {
    PENDING,
    WAITING_APPROVAL,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }
}
