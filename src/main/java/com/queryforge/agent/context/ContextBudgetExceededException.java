package com.queryforge.agent.context;

/**
 * 上下文预算无法满足：即使进入 L4 紧急压缩，关键事实（指令/审批/授权）仍超出硬预算。
 * 指南 §7.2：“仍超出预算则返回明确失败/请求缩小范围，不能偷偷丢失硬约束。”
 */
public class ContextBudgetExceededException extends RuntimeException {

    private final int requiredTokens;
    private final int availableTokens;

    public ContextBudgetExceededException(int requiredTokens, int availableTokens) {
        super("上下文关键事实超出预算（需要 " + requiredTokens + " token，可用 " + availableTokens
                + " token），请缩小问题范围或降低已有约束");
        this.requiredTokens = requiredTokens;
        this.availableTokens = availableTokens;
    }

    public int getRequiredTokens() {
        return requiredTokens;
    }

    public int getAvailableTokens() {
        return availableTokens;
    }
}
