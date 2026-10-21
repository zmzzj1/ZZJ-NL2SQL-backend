package com.queryforge.domain.agent.context;

import java.util.List;

/**
 * 压缩元数据（指南 §7.2，脱敏，不存用户私有原文）。
 */
public record ContextCompactionMeta(
        int inputTokensEstimate,
        int retainedSources,
        int droppedSegments,
        String summaryVersion,
        long latencyMs,
        String contextHash,
        List<ContextLayer> layersUsed
) {
}
