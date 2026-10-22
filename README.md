# QueryForge 后端（真实用户与多数据源）

Java 21 + Spring Boot 3.5.6 + Spring Security 6 + Maven。提供认证、MySQL 连接管理、会话与查询历史、SSE SQL 生成、只读执行、Agent 多步运行、业务知识检索、隔离数据分析、HTML 报告、MCP 工具接入、可视化工作流、Agent Studio、Skill Registry、工作空间、混合 RAG 与长期记忆；P9 新增统一模型治理、路由、配额与可观测性；P10 新增工作流定时调度与安全 CSV 导出；P11 新增统一 Agent 执行内核（统一状态图 + 检查点）、专业角色选择与权限门禁、L1–L4 分层上下文压缩，并让 Spring AI 作为可选的底层模型传输层（不绕过 P9 路由治理）。

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
- 工作流定时调度：Cron/时区校验、未来时间预览、启停、幂等触发、数据库租约、misfire/overlap 策略；含 SQL 的运行仍停在审批边界。
- 查询历史 CSV 导出：重新校验资源权限与 SQL Guard、重新执行受限查询，并防止表格公式注入。
- Agent Studio：草稿 CRUD、工具白名单、RAG/Memory 策略、Skill 绑定、发布不可变版本、回滚与审计。
- Skill Registry：CRUD、不可变版本发布、模板白名单变量校验、运行时按相关性选择并注入。
- 运行配置由 `zzj-service/src/main/java/com/queryforge/application/agent/RuntimeConfigResolver.java` 统一解析，配置真正影响 Manager/Agent Run/Tool Registry；Run 固定版本快照。
- 工作空间：OWNER/ADMIN/EDITOR/VIEWER 成员角色与资源授权；共享知识或 Agent 不自动授予数据源和 SQL 执行权限。
- 混合 RAG：关键词、真实 Embedding 向量和 RRF 融合三种模式，命中结果保留来源、版本与分数。
- 长期记忆：个人/空间作用域、类型、来源、置信度、去重、禁用与检索，并按用户及空间权限隔离。
- 模型治理：供应商与能力档案、用户/空间/Agent 授权、版本化路由策略、熔断、有界重试和故障转移。
- 平台管理：模型调用记录、用量与失败分类、配额、管理审计；管理接口仅允许服务端持久化的 PLATFORM_ADMIN。
- 统一状态图：运行节点（ROUTING/PLANNING/CONTEXT_BUILDING/RUNNING/SQL_GENERATION/WAITING_SQL_APPROVAL/SQL_EXECUTION/REPORT_GENERATION/COMPLETED/FAILED/CANCELED 等）带合法性校验，检查点持久化到 `agent_graph_checkpoints`，SSE `node.*` 轨迹与详情恢复同一套状态。
- 专业角色：Manager/Planner/Data Analyst/Code Analyst/Tool Assistant/Report Assistant 具备角色定义、输入契约与工具白名单，运行时拒绝越权调用；RoleAssigner 的选择结果持久化到步骤并由前端展示。
- L1–L4 分层上下文：渐进装入、脱敏压缩元数据、预算压力下保留审批/授权/关键事实，Token 估算明确标注为近似值。
- Spring AI 可选传输层：`app.llm.use-spring-ai=true` 时以 Spring AI ChatClient 作为底层模型传输（`SpringAiModelTransport`），但统一入口仍是 P9 `RoutedLlmClient`（路由/配额/审计/熔断/故障转移不变），绝不替换或绕过。

## 数据库初始化

