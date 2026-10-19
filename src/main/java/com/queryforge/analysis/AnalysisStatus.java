package com.queryforge.analysis;

/**
 * 分析运行状态。终态不可再推进；取消幂等。
 */
public enum AnalysisStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }
}
