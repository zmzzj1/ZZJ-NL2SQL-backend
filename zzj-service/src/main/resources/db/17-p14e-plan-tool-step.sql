-- P14-E：计划内工具步骤的受控身份（E06 最小交接）。
-- 在既有 agent_steps 上追加两列，使暂停/审批/刷新恢复后仍以同一条真实工具身份执行。
-- 仅追加，不改旧列；幂等：通过 information_schema 判断列是否已存在。

USE sql_platform;

SET @db = DATABASE();

SET @exist_tool_name = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'agent_steps' AND COLUMN_NAME = 'tool_name'
);
SET @sql_tool_name = IF(@exist_tool_name = 0,
  'ALTER TABLE agent_steps ADD COLUMN tool_name VARCHAR(120) NULL COMMENT ''计划内工具步骤的真实工具名''',
  'SELECT 1');
PREPARE stmt_tool_name FROM @sql_tool_name;
EXECUTE stmt_tool_name;
DEALLOCATE PREPARE stmt_tool_name;

SET @exist_tool_args = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'agent_steps' AND COLUMN_NAME = 'tool_args'
);
SET @sql_tool_args = IF(@exist_tool_args = 0,
  'ALTER TABLE agent_steps ADD COLUMN tool_args JSON NULL COMMENT ''计划内工具步骤的受控参数（经 schema 校验）''',
  'SELECT 1');
PREPARE stmt_tool_args FROM @sql_tool_args;
EXECUTE stmt_tool_args;
DEALLOCATE PREPARE stmt_tool_args;
