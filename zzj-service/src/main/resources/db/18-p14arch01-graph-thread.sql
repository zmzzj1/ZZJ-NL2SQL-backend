-- P14-ARCH-01 增量迁移：六 Agent 主执行图（Spring AI Alibaba StateGraph + RedisSaver）的
-- Run → Graph threadId 归属映射。只新增列、不重写既有数据；幂等（information_schema 判断）。
--
-- 说明：真实 Graph checkpoint 由 RedisSaver 持久化在 Redis（graph:checkpoint:*），
-- 本列只是 MySQL 侧的归属映射（runId ↔ threadId），恢复时按 userId 严格校验；
-- 旧 Run（graph_thread_id IS NULL）继续走旧 SQL 审批兼容链路，不伪造可恢复 Graph。

USE sql_platform;

SET @add_graph_thread_id := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN graph_thread_id VARCHAR(64) NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'graph_thread_id'
);
PREPARE stmt_graph_thread_id FROM @add_graph_thread_id;
EXECUTE stmt_graph_thread_id;
DEALLOCATE PREPARE stmt_graph_thread_id;

SET @add_graph_thread_idx := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD INDEX idx_agent_runs_graph_thread (graph_thread_id)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND INDEX_NAME = 'idx_agent_runs_graph_thread'
);
PREPARE stmt_graph_thread_idx FROM @add_graph_thread_idx;
EXECUTE stmt_graph_thread_idx;
DEALLOCATE PREPARE stmt_graph_thread_idx;
