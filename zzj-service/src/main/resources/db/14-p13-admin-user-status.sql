-- P13-M3B：平台管理员启用/停用用户。
-- 增量迁移，可重复执行，不清理已有用户或业务数据。

USE sql_platform;

SET @user_status_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'status'
);
SET @ddl := IF(@user_status_col = 0,
  'ALTER TABLE users ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''ACTIVE'' AFTER role',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
