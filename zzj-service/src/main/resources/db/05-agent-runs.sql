-- P4 增量迁移：Agent 运行、步骤，以及查询历史与 Run/Step 的关联。
-- 只新增表与可空列，不改写 P2/P3 既有数据。

USE sql_platform;

CREATE TABLE IF NOT EXISTS agent_runs (
  id                       BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id                  BIGINT        NOT NULL,
  conversation_id          BIGINT        NOT NULL,
  connection_id            BIGINT        NULL,
  connection_name_snapshot VARCHAR(100)  NOT NULL,
  question                 VARCHAR(2000) NOT NULL,
  status                   VARCHAR(24)   NOT NULL,
  summary                  TEXT          NULL,
  error_code               VARCHAR(40)   NULL,
  error_message            VARCHAR(300)  NULL,
  created_at               DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at               DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_agent_runs_user (user_id, created_at, id),
  KEY idx_agent_runs_conversation (conversation_id, created_at, id),
  CONSTRAINT fk_agent_runs_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_agent_runs_conversation
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
  CONSTRAINT fk_agent_runs_connection
    FOREIGN KEY (connection_id) REFERENCES connections (id) ON DELETE SET NULL
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS agent_steps (
  id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  run_id          BIGINT       NOT NULL,
  sequence        INT          NOT NULL,
  objective       VARCHAR(300) NOT NULL,
  depends_on      INT          NULL,
  status          VARCHAR(24)  NOT NULL,
  sql_text        TEXT         NULL,
  sql_version     INT          NOT NULL DEFAULT 0,
  approval_status VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  repair_count    INT          NOT NULL DEFAULT 0,
  row_count       INT          NULL,
  truncated       TINYINT      NULL,
  error_code      VARCHAR(40)  NULL,
  error_message   VARCHAR(300) NULL,
  result_summary  VARCHAR(500) NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_agent_step_seq (run_id, sequence),
  CONSTRAINT fk_agent_steps_run
    FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 查询历史关联 Run/Step；MySQL 不支持 ADD COLUMN IF NOT EXISTS，用元数据判断保证可重复执行。
SET @add_run_id := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE query_history ADD COLUMN run_id BIGINT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'query_history' AND COLUMN_NAME = 'run_id'
);
PREPARE stmt_run_id FROM @add_run_id;
EXECUTE stmt_run_id;
DEALLOCATE PREPARE stmt_run_id;

SET @add_step_id := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE query_history ADD COLUMN step_id BIGINT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'query_history' AND COLUMN_NAME = 'step_id'
);
PREPARE stmt_step_id FROM @add_step_id;
EXECUTE stmt_step_id;
DEALLOCATE PREPARE stmt_step_id;

SET @add_run_index := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE query_history ADD INDEX idx_history_run (run_id, step_id)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'query_history' AND INDEX_NAME = 'idx_history_run'
);
PREPARE stmt_run_index FROM @add_run_index;
EXECUTE stmt_run_index;
DEALLOCATE PREPARE stmt_run_index;
