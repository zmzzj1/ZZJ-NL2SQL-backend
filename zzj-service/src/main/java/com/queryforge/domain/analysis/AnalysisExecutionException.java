package com.queryforge.domain.analysis;

/**
 * 分析执行失败（非零退出、超时、超限、沙箱不可用等）。携带真实失败原因，不生成假成功。
 */
public class AnalysisExecutionException extends RuntimeException {

    public AnalysisExecutionException(String message) {
        super(message);
    }

    public AnalysisExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
