package com.queryforge.platform.entity;

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
