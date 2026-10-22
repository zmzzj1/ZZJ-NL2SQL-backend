package com.queryforge.application.agent;

import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.context.ContextBudget;
import com.queryforge.domain.agent.context.ContextLayer;
import com.queryforge.domain.agent.context.ContextPriority;
import com.queryforge.domain.agent.context.ContextSegment;
import com.queryforge.domain.agent.context.LayeredContextBuilder;
import com.queryforge.domain.agent.context.LayeredContextResult;
import com.queryforge.domain.agent.context.TokenEstimator;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.domain.knowledge.KnowledgeSearchResponse;
import com.queryforge.application.knowledge.KnowledgeService;
import com.queryforge.application.memory.MemoryService;
import com.queryforge.application.memory.MemoryService.Scope;
import com.queryforge.application.skill.SkillGuidanceSelector;
import com.queryforge.application.skill.SkillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.ResolvedAgentConfig;

/**
 * Agent 运行上下文构建能力：L1–L4 分层上下文压缩、Knowledge 检索与注入、
 * 长期 Memory 注入、Skill 指引渲染，以及对应的 SSE 证据事件。
 *
 * <p>各来源失败均静默降级（返回空上下文），不阻断运行主流程。
 */
@Service
public class AgentContextAssembler {

    /** 知识上下文默认字符预算（Agent 配置 memoryContextBudget 为 0 时使用）。 */
    public static final int DEFAULT_KNOWLEDGE_CONTEXT_CHARS = 2000;
    /** 长期记忆上下文默认字符预算。 */
    public static final int DEFAULT_MEMORY_CONTEXT_CHARS = 1500;

    private static final Logger log = LoggerFactory.getLogger(AgentContextAssembler.class);

    private final KnowledgeService knowledgeService;
    private final SkillGuidanceSelector skillGuidanceSelector;
    private final MemoryService memoryService;
    private final AgentRunRepository repository;
    private final ObjectMapper objectMapper;

