#!/usr/bin/env bash
set -Eeuo pipefail

mysql_root=(mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" --default-character-set=utf8mb4)
schema_dir=/queryforge-db

"${mysql_root[@]}" < "${schema_dir}/02-management-schema.sql"
"${mysql_root[@]}" < "${schema_dir}/03-test-databases.sql"

for migration in \
  04-conversations-history.sql \
  05-agent-runs.sql \
  06-knowledge-memory.sql \
  07-p6-tools-analysis-report-mcp-workflow.sql \
  08-p7-agent-studio-skill.sql \
  09-p8-workspace-rag-memory.sql \
  10-p9-model-governance.sql \
  11-p10-workflow-schedule.sql \
  12-p11-agent-graph.sql \
  14-p13-admin-user-status.sql \
  15-p14c-agent-step-artifacts.sql \
  15-p14-e-mcp-server-lifecycle.sql \
  16-p14d-plan-hitl.sql \
  17-p14e-plan-tool-step.sql \
  18-p14arch01-graph-thread.sql
do
  "${mysql_root[@]}" sql_platform < "${schema_dir}/${migration}"
done

sql_escape() {
  local value=${1//\\/\\\\}
  printf '%s' "${value//\'/\\\'}"
}

app_password=$(sql_escape "${MYSQL_APP_PASSWORD}")
demo_a_password=$(sql_escape "${DEMO_A_PASSWORD}")
demo_b_password=$(sql_escape "${DEMO_B_PASSWORD}")

"${mysql_root[@]}" <<SQL
CREATE USER IF NOT EXISTS 'platform_app'@'%' IDENTIFIED BY '${app_password}';
ALTER USER 'platform_app'@'%' IDENTIFIED BY '${app_password}';
GRANT ALL PRIVILEGES ON sql_platform.* TO 'platform_app'@'%';

CREATE USER IF NOT EXISTS 'demo_a_reader'@'%' IDENTIFIED BY '${demo_a_password}';
ALTER USER 'demo_a_reader'@'%' IDENTIFIED BY '${demo_a_password}';
GRANT SELECT ON sql_demo_a.* TO 'demo_a_reader'@'%';

CREATE USER IF NOT EXISTS 'demo_b_reader'@'%' IDENTIFIED BY '${demo_b_password}';
ALTER USER 'demo_b_reader'@'%' IDENTIFIED BY '${demo_b_password}';
GRANT SELECT ON sql_demo_b.* TO 'demo_b_reader'@'%';
FLUSH PRIVILEGES;
SQL
