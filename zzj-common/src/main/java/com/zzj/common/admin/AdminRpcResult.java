package com.zzj.common.admin;

import java.io.Serializable;

/**
 * P13-M3A：管理 RPC 的统一返回壳。
 *
 * <p>业务失败（无权限、参数越界）以 {@code ok=false + errorCode} 表达，而不是抛异常跨进程传播：
 * Dubbo 对未声明异常有自己的包装策略，用显式壳可以保证 Admin 侧拿到确定语义。
 * RPC 通道本身不可用（无 Provider、超时）仍由 Dubbo 抛出 {@code RpcException}，Admin 侧单独映射为 503。
 */
public final class AdminRpcResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean ok;
    private AdminRpcErrorCode errorCode;
    private String message;
    private T data;

    public AdminRpcResult() {
    }

    private AdminRpcResult(boolean ok, AdminRpcErrorCode errorCode, String message, T data) {
        this.ok = ok;
        this.errorCode = errorCode;
        this.message = message;
        this.data = data;
    }

    public static <T> AdminRpcResult<T> success(T data) {
        return new AdminRpcResult<>(true, null, null, data);
    }

    public static <T> AdminRpcResult<T> failure(AdminRpcErrorCode errorCode, String message) {
        return new AdminRpcResult<>(false, errorCode, message, null);
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public AdminRpcErrorCode getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(AdminRpcErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
