-- P7 增量迁移：Agent Studio、Skill Registry、版本快照与审计。
-- 只新增表与可空列，不改写 P1-P6 既有数据。

USE sql_platform;

-- 1) Agent 定义（草稿 + 发布指针）
CREATE TABLE IF NOT EXISTS agent_definitions (
  id                   BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id              BIGINT        NOT NULL,
  name                 VARCHAR(120)  NOT NULL,
  description          VARCHAR(500)  NULL,
  status               VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT / ARCHIVED
  draft_revision       INT           NOT NULL DEFAULT 0,
  draft_content        TEXT          NOT NULL,                  -- JSON：名称/说明/提示词/模型/工具/RAG/Memory/Skills
  published_version_id BIGINT        NULL,
  created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_agent_name (user_id, name),
  KEY idx_agent_user (user_id, updated_at, id),
  CONSTRAINT fk_agent_def_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 2) Agent 发布版本（不可变快照）
CREATE TABLE IF NOT EXISTS agent_versions (
  id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  agent_id       BIGINT        NOT NULL,
  version_no     INT           NOT NULL,
  content_snapshot TEXT        NOT NULL,                        -- 发布时的 draft_content 副本
  skill_version_refs TEXT      NULL,                            -- JSON：绑定的 skillId -> skillVersionId
  tool_ids       TEXT          NOT NULL,                        -- JSON 数组：允许的工具名
  model_ref      VARCHAR(120)  NULL,
  content_hash   CHAR(64)      NOT NULL,
  published_by   BIGINT        NOT NULL,
  published_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_agent_version (agent_id, version_no),
  CONSTRAINT fk_agent_version_def FOREIGN KEY (agent_id) REFERENCES agent_definitions (id) ON DELETE CASCADE,
  CONSTRAINT fk_agent_version_user FOREIGN KEY (published_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- 3) Skill 定义（草稿 + 发布指针）
CREATE TABLE IF NOT EXISTS skill_definitions (
  id                   BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id              BIGINT        NOT NULL,
  name                 VARCHAR(120)  NOT NULL,
  slug                 VARCHAR(160)  NOT NULL,
  description          VARCHAR(500)  NULL,
  status               VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT / PUBLISHED / DISABLED
  draft_revision       INT           NOT NULL DEFAULT 0,
  draft_content        TEXT          NOT NULL,                  -- JSON：触发场景/指引模板/参数 schema/标签
  published_version_id BIGINT        NULL,
  created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_skill_slug (user_id, slug),
  KEY idx_skill_user (user_id, updated_at, id),
  CONSTRAINT fk_skill_def_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 4) Skill 发布版本（不可变快照）
CREATE TABLE IF NOT EXISTS skill_versions (
  id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  skill_id         BIGINT        NOT NULL,
  version_no       INT           NOT NULL,
  content_snapshot TEXT          NOT NULL,
  content_hash     CHAR(64)      NOT NULL,
  published_by     BIGINT        NOT NULL,
  published_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_skill_version (skill_id, version_no),
  CONSTRAINT fk_skill_version_def FOREIGN KEY (skill_id) REFERENCES skill_definitions (id) ON DELETE CASCADE,
  CONSTRAINT fk_skill_version_user FOREIGN KEY (published_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- 5) 配置审计（不存密钥/密码/敏感消息全文）
CREATE TABLE IF NOT EXISTS config_audit (
  id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT        NOT NULL,
  object_type   VARCHAR(24)   NOT NULL,  -- AGENT / SKILL
  object_id     BIGINT        NOT NULL,
  action        VARCHAR(24)   NOT NULL,  -- CREATE / UPDATE / PUBLISH / ROLLBACK / ARCHIVE
  version_no    INT           NULL,
  result        VARCHAR(16)   NOT NULL,  -- SUCCESS / FAILED
  detail        VARCHAR(500)  NULL,
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_config_audit (object_type, object_id, created_at, id),
  CONSTRAINT fk_config_audit_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 6) agent_runs 扩展：运行固定 Agent 配置引用（可空，兼容旧数据）
SET @add_agent_id := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN agent_id BIGINT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'agent_id'
);
PREPARE stmt_agent_id FROM @add_agent_id;
EXECUTE stmt_agent_id;
DEALLOCATE PREPARE stmt_agent_id;

SET @add_agent_version_id := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN agent_version_id BIGINT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'agent_version_id'
);
PREPARE stmt_agent_ver FROM @add_agent_version_id;
EXECUTE stmt_agent_ver;
DEALLOCATE PREPARE stmt_agent_ver;

SET @add_resolved_skills := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN resolved_skill_versions TEXT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'resolved_skill_versions'
);
PREPARE stmt_resolved_skills FROM @add_resolved_skills;
EXECUTE stmt_resolved_skills;
DEALLOCATE PREPARE stmt_resolved_skills;

SET @add_tool_policy_hash := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN tool_policy_hash CHAR(64) NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'tool_policy_hash'
);
PREPARE stmt_tool_hash FROM @add_tool_policy_hash;
EXECUTE stmt_tool_hash;
DEALLOCATE PREPARE stmt_tool_hash;

-- 7) workflow_runs 扩展：固定 Agent 配置引用（可空，兼容旧数据）
SET @add_wf_agent_id := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE workflow_runs ADD COLUMN agent_id BIGINT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'workflow_runs' AND COLUMN_NAME = 'agent_id'
);
PREPARE stmt_wf_agent_id FROM @add_wf_agent_id;
EXECUTE stmt_wf_agent_id;
DEALLOCATE PREPARE stmt_wf_agent_id;

SET @add_wf_agent_version_id := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE workflow_runs ADD COLUMN agent_version_id BIGINT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'workflow_runs' AND COLUMN_NAME = 'agent_version_id'
);
PREPARE stmt_wf_agent_ver FROM @add_wf_agent_version_id;
EXECUTE stmt_wf_agent_ver;
DEALLOCATE PREPARE stmt_wf_agent_ver;
