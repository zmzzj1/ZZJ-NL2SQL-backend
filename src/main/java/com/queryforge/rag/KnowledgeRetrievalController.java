package com.queryforge.rag;

import com.queryforge.identity.AppUserPrincipal;
import com.queryforge.knowledge.KnowledgeEntry;
import com.queryforge.knowledge.KnowledgeSearchResponse;
import com.queryforge.knowledge.KnowledgeService;
import com.queryforge.rag.HybridKnowledgeRetriever.Mode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P8 混合知识检索与索引管理。
 * 检索在服务端判权后执行；索引重建限有权限用户。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeRetrievalController {

    private final HybridKnowledgeRetriever retriever;
    private final KnowledgeService knowledgeService;

    public KnowledgeRetrievalController(HybridKnowledgeRetriever retriever, KnowledgeService knowledgeService) {
        this.retriever = retriever;
        this.knowledgeService = knowledgeService;
    }

    /** 混合检索：mode=KEYWORD/VECTOR/HYBRID，返回带来源与得分的结果。 */
    @PostMapping("/retrieve")
    public KnowledgeSearchResponse retrieve(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody RetrieveRequest request) {
        return retriever.retrieve(
                java.util.List.of(), principal.id(), request.workspaceId(), request.connectionId(),
                request.query(), request.mode());
    }

    /** 索引状态查询。 */
    @GetMapping("/index-status")
    public IndexStatus indexStatus(@AuthenticationPrincipal AppUserPrincipal principal) {
        return new IndexStatus(retriever.indexReady(principal.id()));
    }

    /** 重建某知识条目的向量索引（真实 embedding）。 */
    @PostMapping("/entries/{id}/index")
    public IndexResult index(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        KnowledgeEntry entry = knowledgeService.requireOwnedEntry(id, principal.id());
        int generation = retriever.indexKnowledge(entry.id(), entry.userId(), null,
                entry.connectionId(), entry.sourceType(), entry.title(), entry.content());
        return new IndexResult(id, generation);
    }

    public record RetrieveRequest(
            @NotBlank String query,
            Long workspaceId,
            Long connectionId,
            @NotNull Mode mode
    ) {
    }

    public record IndexStatus(boolean ready) {
    }

    public record IndexResult(Long knowledgeId, int generation) {
    }
}
