# QueryForge 后端（真实用户与多数据源）

Java 21 + Spring Boot 3.5.6 + Spring Security 6 + Maven。提供认证、MySQL 连接管理、会话与查询历史、SSE SQL 生成、只读执行、Agent 多步运行、业务知识检索、隔离数据分析、HTML 报告、MCP 工具接入、可视化工作流、Agent Studio、Skill Registry、工作空间、混合 RAG 与长期记忆；P9 新增统一模型治理、路由、配额与可观测性。

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
- 受控工具注册表（只读工具 + 审计）、真实 MCP stdio 客户端（预配置白名单服务）。
- 隔离 Python 数据分析（Docker 沙箱，不可用时拒绝回退宿主机）、内置确定性分析器。
- 结构化图表（table/bar/line/pie）与净化 HTML 报告（CSP + 沙箱预览）。
- 可视化工作流 DAG（校验、版本化、串行执行、SQL 审批暂停/恢复、SSE）。
- Agent Studio：草稿 CRUD、工具白名单、RAG/Memory 策略、Skill 绑定、发布不可变版本、回滚与审计。
- Skill Registry：CRUD、不可变版本发布、模板白名单变量校验、运行时按相关性选择并注入。
- 运行配置统一解析（`config/RuntimeConfigResolver`），配置真正影响 Manager/Agent Run/Tool Registry；Run 固定版本快照。
- 工作空间：OWNER/ADMIN/EDITOR/VIEWER 成员角色与资源授权；共享知识或 Agent 不自动授予数据源和 SQL 执行权限。
- 混合 RAG：关键词、真实 Embedding 向量和 RRF 融合三种模式，命中结果保留来源、版本与分数。
- 长期记忆：个人/空间作用域、类型、来源、置信度、去重、禁用与检索，并按用户及空间权限隔离。
- 模型治理：供应商与能力档案、用户/空间/Agent 授权、版本化路由策略、熔断、有界重试和故障转移。
- 平台管理：模型调用记录、用量与失败分类、配额、管理审计；管理接口仅允许服务端持久化的 PLATFORM_ADMIN。

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
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < src/main/resources/db/07-p6-tools-analysis-report-mcp-workflow.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < src/main/resources/db/08-p7-agent-studio-skill.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < src/main/resources/db/09-p8-workspace-rag-memory.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < src/main/resources/db/10-p9-model-governance.sql
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
| `EMBEDDING_BASE_URL/API_KEY/MODEL/DIMENSIONS` | OpenAI 兼容 Embedding 配置；默认地址与密钥可继承 LLM 配置 |
| `EMBEDDING_REQUEST_TIMEOUT` | Embedding 请求超时，默认 30 秒 |
| `SQL_MAX_ROWS` / `SQL_QUERY_TIMEOUT_SECONDS` | 行数/超时限制 |
| `AGENT_MAX_STEPS` / `AGENT_MAX_REPAIRS` | Agent 最大步骤数/单步最大修复次数 |
| `AGENT_MAX_PLAN_CHARS` / `AGENT_MAX_LLM_CALLS` | 计划长度/单次运行 LLM 调用上限 |
| `ANALYSIS_MAX_INPUT_ROWS/BYTES/CODE_CHARS` | 分析输入行数/字节/代码字符上限 |
| `ANALYSIS_TIMEOUT_SECONDS` / `ANALYSIS_MEMORY_BYTES` / `ANALYSIS_CPUS` | 沙箱超时/内存/CPU 限额 |
| `ANALYSIS_SANDBOX_IMAGE` / `ANALYSIS_SANDBOX_COMMAND` | 沙箱镜像/入口命令 |
| `MCP_ECHO_SCRIPT` | 本地 MCP 测试服务脚本路径（默认 `tools/mcp-echo-server.mjs`） |

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
| GET/POST | `/api/v1/tools`、`/api/v1/tools/invoke` | 只读工具目录与调用 |
| POST/GET | `/api/v1/analysis/runs/**` | Python 隔离分析（SSE）与详情 |
| POST/GET/DELETE | `/api/v1/reports`、`/api/v1/reports/{id}`、`/{id}/preview` | 报告生成、读取与净化预览 |
| GET/POST | `/api/v1/mcp/servers`、`.../{name}/test`、`.../{tool}/call` | MCP 预配置服务发现与只读调用 |
| POST/GET/PUT/DELETE | `/api/v1/workflows`、`/api/v1/workflows/{id}` | 工作流 DAG 定义与版本化 |
| POST/GET | `/api/v1/workflows/{id}/runs/stream`、`.../approve/stream` | 工作流运行与审批续跑 |
| GET/POST/PATCH/DELETE | `/api/v1/studio/agents/**` | Agent Studio：草稿、发布、版本、回滚、测试运行 |
| GET/POST/PATCH/DELETE | `/api/v1/skills/**` | Skill Registry：CRUD、发布、版本 |
| GET/POST/PATCH/DELETE | `/api/v1/workspaces/**` | 工作空间、成员角色与资源授权 |
| GET/POST/PATCH/DELETE | `/api/v1/memories/**` | 类型化长期记忆维护与检索 |
| POST | `/api/v1/knowledge/search` | 按权限执行 KEYWORD、VECTOR 或 HYBRID 检索 |
| GET/POST/PATCH | `/api/v1/model/**` | 模型供应商、能力档案、授权与路由策略 |
| GET/POST | `/api/v1/admin/**` | 管理概览、模型用量、配额和审计 |

