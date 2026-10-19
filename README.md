# QueryForge 后端（真实用户与多数据源）

Java 21 + Spring Boot 3.5.6 + Spring Security 6 + Maven。提供认证、MySQL 连接管理、会话与查询历史、SSE SQL 生成、只读执行、Agent 多步运行和业务知识检索。

## 环境要求

- JDK 21（验证使用 Temurin 21.0.12.1）
- Maven 3.9+（验证使用 3.9.1）
- MySQL 8（验证使用 8.4.11）
- 可选：OpenAI 兼容模型服务

## 主要能力

- 会话、消息和查询历史持久化，资源按登录用户隔离。
- Agent 多步规划、逐步确认执行、限次 SQL 修复、取消与运行恢复。
- Manager 结构化路由、多角色轨迹和 LLM 调用次数限制。
- 业务知识 CRUD、按连接鉴权检索和会话记忆摘要。

## 数据库初始化

```bash
# 管理库：用户与连接配置
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < src/main/resources/db/02-management-schema.sql

# 两个隔离测试库：sql_demo_a(shop_*) / sql_demo_b(blog_*)
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < src/main/resources/db/03-test-databases.sql

# 按版本顺序执行管理库增量脚本
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < src/main/resources/db/04-conversations-history.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < src/main/resources/db/05-agent-runs.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < src/main/resources/db/06-knowledge-memory.sql
```

创建账号（密码由本地管理员设置，不提交仓库）：

```sql
CREATE USER 'platform_app'@'127.0.0.1' IDENTIFIED BY '<management-password>';
GRANT ALL PRIVILEGES ON sql_platform.* TO 'platform_app'@'127.0.0.1';
CREATE USER 'demo_a_reader'@'127.0.0.1' IDENTIFIED BY '<a-reader-password>';
GRANT SELECT ON sql_demo_a.* TO 'demo_a_reader'@'127.0.0.1';
CREATE USER 'demo_b_reader'@'127.0.0.1' IDENTIFIED BY '<b-reader-password>';
GRANT SELECT ON sql_demo_b.* TO 'demo_b_reader'@'127.0.0.1';
FLUSH PRIVILEGES;
```

## 配置

参考 `.env.example`，通过本机环境变量或外部 Spring 配置提供以下值：

```properties
MANAGEMENT_DB_URL=jdbc:mysql://127.0.0.1:3306/sql_platform?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowMultiQueries=false
MANAGEMENT_DB_USERNAME=platform_app
MANAGEMENT_DB_PASSWORD=<management-password>
APP_ENCRYPTION_KEY=<base64-32-bytes>
```

生成加密密钥：`openssl rand -base64 32`。该密钥必须与数据库分开保管。

环境变量：

| 变量 | 说明 |
| --- | --- |
| `MANAGEMENT_DB_URL/USERNAME/PASSWORD` | 覆盖管理库 |
| `APP_ENCRYPTION_KEY` | 连接密码加密密钥（32 字节 Base64） |
| `CONNECTION_ALLOWED_HOSTS` | 允许连接的主机，默认 `127.0.0.1,localhost` |
| `CONNECTION_ALLOWED_PORTS` | 允许连接的端口，默认 `3306,3307,3308,3309,3310` |
| `LLM_BASE_URL/API_KEY/MODEL` | 模型配置；无 Key 时生成返回 503 |
| `SQL_MAX_ROWS` / `SQL_QUERY_TIMEOUT_SECONDS` | 行数/超时限制 |
| `AGENT_MAX_STEPS` / `AGENT_MAX_REPAIRS` | Agent 最大步骤数/单步最大修复次数 |
| `AGENT_MAX_PLAN_CHARS` / `AGENT_MAX_LLM_CALLS` | 计划长度/单次运行 LLM 调用上限 |

## 启动与测试

```powershell
$env:JAVA_HOME = 'D:\dev\tools\jdk-21.0.12.1+1'
mvn verify
mvn spring-boot:run
```

健康检查：`GET http://127.0.0.1:8080/api/health`。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | 注册（BCrypt） |
| POST | `/api/v1/auth/login` | 登录，建立 Session |
| GET | `/api/v1/auth/me` | 当前用户 |
| POST | `/api/v1/auth/logout` | 注销 |
| GET | `/api/v1/auth/csrf` | CSRF Token |
| GET/POST | `/api/v1/connections` | 本人连接列表/新增 |
| PATCH/DELETE | `/api/v1/connections/{id}` | 修改/删除 |
| POST | `/api/v1/connections/{id}/test` | 真实连通性测试 |
| GET | `/api/v1/connections/{id}/schema` | JDBC 元数据 |
| GET/POST | `/api/v1/conversations` | 会话列表/新建 |
| GET/DELETE | `/api/v1/conversations/{id}` | 会话详情/删除 |
| GET/DELETE | `/api/v1/query-history[/{id}]` | 查询历史列表、详情与删除 |
| POST | `/api/v1/sql/generate/stream` | SSE，`connectionId` 决定 Schema |
| POST | `/api/v1/sql/execute` | 只读执行，`connectionId + sql` |
| POST | `/api/v1/agent/runs/stream` | 创建并流式运行 Agent |
| POST | `/api/v1/agent/runs/{runId}/steps/{stepId}/approve/stream` | 确认步骤并继续运行 |
| POST | `/api/v1/agent/runs/{runId}/steps/{stepId}/reject` | 拒绝步骤 |
| POST | `/api/v1/agent/runs/{runId}/cancel` | 取消运行 |
| GET | `/api/v1/agent/runs`、`/{runId}`、`/latest` | 查询与恢复运行 |
| POST/GET/PUT/DELETE | `/api/v1/knowledge/**` | 业务知识维护与关键词检索 |

## 安全边界

- Spring Security Session + HttpOnly Cookie + CSRF（`X-XSRF-TOKEN`）。
- 连接密码 AES-256-GCM 加密，接口不返回密码/密文；密钥独立配置。
- 主机/端口白名单，数据库名/用户名只允许安全标识符；不接受任意 JDBC URL。
- 所有连接查询带 `user_id`；跨用户资源统一 404。
- SQL 执行：JSqlParser + 危险函数/系统库/跨库拒绝 + 只读账号 + `SET SESSION TRANSACTION READ ONLY` + 行数/超时。
- 模型输出不会自动执行，必须用户显式提交。

## 本地模型测试桩

```bash
node tools/llm-stub.mjs 9099
# 另开终端
$env:LLM_BASE_URL='http://127.0.0.1:9099/v1'; $env:LLM_API_KEY='stub-key'; mvn spring-boot:run
```

测试桩按服务端注入的 Schema 返回 `shop_orders` 或 `blog_posts` 查询；不是真实模型。

## 测试

- `mvn test`：103 个自动化用例，包含 Controller 切片与 Service 单元测试。
