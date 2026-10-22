-- P5 增量迁移：业务知识条目、会话记忆摘要、Agent 路由与角色轨迹。
-- 只新增表与可空列，不改写 P2–P4 数据。

USE sql_platform;

CREATE TABLE IF NOT EXISTS knowledge_entries (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT       NOT NULL,
  connection_id BIGINT       NULL,
  source_type   VARCHAR(24)  NOT NULL,
  title         VARCHAR(200) NOT NULL,
  content       TEXT         NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  version       INT          NOT NULL DEFAULT 1,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_knowledge_user_type (user_id, source_type, status),
  KEY idx_knowledge_connection (user_id, connection_id, status),
  CONSTRAINT fk_knowledge_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_knowledge_connection
    FOREIGN KEY (connection_id) REFERENCES connections (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 会话记忆摘要（幂等 ALTER）
SET @add_memory := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE conversations ADD COLUMN memory_summary VARCHAR(1000) NULL, ADD COLUMN memory_updated_at DATETIME(3) NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'conversations' AND COLUMN_NAME = 'memory_summary'
);
PREPARE stmt_memory FROM @add_memory;
EXECUTE stmt_memory;
DEALLOCATE PREPARE stmt_memory;

-- Agent 路由与角色轨迹（幂等 ALTER）
SET @add_route := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN route VARCHAR(24) NULL, ADD COLUMN role_activities TEXT NULL, ADD COLUMN knowledge_sources TEXT NULL, ADD COLUMN llm_call_count INT NOT NULL DEFAULT 0',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'route'
);
PREPARE stmt_route FROM @add_route;
EXECUTE stmt_route;
DEALLOCATE PREPARE stmt_route;

SET @add_role := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_steps ADD COLUMN agent_role VARCHAR(24) NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_steps' AND COLUMN_NAME = 'agent_role'
);
PREPARE stmt_role FROM @add_role;
EXECUTE stmt_role;
DEALLOCATE PREPARE stmt_role;
