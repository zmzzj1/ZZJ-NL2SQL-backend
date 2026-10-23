package com.queryforge.domain.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** P4：在一个已授权会话与数据源上启动 Agent 运行。 */
public record CreateAgentRunRequest(
        @NotNull(message = "conversationId 不能为空")
        Long conversationId,

        @NotNull(message = "connectionId 不能为空")
        Long connectionId,

        @NotBlank(message = "userInput 不能为空")
        @Size(max = 2000, message = "userInput 最长 2000 字符")
        String userInput,

        /** 可选：指定 Agent 配置（发布版优先）。缺省沿用内置默认行为。 */
        Long agentId,

        /** 可选：指定发布版本（用于追溯与回滚后固定版本）。缺省使用 Agent 当前发布版。 */
        Long agentVersionId,

        /** 可选：true 表示读取草稿快照（Studio 测试预览）；缺省 false 使用发布版。 */
        Boolean draft,

        /** 可选：当前工作空间，命中共享知识与工作空间记忆时生效（需成员资格）。 */
        Long workspaceId,

        /**
         * P14-A：用户在输入框显式选择的工具调用（可选）。
         * 仅当用户主动选中工具并填写参数时由前端提交；普通文本中出现工具名不会触发。
         * 存在时跳过 Manager 分类与 Planner，走单次受控工具执行链。
         */
        @Valid
        ToolInvocationRequest toolInvocation,

        /**
         * P14-D：计划级风险审核模式。true（默认）= 跳过计划级 LLM 风险评估与人工审核，
         * 不跳过具体 SQL 的逐版本确认；false = 复杂计划生成后由 Gate 判断是否需人工审核。
         * 仅针对本次 Agent Run 固定为创建时的快照，继续执行时不读取前端新值。
         */
        Boolean autoConfirm
) {

    /**
     * 一次显式工具调用的结构化请求。userId、角色、Run 归属与审计都由服务端创建，
     * 不接受前端伪造的内部身份。
     */
    public record ToolInvocationRequest(
            @NotBlank(message = "toolName 不能为空")
            @Size(max = 100, message = "toolName 最长 100 字符")
            String toolName,

            /** 工具参数，必须是 JSON 对象（Map）；数组或标量由服务端拒绝。 */
            Map<String, Object> args
    ) {
    }
}
