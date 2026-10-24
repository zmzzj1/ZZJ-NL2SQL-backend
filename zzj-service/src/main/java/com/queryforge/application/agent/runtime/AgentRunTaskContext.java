package com.queryforge.application.agent.runtime;

import com.queryforge.application.agent.EffectiveRunContext;
import com.queryforge.application.agent.PreparedRun;
import com.queryforge.domain.agent.CreateAgentRunRequest.ToolInvocationRequest;
import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.domain.model.ModelRouteContextHolder;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.infrastructure.web.SseEventWriter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P14-ARCH-01：Graph Task Context（按 run 不可变的执行上下文）。
 *
 * <p>经 {@code RunnableConfig} metadata 显式传递给每个节点 Action，避免 ThreadLocal 模型路由
 * 上下文在 Reactor/图执行线程中丢失或串用户。内容为进程内对象（不进入 checkpoint）：
 * 身份、授权配置、SSE 写入器、Schema 摘要、分层上下文，以及同一次流内步骤结果交接表
 * （{@link #stepResults}，仅当前 stream 调用有效，跨请求恢复时按 MySQL 事实重新鉴权取数）。
 *
 * <p>每个 Graph 节点 Action 在执行前后自行设置/清理 {@link ModelRouteContextHolder}，
 * 保证模型路由身份在节点线程上正确可用且不泄漏到其他 Run。
 */
public final class AgentRunTaskContext {

    private final Long runId;
    private final Long userId;
    private final Long workspaceId;
    private final Long conversationId;
    private final Long connectionId;
    private final String database;
    private final String question;
    private final String threadId;
    private final ResolvedAgentConfig config;
    private final boolean autoConfirm;
    private final ToolInvocationRequest toolInvocation;
    private final ModelRouteContextHolder.Context modelRouteContext;
    private final SseEventWriter writer;
    private final String schemaSummary;
    private final EffectiveRunContext effectiveContext;
    private final Map<Long, QueryResultResponse> stepResults = new ConcurrentHashMap<>();

    public AgentRunTaskContext(Long runId, Long userId, Long workspaceId, Long conversationId, Long connectionId,
                               String database, String question, String threadId, ResolvedAgentConfig config,
                               boolean autoConfirm, ToolInvocationRequest toolInvocation,
                               ModelRouteContextHolder.Context modelRouteContext,
                               SseEventWriter writer, String schemaSummary, EffectiveRunContext effectiveContext) {
        this.runId = runId;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.conversationId = conversationId;
        this.connectionId = connectionId;
        this.database = database;
        this.question = question;
        this.threadId = threadId;
        this.config = config;
        this.autoConfirm = autoConfirm;
        this.toolInvocation = toolInvocation;
        this.modelRouteContext = modelRouteContext;
        this.writer = writer;
        this.schemaSummary = schemaSummary;
        this.effectiveContext = effectiveContext == null ? EffectiveRunContext.empty() : effectiveContext;
    }

    /** 从已校验的 PreparedRun 构造（启动路径）。 */
    public static AgentRunTaskContext fromPreparedRun(PreparedRun run, String threadId, SseEventWriter writer,
                                                      String schemaSummary, EffectiveRunContext context) {
        return new AgentRunTaskContext(
                run.runId(), run.userId(), run.workspaceId(), run.conversationId(), run.connectionId(),
                run.database(), run.question(), threadId, run.config(),
                !Boolean.FALSE.equals(run.planAutoConfirm()), run.toolInvocation(),
                new ModelRouteContextHolder.Context(
                        run.userId(), run.workspaceId(), null, run.runId(),
                        run.config() == null ? null : run.config().agentId(), run.conversationId()),
                writer, schemaSummary, context);
    }

    public Long runId() {
        return runId;
    }

    public Long userId() {
        return userId;
    }

    public Long workspaceId() {
        return workspaceId;
    }

    public Long conversationId() {
        return conversationId;
    }

    public Long connectionId() {
        return connectionId;
    }

    public String database() {
        return database;
    }

    public String question() {
        return question;
    }

    public String threadId() {
        return threadId;
    }

    public ResolvedAgentConfig config() {
        return config;
    }

    public boolean autoConfirm() {
        return autoConfirm;
    }

    public ToolInvocationRequest toolInvocation() {
        return toolInvocation;
    }

    public ModelRouteContextHolder.Context modelRouteContext() {
        return modelRouteContext;
    }

    public SseEventWriter writer() {
        return writer;
    }

    public String schemaSummary() {
        return schemaSummary;
    }

    public EffectiveRunContext effectiveContext() {
        return effectiveContext;
    }

    /** 同一次流内的步骤结果交接表（进程内，不落 checkpoint）。 */
    public Map<Long, QueryResultResponse> stepResults() {
        return stepResults;
    }

    public String effectiveContextText() {
        return effectiveContext == null ? "" : effectiveContext.effectiveContext();
    }
}