```bash
# 管理库：用户与连接配置
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < zzj-service/src/main/resources/db/02-management-schema.sql

# 两个隔离测试库：sql_demo_a(shop_*) / sql_demo_b(blog_*)
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < zzj-service/src/main/resources/db/03-test-databases.sql

# 按版本顺序执行管理库增量脚本
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < zzj-service/src/main/resources/db/04-conversations-history.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < zzj-service/src/main/resources/db/05-agent-runs.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < zzj-service/src/main/resources/db/06-knowledge-memory.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < zzj-service/src/main/resources/db/07-p6-tools-analysis-report-mcp-workflow.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < zzj-service/src/main/resources/db/08-p7-agent-studio-skill.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < zzj-service/src/main/resources/db/09-p8-workspace-rag-memory.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < zzj-service/src/main/resources/db/10-p9-model-governance.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < zzj-service/src/main/resources/db/11-p10-workflow-schedule.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < zzj-service/src/main/resources/db/12-p11-agent-graph.sql
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
| `SCHEDULE_POLL_INTERVAL_MS` / `SCHEDULE_LEASE_SECONDS` | 调度轮询间隔/执行租约秒数 |
| `SCHEDULE_MAX_CLAIM_BATCH` / `SCHEDULE_EXECUTION_THREADS` | 单轮最大领取数/执行并发数 |
| `SCHEDULE_CATCH_UP_WINDOW_MINUTES` / `SCHEDULE_MIN_INTERVAL_MINUTES` | 补偿窗口/允许的最短计划周期 |
| `ANALYSIS_MAX_INPUT_ROWS/BYTES/CODE_CHARS` | 分析输入行数/字节/代码字符上限 |
| `ANALYSIS_TIMEOUT_SECONDS` / `ANALYSIS_MEMORY_BYTES` / `ANALYSIS_CPUS` | 沙箱超时/内存/CPU 限额 |
| `ANALYSIS_SANDBOX_IMAGE` / `ANALYSIS_SANDBOX_COMMAND` | 沙箱镜像/入口命令 |
| `MCP_ECHO_SCRIPT` | 本地 MCP 测试服务脚本路径（默认 `tools/mcp-echo-server.mjs`） |

Spring 属性（通过 `--app.*=value` 或外部配置传入）：

| 属性 | 说明 |
| --- | --- |
| `app.llm.use-spring-ai` | 是否用 Spring AI ChatClient 作为底层模型传输层；默认 `false`（用内置 HTTP 客户端）。无论取值，统一入口都是 `RoutedLlmClient`，路由/配额/审计/熔断/故障转移语义不变，Spring AI 只替换最底层的 I/O 实现 |

## 启动与测试

P13-M1 起本目录是 Maven 聚合父工程（`packaging=pom`），含两个可独立运行的子模块：

| 模块 | 端口 | 说明 |
| --- | --- | --- |
| `zzj-service` | 8081（仅回环） | 全部既有核心业务（SQL/Agent/Workflow/RAG/Memory/Studio/认证），注册到 Nacos |
| `zzj-gateway` | 8080 | 公开接入层：WebFlux Gateway + Nacos Discovery + LoadBalancer，转发 `/api/**` 到 `lb://zzj-service` |

前端 `/api` 仍然指向 8080（Vite 代理不变），因此页面代码无需改动；8080 现在是网关而不是业务进程。

### 入口认证（P13-M2）

网关对 `/api/**` **默认要求登录**，只放行经核实的精确匿名端点；其余请求用核心服务已建立的
Sa-Token/Redis 会话判定：

- 匿名白名单（精确 Method + Path）：`GET /api/health`、`POST /api/v1/auth/register`、
  `POST /api/v1/auth/login`、`GET /api/v1/auth/csrf`。`logout` 与 `me` 不在其中。
- 未登录 → `401 {"code":"UNAUTHENTICATED",...}`；会话存储不可用 → `503 {"code":"SERVICE_UNAVAILABLE",...}`
  （失败关闭，不静默放行）。响应体形状与核心服务一致，前端无需改动。
- 网关只判断"能否继续"，**不签发身份、不构造可信身份头、不做资源授权**；连接/Workspace/管理员/
  SQL 审批/工具权限仍由 `zzj-service` 独立判断。
- 客户端自填的 `X-User-Id`、`X-Role`、`X-Workspace*`、`X-Internal*` 等头在网关边界即被移除。
- 网关会话校验只读 Redis（不写、不续期），且连接惰性：Redis 晚于网关启动不会让网关起不来。

```powershell
# 确保 JAVA_HOME 指向本机安装的 JDK 21
mvn verify            # 在 backend/ 下构建整个 Reactor

# 逐个启动（统一以 backend/ 为工作目录，保证 ./config 与 tools/ 相对路径语义不变）
# 1) Nacos（standalone）
# 2) zzj-service
$env:PG_PASSWORD='<postgres-password>'
java -jar zzj-service/target/zzj-service.jar --server.port=8081
# 3) zzj-gateway
java -jar zzj-gateway/target/zzj-gateway.jar --server.port=8080
```

也可使用 `scripts/start-local.sh {nacos|service|gateway}`。

健康检查：

- 经网关：`GET http://127.0.0.1:8080/api/health`（转发到 Service）
- 直连排障：`GET http://127.0.0.1:8081/api/health`（仅回环可达，不作为公开入口）
- 网关自身：`GET http://127.0.0.1:8080/actuator/health`

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
| GET | `/api/v1/query-history/{id}/export` | 重新鉴权、Guard 校验并执行后导出 CSV |
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
| GET/POST/PATCH/DELETE | `/api/v1/schedules/**` | 定时计划、Cron 预览、启停、手动触发与运行历史 |
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
- 定时触发前重新校验工作流、连接、工作空间和 Agent 版本权限；调度器不自动批准或直接执行待确认 SQL。
- CSV 导出不信任历史结果，按当前用户权限重新执行只读查询，并限制行数、单元格和文件大小以及转义公式前缀。
- 统一状态图终态（COMPLETED/FAILED/CANCELED）不接受迟到回调覆写；审批以 `sqlVersion` + 原子领取保证重复批准/重放/双标签页不重复执行 SQL。
- Spring AI 只作底层传输层，不替换或绕过统一模型入口；P9 的路由、授权、配额、审计、熔断与故障转移在任意传输层下都保留。

## P12 基础设施集成（S1–S5）

- **MyBatis-Plus（S1）**：`users` 管理实体走 `BaseMapper` + Wrapper（`@TableId(type=AUTO)` 回填自增主键）；业务 SQL 仍只走 SQL Guard + 只读执行 + 审批，不进入元数据 CRUD。
- **Sa-Token + Redis（S2）**：Sa-Token 是唯一身份来源（`SaTokenIdentityService` + 每请求 `SaTokenAuthFilter`，`STATELESS`），HttpOnly `satoken` Cookie + 保留 Cookie CSRF；Redis 故障 fail-closed（按未登录，不发放身份）。已删除 Spring Session 身份承载，不存在双身份体系。
- **pgvector（S3）**：独立 PostgreSQL 数据源（`PgVectorConfig`/`PgVectorStore`）存 GLOSSARY / QUESTION_KNOWLEDGE / MEMORY 三类向量投影；余弦相似度 Top-K，服务端 `userId + connectionId + vectorType`（工作空间记忆按 `workspace_id`）过滤；MySQL 授权候选集做最终复核；删除/停用同步软失效；PG/Embedding 故障 fail-soft 不阻塞主链路。
- **Redis/Redisson 四场景（S4）**：Agent 计划短期上下文（`AgentPlanStore`，TTL + RLock）、HITL 待审批会话（`ApprovalSessionStore`，原子领取）、模型会话亲和（`ModelSessionAffinity`，短 TTL 键）、定时任务互斥（`ScheduleRunLock`，按任务 ID RLock，未获取即跳过）。
- **Caffeine + 连接池（S5）**：连接 Schema 有界缓存（`ConnectionSchemaCache`，TTL 5min/500 条/变更显式失效）；目标库 Hikari 池按连接复用（`TargetConnectionFactory`，空闲过期即关池、`SET SESSION TRANSACTION READ ONLY`、淘汰不泄漏）。
- MySQL 侧零新增表；PG 侧唯一增量 DDL：`zzj-service/src/main/resources/db/13-p12-pgvector.sql`（手工执行，不引入 Flyway）。

## 本地模型测试桩

```bash
node tools/llm-stub.mjs 9099
# 另开终端
$env:LLM_BASE_URL='http://127.0.0.1:9099/v1'; $env:LLM_API_KEY='stub-key'; mvn spring-boot:run
```

测试桩按服务端注入的 Schema 返回 `shop_orders` 或 `blog_posts` 查询；不是真实模型。

## 测试

- `mvn verify`：Reactor 共 384 个自动化用例（`zzj-service` 350 个、`zzj-gateway` 34 个；0 failures/0 errors/12 skipped，2026-10-22 实跑）；跳过项均为缺少对应外部测试环境时主动跳过的集成测试。覆盖 Controller 切片、Service 单元测试，网关匿名策略/入口认证/身份头清洗/会话兼容（真实 Redis），以及 P6–P12（分析器/报告/工具/工作流/Studio/Skill/Workspace/RAG/Memory/模型治理/定时调度/CSV 导出/统一状态图/角色门禁/分层上下文/Spring AI 传输层/路由治理/MyBatis-Plus/Sa-Token 会话/pgvector 检索/Redisson 四场景/Caffeine 缓存与连接池）测试。
- 真实环境用例（MySQL/PostgreSQL/Redis）在缺少对应服务时自动跳过并在报告输出原因，不冒充通过。
