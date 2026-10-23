-- P14-E：MCP 服务生命周期（用户级持久化管理）。
-- 在既有 mcp_servers 表上追加实际用到的列：stdio 参数与受控的非敏感环境元数据。
-- 仅追加，不删除旧表/旧列，不另建 mcp_server_config。
-- 幂等：通过 information_schema 判断列是否已存在。

SET @db = DATABASE();

SET @exist_args = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'mcp_servers' AND COLUMN_NAME = 'args'
);
SET @sql_args = IF(@exist_args = 0,
  'ALTER TABLE mcp_servers ADD COLUMN args JSON NULL COMMENT ''STDIO 启动参数（非敏感元数据）''',
  'SELECT 1');
PREPARE stmt_args FROM @sql_args;
EXECUTE stmt_args;
DEALLOCATE PREPARE stmt_args;

SET @exist_env = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'mcp_servers' AND COLUMN_NAME = 'env_meta'
);
SET @sql_env = IF(@exist_env = 0,
  'ALTER TABLE mcp_servers ADD COLUMN env_meta JSON NULL COMMENT ''受控的非敏感环境元数据（拒绝 secret/token 等）''',
  'SELECT 1');
PREPARE stmt_env FROM @sql_env;
EXECUTE stmt_env;
DEALLOCATE PREPARE stmt_env;
