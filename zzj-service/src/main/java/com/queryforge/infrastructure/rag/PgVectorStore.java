package com.queryforge.infrastructure.rag;

import com.pgvector.PGvector;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * P12 S3：PostgreSQL pgvector 向量存储适配器。
 * 只支持 v2 规定的三种用途：业务术语（GLOSSARY）、问答示例（QUESTION_KNOWLEDGE）、长期记忆（MEMORY）。
 * 相似度用余弦距离（pgvector {@code <=>}），按服务端 userId + connectionId + vectorType 过滤，
 * 授权过滤由调用方在 MySQL 管理库完成最终复核（本类只做向量投影检索）。
 */
@Repository
public class PgVectorStore {

    public static final String TYPE_GLOSSARY = "GLOSSARY";
    public static final String TYPE_QUESTION_KNOWLEDGE = "QUESTION_KNOWLEDGE";
    public static final String TYPE_MEMORY = "MEMORY";

    private final JdbcTemplate jdbc;

    public PgVectorStore(JdbcTemplate pgVectorJdbcTemplate) {
        this.jdbc = pgVectorJdbcTemplate;
    }

    /** 一条向量命中的投影（不携带正文，正文仍由 MySQL 管理库按授权返回）。 */
    public record Hit(String vectorType, long sourceId, long ownerId, Long connectionId,
                      float cosineDistance, String contentSummary) {
    }

    /** 写入/更新一条向量（同一 source 重复写入由调用方先 deactivate 旧记录）。 */
    public void add(String vectorType, long sourceId, long ownerId, Long connectionId, Long workspaceId,
                    List<Double> embedding, String contentSummary) {
        jdbc.update("""
                        INSERT INTO knowledge_vectors
                            (vector_type, source_id, owner_id, connection_id, workspace_id, embedding, content_summary, active)
                        VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
                        """,
                vectorType, sourceId, ownerId, connectionId, workspaceId,
                new PGvector(toFloatArray(embedding)), contentSummary);
    }

    /** 余弦相似度 Top-K：按服务端 userId + connectionId + vectorType 过滤（connectionId 可空则不过滤）。 */
    public List<Hit> similaritySearch(String vectorType, long userId, Long connectionId,
                                      List<Double> queryVector, int topK) {
        PGvector query = new PGvector(toFloatArray(queryVector));
        return jdbc.query("""
                        SELECT vector_type, source_id, owner_id, connection_id,
                               embedding <=> ? AS cosine_distance, content_summary
                        FROM knowledge_vectors
                        WHERE vector_type = ? AND owner_id = ? AND active = TRUE
                          AND (?::bigint IS NULL OR connection_id = ?)
                        ORDER BY embedding <=> ?
                        LIMIT ?
                        """,
                (rs, i) -> new Hit(
                        rs.getString("vector_type"), rs.getLong("source_id"), rs.getLong("owner_id"),
                        (Long) rs.getObject("connection_id"), rs.getFloat("cosine_distance"),
                        rs.getString("content_summary")),
                query, vectorType, userId, connectionId, connectionId, query, topK);
    }

    /** 软失效：删除/禁用后不再被检索（后续检索过滤 active=FALSE）。 */
    public int deactivate(String vectorType, long sourceId) {
        return jdbc.update(
                "UPDATE knowledge_vectors SET active = FALSE WHERE vector_type = ? AND source_id = ? AND active = TRUE",
                vectorType, sourceId);
    }

    /**
     * 工作空间共享向量 Top-K：按 workspaceId + vectorType 过滤（用于 WORKSPACE 作用域长期记忆）。
     * 命中仍可能跨 owner，最终授权由调用方在 MySQL 管理库按成员资格复核。
     */
    public List<Hit> similaritySearchInWorkspace(String vectorType, long workspaceId,
                                                  List<Double> queryVector, int topK) {
        PGvector query = new PGvector(toFloatArray(queryVector));
        return jdbc.query("""
                        SELECT vector_type, source_id, owner_id, connection_id,
                               embedding <=> ? AS cosine_distance, content_summary
                        FROM knowledge_vectors
                        WHERE vector_type = ? AND workspace_id = ? AND active = TRUE
                        ORDER BY embedding <=> ?
                        LIMIT ?
                        """,
                (rs, i) -> new Hit(
                        rs.getString("vector_type"), rs.getLong("source_id"), rs.getLong("owner_id"),
                        (Long) rs.getObject("connection_id"), rs.getFloat("cosine_distance"),
                        rs.getString("content_summary")),
                query, vectorType, workspaceId, query, topK);
    }

    private static float[] toFloatArray(List<Double> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i).floatValue();
        }
        return out;
    }
}