    public AgentContextAssembler(
            KnowledgeService knowledgeService,
            SkillGuidanceSelector skillGuidanceSelector,
            MemoryService memoryService,
            AgentRunRepository repository,
            ObjectMapper objectMapper) {
        this.knowledgeService = knowledgeService;
        this.skillGuidanceSelector = skillGuidanceSelector;
        this.memoryService = memoryService;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 用分层上下文构建器（L1–L4）压缩历史与约束，发出 {@code context.compacted} 事件，
     * 携带脱敏元数据（输入 token、保留来源、丢弃段、层次、hash），不伪造精确供应商值（P1126）。
     */
    public void emitContextCompacted(Long runId, SseEventWriter writer, List<String> recent, String question) {
        try {
            List<ContextSegment> segments = new ArrayList<>();
            segments.add(new ContextSegment(
                    "q", ContextLayer.L1_RECENT, ContextPriority.HIGH, question, true, false, false));
            int index = 0;
            for (String message : recent) {
                segments.add(new ContextSegment(
                        "m" + (++index), ContextLayer.L1_RECENT, ContextPriority.MEDIUM, message, false, false, false));
            }
            ContextBudget budget = new ContextBudget(200, question.length() / 4 + 60, 0, 8000);
            LayeredContextResult result = new LayeredContextBuilder(TokenEstimator.defaultEstimator())
                    .build("SYS", question, segments, budget);
            var meta = result.meta();
            writer.event("context.compacted", new AgentStreamEvents.ContextCompacted(
                    runId, meta.inputTokensEstimate(), meta.retainedSources(), meta.droppedSegments(),
                    budget.historyBudget(),
                    meta.layersUsed().stream().map(Enum::name).collect(Collectors.joining(",")),
                    meta.contextHash()));
        } catch (IOException | RuntimeException ex) {
            log.debug("agent_context_compacted_skipped runId={}", runId);
        }
    }

    /** Workspace 感知的知识命中：传入 workspaceId 时包含空间已授权共享知识。 */
    public List<KnowledgeSearchResponse.Hit> knowledgeHits(PreparedRun run) {
        if (run.workspaceId() != null) {
            return knowledgeService.search(run.userId(), run.connectionId(), run.question(), run.workspaceId()).items();
        }
        return knowledgeService.hits(run.userId(), run.connectionId(), run.question());
    }

    /** Workspace 感知的知识上下文（带来源标识）。 */
    public String knowledgeContext(PreparedRun run, int maxChars) {
        if (run.workspaceId() == null) {
            return knowledgeService.knowledgeContext(run.userId(), run.connectionId(), run.question(), maxChars);
        }
        KnowledgeSearchResponse search = knowledgeService.search(
                run.userId(), run.connectionId(), run.question(), run.workspaceId());
        StringBuilder builder = new StringBuilder();
        for (KnowledgeSearchResponse.Hit hit : search.items()) {
            String line = "[" + hit.sourceType() + "#" + hit.id() + " v" + hit.version() + "] "
                    + hit.title() + "：" + hit.snippet();
            if (builder.length() + line.length() > maxChars) {
                break;
            }
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    /** 发出知识命中证据事件并持久化来源清单（写 JSON 失败不影响主流程）。 */
    public void emitKnowledge(Long runId, SseEventWriter writer, List<KnowledgeSearchResponse.Hit> hits)
            throws IOException {
        if (hits.isEmpty()) {
            return;
        }
        List<AgentStreamEvents.KnowledgeSourceView> sources = hits.stream()
                .map(hit -> new AgentStreamEvents.KnowledgeSourceView(
                        hit.id(), hit.sourceType().name(), hit.title(), hit.version()))
                .toList();
        writer.event("knowledge.retrieved", new AgentStreamEvents.KnowledgeRetrieved(runId, sources));
        try {
            String sourcesJson = objectMapper.writeValueAsString(sources);
            repository.updateRunKnowledgeSources(runId, sourcesJson);
        } catch (IOException | RuntimeException ex) {
            log.debug("agent_knowledge_persist_skipped runId={}", runId);
        }
    }

    /**
     * 按任务问题选择绑定的已发布 Skill 并渲染指引；无相关 Skill 时返回空串。
     * 只记录选择证据（skillId、版本号），不泄露模板全文到普通日志。
     */
    public String skillGuidance(PreparedRun run, SseEventWriter writer) throws IOException {
        List<SkillService.SelectedSkill> selected =
                skillGuidanceSelector.select(run.config(), run.question());
        if (selected.isEmpty()) {
            return "";
        }
        List<AgentStreamEvents.SkillSelectedView> views = selected.stream()
                .map(s -> new AgentStreamEvents.SkillSelectedView(s.skillId(), s.skillVersionId(), s.versionNo()))
                .toList();
        writer.event("skill.selected", new AgentStreamEvents.SkillSelected(run.runId(), views));
        return SkillGuidanceSelector.render(selected);
    }

    /**
     * 长期 Memory 注入（尊重 Agent 配置 memory.enabled）。
     * workspaceId 存在时检索工作空间记忆（需成员资格）；否则检索个人记忆。
     */
    public String memoryContext(PreparedRun run, SseEventWriter writer) throws IOException {
        ResolvedAgentConfig config = run.config();
        if (config == null || !config.memoryEnabled()) {
            return "";
        }
        try {
            String context;
            if (run.workspaceId() != null) {
                context = memoryService.memoryContext(run.userId(), run.workspaceId(), Scope.WORKSPACE,
                        run.question(), DEFAULT_MEMORY_CONTEXT_CHARS);
            } else {
                context = memoryService.memoryContext(run.userId(), null, Scope.PERSONAL,
                        run.question(), DEFAULT_MEMORY_CONTEXT_CHARS);
            }
            if (context != null && !context.isBlank()) {
                long count = context.lines().count();
                writer.event("memory.retrieved", new AgentStreamEvents.MemoryRetrieved(run.runId(), count));
                return "【长期记忆】\n" + context;
            }
            return "";
        } catch (RuntimeException ex) {
            log.warn("agent_memory_context_failed runId={} message={}", run.runId(), ex.getMessage());
            return "";
        }
    }
}
