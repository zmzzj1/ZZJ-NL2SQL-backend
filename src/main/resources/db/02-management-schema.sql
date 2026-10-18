-- 可写管理库：仅保存用户与连接配置，不存放业务查询数据。
CREATE DATABASE IF NOT EXISTS sql_platform
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE sql_platform;

CREATE TABLE IF NOT EXISTS users (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  email         VARCHAR(255) NOT NULL,
  display_name  VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS connections (
  id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id             BIGINT       NOT NULL,
  name                VARCHAR(100) NOT NULL,
  engine              VARCHAR(16)  NOT NULL,
  host                VARCHAR(255) NOT NULL,
  port                INT          NOT NULL,
  database_name       VARCHAR(64)  NOT NULL,
  username            VARCHAR(64)  NOT NULL,
  password_ciphertext TEXT         NOT NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_connections_user (user_id),
  CONSTRAINT fk_connections_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;
