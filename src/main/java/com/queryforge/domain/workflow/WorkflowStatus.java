package com.queryforge.domain.workflow;

/**
 * 工作流运行状态与节点状态。终态不可推进；取消幂等。
 */
public final class WorkflowStatus {

    private WorkflowStatus() {
    }

    public enum Run {
        PENDING, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELED;
        }
    }

    public enum Node {
        PENDING, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, CANCELED;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELED;
        }
    }
}
