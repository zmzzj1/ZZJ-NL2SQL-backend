package com.queryforge.domain.studio;

import java.time.LocalDateTime;

/** Agent 发布版本（不可变快照）。 */
public record AgentVersion(
        Long id,
        Long agentId,
        int versionNo,
        String contentSnapshot,
        String skillVersionRefs,
        String toolIds,
        String modelRef,
        String contentHash,
        Long publishedBy,
        LocalDateTime publishedAt
) {
}
