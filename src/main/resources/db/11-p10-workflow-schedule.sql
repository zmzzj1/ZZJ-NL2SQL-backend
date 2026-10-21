-- P10 定时工作流调度：计划（schedules）+ 触发记录（schedule_occurrences）
-- 风格约定：全部 CREATE TABLE IF NOT EXISTS，可重复执行；只新增，不删改 P1-P9。
-- 时间统一 UTC DATETIME(3)；计划状态与触发运行状态分别建模。

USE sql_platform;

CREATE TABLE IF NOT EXISTS workflow_schedules (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_user_id      BIGINT       NOT NULL,
    workspace_id       BIGINT       NULL,
    workflow_id        BIGINT       NOT NULL,
    workflow_revision  INT          NOT NULL,
    connection_id      BIGINT       NOT NULL,
    conversation_id    BIGINT       NULL,
    user_input         VARCHAR(2000) NULL,
    agent_id           BIGINT       NULL,
    agent_version_id   BIGINT       NULL,
    cron_expression    VARCHAR(64)  NOT NULL,
    cron_normalized    VARCHAR(64)  NOT NULL,
    timezone           VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',          -- ACTIVE / PAUSED
    misfire_policy     VARCHAR(16)  NOT NULL DEFAULT 'SKIP',            -- SKIP / FIRE_ONCE
    overlap_policy     VARCHAR(24)  NOT NULL DEFAULT 'SKIP_WHILE_RUNNING', -- SKIP_WHILE_RUNNING / QUEUE_ONE
    next_fire_at       DATETIME(3)  NULL,                               -- UTC
    last_fire_at       DATETIME(3)  NULL,                               -- UTC
    revision           INT          NOT NULL DEFAULT 1,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_schedules_due (status, next_fire_at, id),
    KEY idx_schedules_user (owner_user_id, updated_at, id),
    KEY idx_schedules_workflow (workflow_id),
    CONSTRAINT fk_schedules_user FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_schedules_workflow FOREIGN KEY (workflow_id) REFERENCES workflows (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_schedule_occurrences (
    id                  BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    schedule_id         BIGINT        NOT NULL,
    fire_at_utc         DATETIME(3)   NOT NULL,                         -- UTC
    fire_at_local       VARCHAR(32)   NULL,                             -- 审计展示用本地时间
    workflow_revision   INT           NOT NULL,
    state               VARCHAR(24)   NOT NULL DEFAULT 'SCHEDULED',     -- SCHEDULED/CLAIMED/WAITING_APPROVAL/RUNNING/SUCCEEDED/FAILED/CANCELED/SKIPPED
    attempt             INT           NOT NULL DEFAULT 1,
    trigger_source      VARCHAR(16)   NOT NULL DEFAULT 'SCHEDULER',     -- SCHEDULER / MANUAL
    workflow_run_id     BIGINT        NULL,
    error_code          VARCHAR(40)   NULL,
    error_message       VARCHAR(300)  NULL,
    lease_owner         VARCHAR(64)   NULL,
    lease_expires_at    DATETIME(3)   NULL,                             -- UTC
    created_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_occurrence_idem (schedule_id, fire_at_utc, workflow_revision, attempt),
    KEY idx_occurrences_due (state, fire_at_utc, id),
    KEY idx_occurrences_lease (state, lease_expires_at),
    KEY idx_occurrences_schedule (schedule_id, fire_at_utc DESC),
    CONSTRAINT fk_occurrences_schedule FOREIGN KEY (schedule_id) REFERENCES workflow_schedules (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
