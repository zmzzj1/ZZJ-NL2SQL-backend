package com.queryforge.domain.agent.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * L1–L4 分层上下文压缩构建器（指南 §7.2）。
 *
 * <p>策略：只有前一层预算不足时才进入后一层。
 * <ol>
 *   <li>L1 最近窗口：保留最近完整消息、当前任务意图与已确认限制。</li>
 *   <li>L2 结构化摘要：较老消息压缩为事实/决定/待确认/数据来源，明确标注用户确认 vs 模型推测。</li>
 *   <li>L3 按需检索：由调用方在已授权 Workspace/资源范围内检索后传入（知识/Memory），本类只负责预算融合。</li>
 *   <li>L4 紧急压缩：极端压力下只保留 CRITICAL（当前指令、待审批状态、授权边界、用户已确认约束），
 *       其余丢弃；若关键事实仍超预算则抛出 {@link ContextBudgetExceededException}。</li>
 * </ol>
 *
 * <p>不变量：待审批状态（{@code isApproval}）与授权边界（{@code isAuthorization}）在任一压缩层都不会被改写为“已批准”。
 * 本类只原样搬运片段文本，标签由调用方在构造片段时保证；L2 渲染会显式标注“（推测）”以区分模型猜测。
 */
public final class LayeredContextBuilder {

    private final TokenEstimator estimator;

    public LayeredContextBuilder(TokenEstimator estimator) {
        this.estimator = estimator == null ? TokenEstimator.defaultEstimator() : estimator;
    }

    public LayeredContextResult build(
            String systemPrompt,
            String question,
            List<ContextSegment> segments,
            ContextBudget budget) {
        long start = System.nanoTime();
        List<ContextSegment> all = segments == null ? List.of() : new ArrayList<>(segments);
        int inputTokensEstimate = estimator.estimate(all);
        int historyBudget = budget.historyBudget();

        // 分区并按优先级排序（CRITICAL/审批/授权优先，LOW 最后）。
        List<ContextSegment> l1 = filterByLayer(all, ContextLayer.L1_RECENT);
        List<ContextSegment> l2 = filterByLayer(all, ContextLayer.L2_SUMMARY);
        List<ContextSegment> l3 = filterByLayer(all, ContextLayer.L3_RETRIEVAL);
        List<ContextSegment> l4 = filterByLayer(all, ContextLayer.L4_EMERGENCY);

        // 渐进装入：L4(紧急关键字段强制) -> L1 -> L2 -> L3
        List<ContextSegment> retained = new ArrayList<>(l4); // L4 已是最压缩关键字段，强制保留
        int used = estimator.estimate(retained);
        used = appendInPriorityOrder(l1, retained, used, historyBudget);
        used = appendInPriorityOrder(l2, retained, used, historyBudget);
        used = appendInPriorityOrder(l3, retained, used, historyBudget);

        int dropped = all.size() - retained.size();

        // L4 紧急：若超出（理论上 append 已保证不超），或显式压力触发——这里处理“关键事实超预算”失败分支。
        int criticalTokens = estimator.estimate(criticalOnly(retained));
        if (criticalTokens > historyBudget) {
            throw new ContextBudgetExceededException(criticalTokens, historyBudget);
        }

        String systemContext = renderSystem(systemPrompt, retained);
        String userContext = renderUser(question, retained);

        Set<ContextLayer> layersUsed = EnumSet.noneOf(ContextLayer.class);
        for (ContextSegment segment : retained) {
            layersUsed.add(segment.layer());
        }
        ContextCompactionMeta meta = new ContextCompactionMeta(
                inputTokensEstimate,
                distinctSources(retained),
                dropped,
                summaryVersion(l2),
                (System.nanoTime() - start) / 1_000_000,
                contextHash(systemContext + userContext),
                new ArrayList<>(layersUsed));
        return new LayeredContextResult(systemContext, userContext, meta);
    }

    private int appendInPriorityOrder(
            List<ContextSegment> layer,
            List<ContextSegment> retained,
            int usedSoFar,
            int budget) {
        List<ContextSegment> ordered = new ArrayList<>(layer);
        // 枚举顺序 CRITICAL(0),HIGH(1),MEDIUM(2),LOW(3)：升序即“最重要优先”，保证关键事实先装入。
        ordered.sort(Comparator.comparingInt((ContextSegment s) -> s.priority().ordinal()));
        int used = usedSoFar;
        for (ContextSegment segment : ordered) {
            int t = segment.tokens(estimator);
            if (used + t <= budget || segment.isCriticalFact()) {
                retained.add(segment);
                used += t;
            }
        }
        return used;
    }

    private List<ContextSegment> criticalOnly(List<ContextSegment> retained) {
        List<ContextSegment> critical = new ArrayList<>();
        for (ContextSegment segment : retained) {
            if (segment.isCriticalFact()) {
                critical.add(segment);
            }
        }
        return critical;
    }

    private static List<ContextSegment> filterByLayer(List<ContextSegment> all, ContextLayer layer) {
        List<ContextSegment> out = new ArrayList<>();
        for (ContextSegment segment : all) {
            if (segment.layer() == layer) {
                out.add(segment);
            }
        }
        return out;
    }

    private static String renderSystem(String systemPrompt, List<ContextSegment> retained) {
        StringBuilder sb = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append(systemPrompt.trim());
        }
        // 授权边界事实前置到系统上下文，确保模型始终可见。
        for (ContextSegment segment : retained) {
            if (segment.isAuthorization()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append("[授权边界] ").append(segment.text().trim());
            }
        }
        return sb.toString();
    }

    private static String renderUser(String question, List<ContextSegment> retained) {
        StringBuilder sb = new StringBuilder();
        if (question != null && !question.isBlank()) {
            sb.append(question.trim());
        }
        for (ContextSegment segment : retained) {
            if (segment.isAuthorization()) {
                continue; // 已在 system 中呈现
            }
            String tag = switch (segment.layer()) {
                case L1_RECENT -> "[最近]";
                case L2_SUMMARY -> segment.isConfirmed() ? "[摘要·已确认]" : "[摘要·推测]";
                case L3_RETRIEVAL -> "[检索]";
                case L4_EMERGENCY -> "[紧急]";
            };
            sb.append("\n").append(tag).append(" ").append(segment.text().trim());
        }
        return sb.toString();
    }

    private static int distinctSources(List<ContextSegment> retained) {
        Set<String> sources = new LinkedHashSet<>();
        for (ContextSegment segment : retained) {
            sources.add(segment.sourceId());
        }
        return sources.size();
    }

    private static String summaryVersion(List<ContextSegment> l2) {
        StringBuilder sb = new StringBuilder();
        for (ContextSegment segment : l2) {
            sb.append(segment.sourceId()).append(':').append(segment.text().hashCode()).append('|');
        }
        return contextHash(sb.toString());
    }

    private static String contextHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(content.hashCode());
        }
    }
}
