-- P14-C 增量迁移：非 SQL 步骤（Code/Report）真实工件 ID。
-- 只新增可空列，不改写既有数据；全部可重复执行（information_schema 元数据判断）。

USE sql_platform;

SET @add_analysis_run_id := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_steps ADD COLUMN analysis_run_id BIGINT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_steps' AND COLUMN_NAME = 'analysis_run_id'
);
PREPARE stmt_analysis_run_id FROM @add_analysis_run_id;
EXECUTE stmt_analysis_run_id;
DEALLOCATE PREPARE stmt_analysis_run_id;

SET @add_report_id := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_steps ADD COLUMN report_id BIGINT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'sql_platform' AND TABLE_NAME = 'agent_steps' AND COLUMN_NAME = 'report_id'
);
PREPARE stmt_report_id FROM @add_report_id;
EXECUTE stmt_report_id;
DEALLOCATE PREPARE stmt_report_id;

-- 报告步骤按 Run 恢复：同一 Run 的工件 ID 查询走序列索引即可，无需额外索引。
