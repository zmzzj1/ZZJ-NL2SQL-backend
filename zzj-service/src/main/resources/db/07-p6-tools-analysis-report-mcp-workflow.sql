-- P6 增量迁移：工具调用审计、Python 隔离分析、报告、MCP 服务、可视化工作流。
-- 只新增表，不改写 P2–P5 既有数据；全部可重复执行（IF NOT EXISTS）。
-- 分析/报告一律归属用户，通过服务端鉴权访问，不暴露物理文件路径。

USE sql_platform;

-- 工具调用审计：记录归属、上下文、状态与耗时，不落工具输出或敏感结果行。
CREATE TABLE IF NOT EXISTS tool_invocations (
  id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id           BIGINT        NOT NULL,
  tool_name         VARCHAR(80)   NOT NULL,
  conversation_id   BIGINT        NULL,
  agent_run_id      BIGINT        NULL,
  agent_step_id     BIGINT        NULL,
  workflow_run_id   BIGINT        NULL,
  workflow_node_id  BIGINT        NULL,
  status            VARCHAR(16)   NOT NULL,
  duration_ms       INT           NULL,
  error_code        VARCHAR(40)   NULL,
  created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_tool_invocations_user (user_id, created_at, id),
  KEY idx_tool_invocations_run (agent_run_id),
  CONSTRAINT fk_tool_invocations_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Python 分析运行：输入引用一次真实执行的受限结果（重新执行取真实行），不保存原始全量数据。
CREATE TABLE IF NOT EXISTS analysis_runs (
  id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id           BIGINT        NOT NULL,
  conversation_id   BIGINT        NULL,
  connection_id     BIGINT        NULL,
  agent_run_id      BIGINT        NULL,
  agent_step_id     BIGINT        NULL,
  query_history_id  BIGINT        NULL,
  source_sql        TEXT          NULL,
  engine            VARCHAR(24)   NOT NULL,
  status            VARCHAR(24)   NOT NULL,
  input_row_count   INT           NOT NULL DEFAULT 0,
  input_byte_limit  INT           NOT NULL DEFAULT 0,
  error_code        VARCHAR(40)   NULL,
  error_message     VARCHAR(300)  NULL,
  stdout_tail       VARCHAR(2000) NULL,
  stderr_tail       VARCHAR(2000) NULL,
  exit_code         INT           NULL,
  duration_ms       INT           NULL,
  created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_analysis_runs_user (user_id, created_at, id),
  KEY idx_analysis_runs_agent (agent_run_id, agent_step_id),
  CONSTRAINT fk_analysis_runs_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_analysis_runs_connection
    FOREIGN KEY (connection_id) REFERENCES connections (id) ON DELETE SET NULL,
  CONSTRAINT fk_analysis_runs_agent
    FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id) ON DELETE SET NULL
) ENGINE = InnoDB;

-- 分析结果工件：结构化图表/统计 JSON，受控大小，归属用户。
CREATE TABLE IF NOT EXISTS analysis_artifacts (
  id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  analysis_run_id BIGINT       NOT NULL,
  user_id         BIGINT       NOT NULL,
  kind            VARCHAR(24)  NOT NULL,
  mime_type       VARCHAR(64)  NOT NULL,
  size_bytes      INT          NOT NULL,
  content         MEDIUMTEXT   NOT NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_artifacts_run (analysis_run_id),
  KEY idx_artifacts_user (user_id, created_at, id),
  CONSTRAINT fk_artifacts_run
    FOREIGN KEY (analysis_run_id) REFERENCES analysis_runs (id) ON DELETE CASCADE,
  CONSTRAINT fk_artifacts_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- HTML 报告：净化后的内容 + 元数据，读写删除均服务端鉴权。
CREATE TABLE IF NOT EXISTS reports (
  id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id           BIGINT       NOT NULL,
  connection_id     BIGINT       NULL,
  agent_run_id      BIGINT       NULL,
  analysis_run_id   BIGINT       NULL,
  title             VARCHAR(200) NOT NULL,
  status            VARCHAR(24)  NOT NULL DEFAULT 'READY',
  html_content      MEDIUMTEXT   NOT NULL,
  size_bytes        INT          NOT NULL DEFAULT 0,
  revision          INT          NOT NULL DEFAULT 1,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_reports_user (user_id, created_at, id),
  CONSTRAINT fk_reports_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_reports_connection
    FOREIGN KEY (connection_id) REFERENCES connections (id) ON DELETE SET NULL,
  CONSTRAINT fk_reports_agent
    FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id) ON DELETE SET NULL,
  CONSTRAINT fk_reports_analysis
    FOREIGN KEY (analysis_run_id) REFERENCES analysis_runs (id) ON DELETE SET NULL
) ENGINE = InnoDB;

-- MCP 服务：仅预配置的受信服务，不含访问凭据。
CREATE TABLE IF NOT EXISTS mcp_servers (
  id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT        NOT NULL,
  name          VARCHAR(100)  NOT NULL,
  transport     VARCHAR(16)   NOT NULL,
  endpoint      VARCHAR(300)  NOT NULL,
  status        VARCHAR(16)   NOT NULL DEFAULT 'DISABLED',
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_mcp_servers_user_name (user_id, name),
  CONSTRAINT fk_mcp_servers_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 工作流定义：版本化 DAG（节点与边以 JSON 存储）。
CREATE TABLE IF NOT EXISTS workflows (
  id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id        BIGINT        NOT NULL,
  name           VARCHAR(100)  NOT NULL,
  description    VARCHAR(300)  NULL,
  definition     JSON          NOT NULL,
  revision       INT           NOT NULL DEFAULT 1,
  status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_workflows_user (user_id, updated_at, id),
  CONSTRAINT fk_workflows_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 工作流运行：一次执行的终态与进度。
CREATE TABLE IF NOT EXISTS workflow_runs (
  id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT        NOT NULL,
  workflow_id      BIGINT        NOT NULL,
  workflow_revision INT          NOT NULL,
  conversation_id  BIGINT        NULL,
  connection_id    BIGINT        NULL,
  status           VARCHAR(24)   NOT NULL,
  error_code       VARCHAR(40)   NULL,
  error_message    VARCHAR(300)  NULL,
  created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_workflow_runs_user (user_id, created_at, id),
  KEY idx_workflow_runs_workflow (workflow_id, id),
  CONSTRAINT fk_workflow_runs_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_workflow_runs_workflow
    FOREIGN KEY (workflow_id) REFERENCES workflows (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- 工作流节点运行状态：每个节点一次执行的可持久化状态。
CREATE TABLE IF NOT EXISTS workflow_nodes (
  id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workflow_run_id  BIGINT        NOT NULL,
  node_id          VARCHAR(80)   NOT NULL,
  node_type        VARCHAR(24)   NOT NULL,
  sequence         INT           NOT NULL DEFAULT 0,
  status           VARCHAR(24)   NOT NULL,
  agent_run_id     BIGINT        NULL,
  agent_step_id    BIGINT        NULL,
  analysis_run_id  BIGINT        NULL,
  report_id        BIGINT        NULL,
  output_summary   VARCHAR(500)  NULL,
  error_code       VARCHAR(40)   NULL,
  error_message    VARCHAR(300)  NULL,
  created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_workflow_node (workflow_run_id, node_id),
  KEY idx_workflow_nodes_run (workflow_run_id, sequence),
  CONSTRAINT fk_workflow_nodes_run
    FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs (id) ON DELETE CASCADE
) ENGINE = InnoDB;
