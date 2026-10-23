-- P14-D 增量迁移：计划级 HITL 风险审核所需的 Run 元数据。
-- 只新增列、不重写既有数据；MySQL 不支持 ADD COLUMN IF NOT EXISTS，
-- 统一用 information_schema 元数据判断保证可重复执行（已存在则跳过）。

USE sql_platform;

SET @add_plan_auto_confirm := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN plan_auto_confirm TINYINT NOT NULL DEFAULT 1',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'plan_auto_confirm'
);
PREPARE stmt_plan_auto_confirm FROM @add_plan_auto_confirm;
EXECUTE stmt_plan_auto_confirm;
DEALLOCATE PREPARE stmt_plan_auto_confirm;

SET @add_plan_review_version := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN plan_review_version INT NOT NULL DEFAULT 0',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'plan_review_version'
);
PREPARE stmt_plan_review_version FROM @add_plan_review_version;
EXECUTE stmt_plan_review_version;
DEALLOCATE PREPARE stmt_plan_review_version;

SET @add_plan_review_status := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN plan_review_status VARCHAR(24) NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'plan_review_status'
);
PREPARE stmt_plan_review_status FROM @add_plan_review_status;
EXECUTE stmt_plan_review_status;
DEALLOCATE PREPARE stmt_plan_review_status;

SET @add_plan_review_required := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN plan_review_required TINYINT NOT NULL DEFAULT 0',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'plan_review_required'
);
PREPARE stmt_plan_review_required FROM @add_plan_review_required;
EXECUTE stmt_plan_review_required;
DEALLOCATE PREPARE stmt_plan_review_required;

SET @add_plan_review_reason := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN plan_review_reason VARCHAR(300) NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'plan_review_reason'
);
PREPARE stmt_plan_review_reason FROM @add_plan_review_reason;
EXECUTE stmt_plan_review_reason;
DEALLOCATE PREPARE stmt_plan_review_reason;

SET @add_plan_rejection_count := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN plan_rejection_count INT NOT NULL DEFAULT 0',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'plan_rejection_count'
);
PREPARE stmt_plan_rejection_count FROM @add_plan_rejection_count;
EXECUTE stmt_plan_rejection_count;
DEALLOCATE PREPARE stmt_plan_rejection_count;

SET @add_plan_review_feedback := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_runs ADD COLUMN plan_review_feedback VARCHAR(4000) NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_runs' AND COLUMN_NAME = 'plan_review_feedback'
);
PREPARE stmt_plan_review_feedback FROM @add_plan_review_feedback;
EXECUTE stmt_plan_review_feedback;
DEALLOCATE PREPARE stmt_plan_review_feedback;
