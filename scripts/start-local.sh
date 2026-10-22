#!/usr/bin/env bash
# P13-M1 本地开发启动：Nacos -> zzj-service -> zzj-gateway -> Vite
# 用法（每个目标单独启动，互不阻塞）：
#   bash backend/scripts/start-local.sh nacos
#   PG_PASSWORD='<postgres-password>' bash backend/scripts/start-local.sh service
#   bash backend/scripts/start-local.sh gateway
#   cd frontend && npm run dev -- --port 5174 --strictPort
#
# 说明：
#   * 统一以 `D:\ZZJ-NL2SQL\backend` 为工作目录启动 Service/Gateway，
#     这样 `./config/local.properties` 与 `tools/mcp-echo-server.mjs` 的相对语义与迁移前完全一致。
#   * 不含任何 Git 命令；密钥只从 backend/config/local.properties（gitignored）读取。
set -euo pipefail

BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME_LOCAL="${JAVA_HOME_LOCAL:-D:/dev/tools/jdk-21.0.12.1+1}"
# Nacos Server 3.x（解压在开发机目录，不进仓库）。可用 NACOS_HOME 覆盖。
# 注意：3.0.3 在本机 JDK 21 上内嵌 Derby 无法启动，实测可用版本为 3.2.4（客户端仍为 SCA 锁定的 3.0.3）。
NACOS_HOME="${NACOS_HOME:-D:/dev/tools/nacos-server-3.2.4/nacos}"

start_nacos() {
  # 注意：本机会话里存在被污染的 SERVER__PORT 环境变量，会让 Nacos 控制台上下文绑错端口，启动前必须清除。
  unset SERVER__PORT || true
  export JAVA_HOME="$JAVA_HOME_LOCAL"
  (cd "$NACOS_HOME/bin" && cmd //c "startup.cmd -m standalone")
}

start_service() {
  export JAVA_HOME="$JAVA_HOME_LOCAL"
  cd "$BACKEND_DIR"
  # cwd 必须是 backend/：./config 与 tools/ 相对路径依赖它。
  "$JAVA_HOME_LOCAL/bin/java" \
    -jar zzj-service/target/zzj-service.jar \
    --server.port=8081
}

start_gateway() {
  export JAVA_HOME="$JAVA_HOME_LOCAL"
  cd "$BACKEND_DIR"
  "$JAVA_HOME_LOCAL/bin/java" \
    -jar zzj-gateway/target/zzj-gateway.jar \
    --server.port=8080
}

case "${1:-}" in
  nacos)   start_nacos ;;
  service) start_service ;;
  gateway) start_gateway ;;
  *)
    echo "用法: $0 {nacos|service|gateway}"
    exit 2
    ;;
esac
