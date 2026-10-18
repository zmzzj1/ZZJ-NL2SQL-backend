# QueryForge 后端（SQL 平台）

Java 21 + Spring Boot 3.5.6 + Maven Wrapper。提供健康检查、SSE 流式 SQL 生成、只读 SQL 执行。

## 环境要求

- JDK 21（验证使用 Temurin 21.0.12.1）
- MySQL 8（验证使用 8.4.11）
- 可选：OpenAI 兼容模型服务（DeepSeek/OpenAI 等）

## 初始化演示库

```bash
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root < src/main/resources/db/01-demo-schema.sql
```

创建只读账号（密码自行替换）：

```sql
CREATE USER IF NOT EXISTS 'demo_reader'@'127.0.0.1' IDENTIFIED BY '<local-password>';
GRANT SELECT ON sql_demo.* TO 'demo_reader'@'127.0.0.1';
FLUSH PRIVILEGES;
```

演示数据固定为 3 条订单、3 个客户、4 条明细；`COUNT(*)` 返回 3。

## 配置

```bash
cp .env.example config/local.properties
```

`config/local.properties` 已被 `.gitignore` 排除。最少需要：

```properties
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/sql_demo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowMultiQueries=false
spring.datasource.username=demo_reader
spring.datasource.password=<local-password>
```

模型配置可用环境变量覆盖：

| 变量 | 说明 |
| --- | --- |
| `LLM_BASE_URL` | OpenAI 兼容地址，默认 `https://api.deepseek.com/v1` |
| `LLM_API_KEY` | 服务端密钥；为空时生成接口返回 `503 MODEL_NOT_CONFIGURED` |
| `LLM_MODEL` | 模型名，默认 `deepseek-chat` |
| `LLM_REQUEST_TIMEOUT` | 建连/响应头超时，默认 `60s` |
| `SQL_MAX_ROWS` | 最大返回行数，默认 200 |
| `SQL_QUERY_TIMEOUT_SECONDS` | 查询超时，默认 5 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 覆盖数据源配置 |

## 启动与测试

```powershell
$env:JAVA_HOME = 'D:\dev\tools\jdk-21.0.12.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn verify
mvn spring-boot:run
```

默认监听 `127.0.0.1:8080`。健康检查：

```powershell
curl.exe --noproxy "*" http://127.0.0.1:8080/api/health
# {"status":"UP"}
```

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | `{"status":"UP"}` |
| POST | `/api/v1/sql/generate/stream` | SSE：`start` / `delta` / `complete` / `error` |
| POST | `/api/v1/sql/execute` | 单条只读 SELECT，返回 `columns/rows/rowCount/truncated` |

错误体统一为 `{"code","message","requestId"}`，不包含堆栈、连接串或密钥。

## 安全边界

- 只允许单条 `SELECT`；JSqlParser 结构校验 + 关键字/函数/系统库检查。
- 数据库账号只有 `SELECT` 权限；Hikari 连接为只读并执行 `SET SESSION TRANSACTION READ ONLY`。
- 最大 200 行、查询超时默认 5s；`truncated` 如实返回。
- 模型输出不会自动执行，必须由用户显式调用执行接口。
- 前端不能传 JDBC URL、账号或密码。

## 本地模型测试桩

没有真实模型凭据时，可用测试桩验证 HTTP/SSE 协议（不是真实模型）：

```bash
node tools/llm-stub.mjs 9099
# 另开终端
$env:LLM_BASE_URL='http://127.0.0.1:9099/v1'; $env:LLM_API_KEY='stub-key'; .\mvnw.cmd spring-boot:run
```

输入包含“触发上游错误”可模拟上游 500；包含“慢速”可放慢输出，便于验证取消。

## 结构

采用 `controller / service / repository / dto / exception / config` 分层。

## 已知限制

- 真实 LLM 调用尚未用有效凭据验收；无 Key 时按契约失败，不伪造结果。
- 上游连接后长时间不返回数据的读取超时未独立测试。
- 未引入 Redis、JPA、Testcontainers：当前无对应业务需求。
