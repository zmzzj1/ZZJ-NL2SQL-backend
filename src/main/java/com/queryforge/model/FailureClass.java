package com.queryforge.model;

/** 模型调用错误分类（O3）。决定是否可重试/切换。 */
public enum FailureClass {
    /** 429 限流，可遵守 Retry-After 有界重试/切换。 */
    RATE_LIMITED,
    /** 网络超时、临时 5xx，模型未输出时可有限重试/切换。 */
    UPSTREAM_5XX,
    /** 401/403，配置错误，不重试同一配置。 */
    AUTH,
    /** 400、输入超限、能力不兼容，不可恢复。 */
    INPUT,
    /** 连接超时。 */
    TIMEOUT,
    /** 未知。 */
    UNKNOWN;

    public boolean retryable() {
        return this == RATE_LIMITED || this == UPSTREAM_5XX || this == TIMEOUT;
    }

    public static FailureClass fromHttpStatus(int status) {
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status == 401 || status == 403) {
            return AUTH;
        }
        if (status >= 400 && status < 500) {
            return INPUT;
        }
        if (status >= 500) {
            return UPSTREAM_5XX;
        }
        return UNKNOWN;
    }
}
