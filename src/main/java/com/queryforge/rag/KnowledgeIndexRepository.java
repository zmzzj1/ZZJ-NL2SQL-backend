package com.queryforge.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 知识向量索引的持久化：按 index_generation 版本化，只有 ACTIVE 代进入检索。
 * 向量以 JSON float 数组存储，owner/workspace 权限过滤在候选查询阶段生效。
 */
@Repository
public class KnowledgeIndexRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public KnowledgeIndexRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 记录：知识条目 + 索引代 + 向量 + 来源。 */
    public record IndexedChunk(
            Long id, Long knowledgeId, int chunkNo, Long ownerId, Long workspaceId,
            String embeddingModel, int embeddingDim, List<Double> embedding, String contentSummary
    ) {
    }

    public void insertChunk(Long knowledgeId, int indexGeneration, int chunkNo, Long ownerId, Long workspaceId,
                            String embeddingModel, int embeddingDim, List<Double> embedding, String contentSummary) {
        String vectorJson = toJson(embedding);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO knowledge_index
                        (index_generation, knowledge_id, chunk_no, owner_id, workspace_id,
                         embedding_model, embedding_dim, embedding, content_summary, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, indexGeneration);
            statement.setLong(2, knowledgeId);
            statement.setInt(3, chunkNo);
            statement.setLong(4, ownerId);
            if (workspaceId == null) {
                statement.setNull(5, java.sql.Types.BIGINT);
            } else {
                statement.setLong(5, workspaceId);
            }
            statement.setString(6, embeddingModel);
            statement.setInt(7, embeddingDim);
            statement.setString(8, vectorJson);
            statement.setString(9, contentSummary);
            return statement;
        }, keyHolder);
    }

    /** 将某知识条目的旧索引代置为 SUPERSEDED（发布新代前）。 */
    public int supersede(Long knowledgeId) {
        return jdbcTemplate.update("""
                        UPDATE knowledge_index SET status = 'SUPERSEDED'
                        WHERE knowledge_id = ? AND status = 'ACTIVE'
                        """, knowledgeId);
    }

    public int currentGeneration(Long knowledgeId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT COALESCE(MAX(index_generation), 0) FROM knowledge_index WHERE knowledge_id = ?",
                (rs, rowNum) -> rs.getInt(1), knowledgeId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /**
     * 向量候选：本人 + 空间内已授权共享的知识，仅 ACTIVE 索引代。
     * 权限在 SQL 层过滤（EXISTS resource_grants），不先取全库再隐藏。
     */
    public List<IndexedChunk> activeChunks(Long userId, Long workspaceId) {
        String precise = """
                SELECT i.id, i.knowledge_id, i.chunk_no, i.owner_id, i.workspace_id,
                       i.embedding_model, i.embedding_dim, i.embedding, i.content_summary
                FROM knowledge_index i
                JOIN knowledge_entries k ON k.id = i.knowledge_id AND k.status = 'ACTIVE'
                WHERE i.status = 'ACTIVE'
                  AND (i.owner_id = ?
                    OR (? IS NOT NULL AND EXISTS (
                          SELECT 1 FROM resource_grants g
                          WHERE g.resource_type = 'KNOWLEDGE' AND g.resource_id = i.knowledge_id
                            AND g.workspace_id = ? AND g.status = 'ACTIVE')))
                """;
        return jdbcTemplate.query(precise,
                (rs, rowNum) -> mapChunk(rs), userId, workspaceId, workspaceId);
    }

    /** 判断是否已有任何已激活索引（供索引状态查询）。 */
    public int activeCount(Long userId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT COUNT(*) FROM knowledge_index WHERE owner_id = ? AND status = 'ACTIVE'",
                (rs, rowNum) -> rs.getInt(1), userId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private IndexedChunk mapChunk(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long workspaceId = rs.getLong("workspace_id");
        Long nullableWs = rs.wasNull() ? null : workspaceId;
        return new IndexedChunk(
                rs.getLong("id"), rs.getLong("knowledge_id"), rs.getInt("chunk_no"),
                rs.getLong("owner_id"), nullableWs,
                rs.getString("embedding_model"), rs.getInt("embedding_dim"),
                parseVector(rs.getString("embedding")), rs.getString("content_summary"));
    }

    private List<Double> parseVector(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Double>>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String toJson(List<Double> vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("向量序列化失败", ex);
        }
    }
}
