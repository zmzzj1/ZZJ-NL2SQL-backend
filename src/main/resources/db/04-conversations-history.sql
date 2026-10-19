-- P3 增量迁移：会话、会话消息、查询历史。
-- 只新增表，不修改/删除 P2 的 users、connections 数据。
-- 连接被删除时，会话与历史保留快照名称，连接 ID 置 NULL，不能凭旧 ID 恢复授权。

USE sql_platform;

CREATE TABLE IF NOT EXISTS conversations (
  id                       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id                  BIGINT       NOT NULL,
  connection_id            BIGINT       NULL,
  connection_name_snapshot VARCHAR(100) NOT NULL,
  title                    VARCHAR(120) NOT NULL,
  created_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_activity_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_conversations_user_recent (user_id, last_activity_at, id),
  CONSTRAINT fk_conversations_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_conversations_connection
    FOREIGN KEY (connection_id) REFERENCES connections (id) ON DELETE SET NULL
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS conversation_messages (
  id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT       NOT NULL,
  role            VARCHAR(16)  NOT NULL,
  content         TEXT         NOT NULL,
  status          VARCHAR(16)  NOT NULL,
  request_id      VARCHAR(64)  NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_messages_conversation (conversation_id, id),
  KEY idx_messages_pending (status, created_at),
  CONSTRAINT fk_messages_conversation
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS query_history (
  id                       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id                  BIGINT       NOT NULL,
  conversation_id          BIGINT       NULL,
  connection_id            BIGINT       NULL,
  connection_name_snapshot VARCHAR(100) NOT NULL,
  sql_text                 TEXT         NOT NULL,
  status                   VARCHAR(16)  NOT NULL,
  executed_at              DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  duration_ms              INT          NOT NULL,
  row_count                INT          NULL,
  truncated                TINYINT(1)   NULL,
  error_code               VARCHAR(40)  NULL,
  error_message            VARCHAR(300) NULL,
  KEY idx_history_user_time (user_id, executed_at, id),
  KEY idx_history_user_connection (user_id, connection_id, executed_at),
  CONSTRAINT fk_history_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_history_conversation
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE SET NULL,
  CONSTRAINT fk_history_connection
    FOREIGN KEY (connection_id) REFERENCES connections (id) ON DELETE SET NULL
) ENGINE = InnoDB;