## 安全边界

- Spring Security Session + HttpOnly Cookie + CSRF（`X-XSRF-TOKEN`）。
- 连接密码 AES-256-GCM 加密，接口不返回密码/密文；密钥独立配置。
- 主机/端口白名单，数据库名/用户名只允许安全标识符；不接受任意 JDBC URL。
- 所有连接查询带 `user_id`；跨用户资源统一 404。
- SQL 执行：JSqlParser + 危险函数/系统库/跨库拒绝 + 只读账号 + `SET SESSION TRANSACTION READ ONLY` + 行数/超时。
- 模型输出不会自动执行，必须用户显式提交。
- Python 分析代码仅在隔离 Docker 容器执行（非 root、禁网、只读、去 capabilities、限额）；沙箱不可用时拒绝执行，不回退宿主机。
- 分析输入仅来自服务端重新校验权限 + SQL Guard 后重新执行的真实受限结果，不接受前端伪造数组。
- HTML 报告经白名单净化 + CSP + 沙箱 iframe 预览，禁止脚本执行。
- MCP 仅连预配置白名单服务，不提供任意 URL/命令注册；工具输出视为不可信数据。
- Agent 工具白名单在服务端真实过滤（运行关联 Agent 时非白名单工具拒绝），自定义提示词仅追加在平台安全指令之后。
- Skill 是低信任提示词模板：只允许白名单变量、不执行脚本、不扩权、不绕过 SQL 审批。
- 工作空间授权与资源自身权限分离；共享资源不会绕过连接归属、SQL Guard 或执行审批。
- RAG 候选在数据库查询阶段按用户/空间过滤；Embedding 不可用时向量与混合模式明确失败，不伪装成向量召回。
- 长期记忆按个人/空间隔离，携带来源与置信度；用户可编辑、禁用或删除。
- 平台管理员身份只由数据库用户角色决定；Workspace OWNER 不自动获得平台管理权限。
- 模型供应商 Key 加密保存且永不回传；供应商 URL 拒绝回环、私网、保留网段及非 HTTP(S) 协议。
- 路由先执行能力与授权过滤，再应用策略；流式响应中断后不拼接其他模型，SQL Guard 与审批边界保持不变。

## 本地模型测试桩

```bash
node tools/llm-stub.mjs 9099
# 另开终端
$env:LLM_BASE_URL='http://127.0.0.1:9099/v1'; $env:LLM_API_KEY='stub-key'; mvn spring-boot:run
```

测试桩按服务端注入的 Schema 返回 `shop_orders` 或 `blog_posts` 查询；不是真实模型。

## 测试

- `mvn test`：174 个自动化用例，包含 Controller 切片、Service 单元测试，以及 P6–P9（分析器/报告/工具/工作流/Studio/Skill/Workspace/RAG/Memory/模型路由/熔断/重试/权限）单测。
