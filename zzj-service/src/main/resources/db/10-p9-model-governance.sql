-- P9-FB09：多模型高可用、可观测性与平台治理
-- 增量迁移，不清理 P1-P8 数据。

USE sql_platform;

-- 平台管理员角色：服务端可信机制，与 Workspace OWNER 正交。
-- 仅用条件式添加列，避免重复执行报错。
SET @role_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'role'
);
SET @ddl := IF(@role_col = 0,
  'ALTER TABLE users ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT ''USER''',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 模型供应商（平台管理员维护）。Key 密文仅存引用，明文不回传。
CREATE TABLE IF NOT EXISTS model_providers (
  id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name           VARCHAR(100) NOT NULL,
  protocol       VARCHAR(16)  NOT NULL DEFAULT 'OPENAI_COMPAT',
  base_url       VARCHAR(255) NOT NULL,
  api_key_cipher TEXT         NOT NULL,
  enabled        TINYINT(1)   NOT NULL DEFAULT 1,
  created_by     BIGINT       NOT NULL,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_provider_name (name)
) ENGINE = InnoDB;

-- 模型能力档案。
CREATE TABLE IF NOT EXISTS model_profiles (
  id                    BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  provider_id           BIGINT       NOT NULL,
  model_id              VARCHAR(100) NOT NULL,
  model_type            VARCHAR(16)  NOT NULL DEFAULT 'CHAT', -- CHAT / EMBEDDING
  supports_streaming    TINYINT(1)   NOT NULL DEFAULT 1,
  supports_tool_calls   TINYINT(1)   NOT NULL DEFAULT 0,
  supports_json         TINYINT(1)   NOT NULL DEFAULT 0,
  context_limit         INT          NOT NULL DEFAULT 8192,
  output_limit          INT          NOT NULL DEFAULT 2048,
  embedding_dim         INT          NULL,
  status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / DISABLED
  capabilities_verified TINYINT(1)   NOT NULL DEFAULT 0,
  created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_profile (provider_id, model_id),
  CONSTRAINT fk_profile_provider FOREIGN KEY (provider_id) REFERENCES model_providers (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 模型使用授权：用户 / Workspace / Agent 维度。
CREATE TABLE IF NOT EXISTS model_grants (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  profile_id    BIGINT       NOT NULL,
  grant_type    VARCHAR(16)  NOT NULL, -- USER / WORKSPACE / AGENT
  grantee_id    BIGINT       NOT NULL,
  granted_by    BIGINT       NOT NULL,
  granted_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at    DATETIME     NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  UNIQUE KEY uk_grant (profile_id, grant_type, grantee_id),
  CONSTRAINT fk_grant_profile FOREIGN KEY (profile_id) REFERENCES model_profiles (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 路由策略（不可变版本，乐观锁 + 原子发布 + 回滚）。
CREATE TABLE IF NOT EXISTS route_policy_versions (
  id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  policy_id      BIGINT       NOT NULL,
  version_no     INT          NOT NULL,
  strategy       VARCHAR(24)  NOT NULL, -- ROUND_ROBIN / LATENCY_FIRST / SUCCESS_RATE_FIRST / SMART_WEIGHTED
  candidates     JSON         NOT NULL, -- [profileId,...]
  fallback_chain JSON         NOT NULL, -- [profileId,...]
  weights        JSON         NULL,     -- SMART_WEIGHTED 权重
  affinity_ttl_s INT          NOT NULL DEFAULT 0,
  status         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT', -- DRAFT / PUBLISHED
  published_at   DATETIME     NULL,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_policy (policy_id),
  UNIQUE KEY uk_policy_version (policy_id, version_no)
) ENGINE = InnoDB;

-- 每次模型调用尝试的真实记录（Provider/Model/attempt/耗时/Token/错误分类/路由原因）。
CREATE TABLE IF NOT EXISTS invocation_attempts (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  call_id       VARCHAR(64)  NOT NULL,
  attempt_no    INT          NOT NULL,
  run_id        BIGINT       NULL,
  agent_id      BIGINT       NULL,
  profile_id    BIGINT       NULL,
  provider_name VARCHAR(100) NULL,
  model_id      VARCHAR(100) NULL,
  model_type    VARCHAR(16)  NOT NULL DEFAULT 'CHAT',
  route_reason  VARCHAR(24)  NULL,
  status        VARCHAR(16)  NOT NULL, -- SUCCESS / FAILED / INTERRUPTED
  error_class   VARCHAR(24)  NULL, -- TIMEOUT / RATE_LIMITED / AUTH / INPUT / UPSTREAM_5XX
  http_status   INT          NULL,
  latency_ms    INT          NULL,
  ttft_ms       INT          NULL,
  prompt_tokens INT          NULL,
  completion_tokens INT      NULL,
  total_tokens  INT          NULL,
  cost_estimate DECIMAL(18,6) NULL,
  cost_currency VARCHAR(8)   NULL,
  cost_status   VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN', -- UNKNOWN / ESTIMATED / FINAL
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_call (call_id),
  KEY idx_run (run_id),
  KEY idx_created (created_at)
) ENGINE = InnoDB;

-- 平台配额：用户/Workspace/Agent/模型维度。
CREATE TABLE IF NOT EXISTS platform_quotas (
  id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  scope_type      VARCHAR(16)  NOT NULL, -- USER / WORKSPACE / AGENT / MODEL
  scope_id        BIGINT       NOT NULL,
  requests_limit  INT          NULL,
  concurrent_limit INT         NULL,
  token_limit     BIGINT       NULL,
  cost_limit      DECIMAL(18,6) NULL,
  cost_currency   VARCHAR(8)   NULL,
  updated_by      BIGINT       NOT NULL,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_quota (scope_type, scope_id)
) ENGINE = InnoDB;

-- 管理操作审计。
CREATE TABLE IF NOT EXISTS admin_audits (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor_id     BIGINT       NOT NULL,
  action       VARCHAR(64)  NOT NULL,
  target_type  VARCHAR(32)  NOT NULL,
  target_id    BIGINT       NULL,
  detail       VARCHAR(500) NOT NULL DEFAULT '',
  result       VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_actor (actor_id),
  KEY idx_created (created_at)
) ENGINE = InnoDB;
