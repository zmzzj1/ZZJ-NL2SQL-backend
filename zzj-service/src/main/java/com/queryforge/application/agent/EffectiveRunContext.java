package com.queryforge.application.agent;

import com.queryforge.domain.agent.context.LayeredContextResult;

/**
 * P14-A：一次构建、多处消费的运行级有效上下文。
 *
 * <p>{@link #layered()} 是真实注入模型的 L1–L4 分层压缩结果（Manager/闲聊/Planner/SQL
 * 消费的是同一条受控上下文）；其余字段是同一批授权来源的派生摘要，避免二次检索。
 */
public record EffectiveRunContext(
        LayeredContextResult layered,
        String historyForClassifier,
        String knowledge,
        String memoryContext,
        String skillGuidance,
        String schemaSummary,
        String evidenceSummary,
        int historyBudget
) {

    /**
     * 注入模型的受控上下文体：服务端授权边界（system 侧）+ 压缩后的历史/知识/摘要（user 侧）。
     * 二者必须一起下发，否则 L4 授权边界不会真正进入模型请求。
     */
    public String effectiveContext() {
        if (layered == null) {
            return "";
        }
        String system = layered.systemContext() == null ? "" : layered.systemContext();
        String user = layered.userContext() == null ? "" : layered.userContext();
        if (system.isBlank()) {
            return user;
        }
        if (user.isBlank()) {
            return system;
        }
        return system + "\n" + user;
    }

    /** 是否真实应用了分层压缩（没有则不得凭模拟事件宣称效果）。 */
    public boolean compacted() {
        return layered != null;
    }

    public static EffectiveRunContext empty() {
        return new EffectiveRunContext(null, "", "", "", "", "", "", 0);
    }
}
