-- P8 增量迁移：Workspace、成员、资源授权、工作区审计、知识索引、长期记忆。
-- 只新增表与可空列，不改写 P1–P7 既有数据；全部可重复执行（IF NOT EXISTS / 条件 PREPARE）。

USE sql_platform;

-- 1) 工作空间
CREATE TABLE IF NOT EXISTS workspaces (
  id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  owner_id    BIGINT        NOT NULL,
  name        VARCHAR(120)  NOT NULL,
  description VARCHAR(500)  NULL,
  status      VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / DISABLED
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_workspaces_owner (owner_id, id),
  CONSTRAINT fk_workspaces_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 2) 成员关系（角色：OWNER/ADMIN/EDITOR/VIEWER；授予者与时间、失效）
CREATE TABLE IF NOT EXISTS workspace_members (
  id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id  BIGINT        NOT NULL,
  user_id       BIGINT        NOT NULL,
  role          VARCHAR(16)   NOT NULL DEFAULT 'VIEWER',
  status        VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / REVOKED
  granted_by    BIGINT        NOT NULL,
  granted_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  revoked_at    DATETIME(3)   NULL,
  UNIQUE KEY uk_workspace_member (workspace_id, user_id),
  KEY idx_workspace_members_user (user_id, status, workspace_id),
  CONSTRAINT fk_ws_members_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_ws_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_ws_members_grantor FOREIGN KEY (granted_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- 3) 资源授权：资源类型/ID、资源 owner、授权空间、权限范围、有效性
CREATE TABLE IF NOT EXISTS resource_grants (
  id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  resource_type   VARCHAR(24)   NOT NULL,  -- AGENT / KNOWLEDGE / REPORT / CONNECTION
  resource_id     BIGINT        NOT NULL,
  resource_owner  BIGINT        NOT NULL,
  workspace_id    BIGINT        NOT NULL,
  scope           VARCHAR(16)   NOT NULL DEFAULT 'READ',  -- READ / EDIT
  status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / REVOKED
  granted_by      BIGINT        NOT NULL,
  granted_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  revoked_at      DATETIME(3)   NULL,
  UNIQUE KEY uk_resource_grant (resource_type, resource_id, workspace_id),
  KEY idx_grants_workspace (workspace_id, resource_type, status),
  KEY idx_grants_owner (resource_owner, resource_type, resource_id),
  CONSTRAINT fk_grants_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
  CONSTRAINT fk_grants_owner FOREIGN KEY (resource_owner) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_grants_grantor FOREIGN KEY (granted_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- 4) 工作区审计（脱敏：成员变更、共享/撤销、重要设置、失败访问）
CREATE TABLE IF NOT EXISTS workspace_audit (
  id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id  BIGINT        NULL,
  actor_id      BIGINT        NOT NULL,
  action        VARCHAR(24)   NOT NULL,  -- MEMBER_ADD / MEMBER_REVOKE / GRANT / REVOKE / ACCESS_DENIED / SETTING
  object_type   VARCHAR(24)   NULL,
  object_id     BIGINT        NULL,
  result        VARCHAR(16)   NOT NULL DEFAULT 'SUCCESS',
  detail        VARCHAR(500)  NULL,
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_ws_audit_ws (workspace_id, created_at, id),
  KEY idx_ws_audit_actor (actor_id, created_at, id),
  CONSTRAINT fk_ws_audit_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 5) 知识索引：索引代、Embedding 模型、chunk、来源、owner/workspace、向量
CREATE TABLE IF NOT EXISTS knowledge_index (
  id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  index_generation  INT           NOT NULL,
  knowledge_id      BIGINT        NOT NULL,
  chunk_no          INT           NOT NULL DEFAULT 0,
  owner_id          BIGINT        NOT NULL,
  workspace_id      BIGINT        NULL,
  embedding_model   VARCHAR(80)   NOT NULL,
  embedding_dim     INT           NOT NULL,
  embedding         JSON          NOT NULL,  -- float 数组
  content_summary   VARCHAR(500)  NULL,
  status            VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / SUPERSEDED
  created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_index_chunk (knowledge_id, index_generation, chunk_no),
  KEY idx_index_scope (owner_id, workspace_id, status, knowledge_id),
  CONSTRAINT fk_index_knowledge FOREIGN KEY (knowledge_id) REFERENCES knowledge_entries (id) ON DELETE CASCADE,
  CONSTRAINT fk_index_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 6) 长期记忆
CREATE TABLE IF NOT EXISTS long_term_memories (
  id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  owner_id        BIGINT        NOT NULL,
  workspace_id    BIGINT        NULL,
  memory_type     VARCHAR(16)   NOT NULL,  -- PROFILE / TASK / FACT / EPISODIC
  scope           VARCHAR(16)   NOT NULL DEFAULT 'PERSONAL',  -- PERSONAL / WORKSPACE
  title           VARCHAR(200)  NOT NULL,
  content         TEXT          NOT NULL,
  source_ref      VARCHAR(200)  NULL,      -- conversationId/runId 等来源引用
  confidence      VARCHAR(16)   NOT NULL DEFAULT 'UNVERIFIED', -- UNVERIFIED / VERIFIED / REJECTED
  status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / DISABLED
  expires_at      DATETIME(3)   NULL,
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_memories_owner (owner_id, scope, status, id),
  KEY idx_memories_ws (workspace_id, scope, status, id),
  KEY idx_memories_type (owner_id, memory_type, status),
  CONSTRAINT fk_memories_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 7) 记忆去重指纹（同 owner+scope+type 的内容指纹，用于合并）
CREATE TABLE IF NOT EXISTS memory_fingerprints (
  id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  owner_id       BIGINT       NOT NULL,
  workspace_id   BIGINT       NULL,
  memory_type    VARCHAR(16)  NOT NULL,
  scope          VARCHAR(16)  NOT NULL,
  fingerprint    CHAR(64)     NOT NULL,
  memory_id      BIGINT       NOT NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_memory_fp (owner_id, workspace_id, memory_type, scope, fingerprint),
  KEY idx_memory_fp_owner (owner_id, scope, memory_type),
  CONSTRAINT fk_mem_fp_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 8) agent_runs 扩展：workspace 上下文（可空，兼容旧数据）
SET @add_run_ws := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN workspace_id BIGINT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'workspace_id'
);
PREPARE stmt_run_ws FROM @add_run_ws;
EXECUTE stmt_run_ws;
DEALLOCATE PREPARE stmt_run_ws;

-- 9) knowledge_entries 扩展：可选的 workspace 归属（个人知识保持 NULL，不自动暴露）
SET @add_ke_ws := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE knowledge_entries ADD COLUMN workspace_id BIGINT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'knowledge_entries' AND COLUMN_NAME = 'workspace_id'
);
PREPARE stmt_ke_ws FROM @add_ke_ws;
EXECUTE stmt_ke_ws;
DEALLOCATE PREPARE stmt_ke_ws;
