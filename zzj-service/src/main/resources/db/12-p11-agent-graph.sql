-- P11 增量迁移：统一状态图检查点（指南 §4.1）。
-- 以 run_id 为主键的快照表，等价于在 agent_runs 上附加 graph_state_json，但避免改动热更新路径的字段映射。
-- 只新增表，可重复执行。

USE sql_platform;

CREATE TABLE IF NOT EXISTS agent_graph_checkpoints (
  run_id            BIGINT        NOT NULL PRIMARY KEY,
  graph_state_json  MEDIUMTEXT    NOT NULL,
  state_revision    BIGINT        NOT NULL DEFAULT 1,
  created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_graph_checkpoints_run
    FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
) ENGINE = InnoDB;
