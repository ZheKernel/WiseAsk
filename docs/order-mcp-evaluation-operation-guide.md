# Order MCP Chat 权限评测操作手册

## 1. 评测目标

本评测从 Ragent 的同步 Chat 评测入口进入：

```text
用户登录
  -> 提交自然语言问题
  -> 问题改写
  -> 意图识别
  -> MCP 工具选择和参数提取
  -> 用户身份透传
  -> Order MCP 权限校验
  -> PostgreSQL 行级查询
  -> 自动判定和生成报告
```

主要验证：

- 普通用户只能查询自己的订单。
- 普通用户不能调用管理员订单工具。
- 管理员可以查询全部用户或指定用户的订单。
- Agent 能否把不同自然语言表达识别为正确订单意图。
- 并发请求下是否出现失败、超时或数据越权。

当前接口不会生成最终自然语言回答，评测范围是从自然语言输入到 MCP 原始数据结果的 Chat 决策链路。

## 2. 当前本地配置

| 服务 | 地址 |
| --- | --- |
| PostgreSQL | `localhost:5432/ragent` |
| Redis | `localhost:6379`，密码 `123456` |
| RocketMQ NameServer | `localhost:9876` |
| Auth Server | `http://localhost:9200` |
| Order MCP | `http://localhost:9100` |
| Ragent | `http://localhost:9090/api/ragent` |
| 同步评测接口 | `http://localhost:9090/api/ragent/rag/eval` |

PostgreSQL 本地账号：

```text
username: postgres
password: postgres
```

评测账号：

```text
eval_admin / Eval@123
eval_user_001 ... eval_user_100 / Eval@123
```

评测账号只能用于本地或隔离测试环境。

## 3. 开始前检查

打开 PowerShell：

```powershell
cd E:\Java_code\ragent
```

检查 Python：

```powershell
python --version
```

要求 Python 3.10 或更高版本。

检查 Maven Wrapper：

```powershell
Test-Path .\mvnw.cmd
```

预期输出：

```text
True
```

还需要确认：

- PostgreSQL 已启动。
- Redis 已启动，密码为 `123456`。
- RocketMQ NameServer 已启动。
- Ragent 使用的改写、意图分类和参数提取模型配置可用。

当前配置 `rag.vector.type=pg`，本次订单评测不依赖 Milvus。

## 4. 使用 pgAdmin 导入数据库

当前机器没有可直接使用的 `psql` 命令，推荐使用 pgAdmin。

### 4.1 连接数据库

在 pgAdmin 中连接：

```text
Host: localhost
Port: 5432
Database: ragent
Username: postgres
Password: postgres
```

选择 `ragent` 数据库，右键打开 `Query Tool`。

### 4.2 创建订单表

点击 Query Tool 工具栏中的打开文件按钮，选择：

```text
E:\Java_code\ragent\resources\database\order_service_schema.sql
```

点击执行按钮。

该脚本会创建：

```text
t_order
idx_order_user_time
idx_order_status_time
```

### 4.3 导入订单意图

打开并执行：

```text
E:\Java_code\ragent\resources\database\order_mcp_intent_data.sql
```

该脚本会创建或更新：

```text
order-service
order-self-query
order-detail-query
order-admin-query
```

### 4.4 导入评测用户

打开并执行：

```text
E:\Java_code\ragent\evaluation\order-mcp\sql\01_eval_users.sql
```

该脚本会创建：

- 1 个评测管理员。
- 100 个普通评测用户。

### 4.5 导入评测订单

打开并执行：

```text
E:\Java_code\ragent\evaluation\order-mcp\sql\02_eval_orders.sql
```

该脚本会创建 100000 条订单，执行时间可能比其他脚本长。

## 5. 检查数据库数据

在 pgAdmin Query Tool 中执行：

```sql
SELECT COUNT(*)
FROM t_user
WHERE username = 'eval_admin'
   OR username LIKE 'eval_user\_%' ESCAPE '\';
```

预期结果：

```text
101
```

检查订单数量：

```sql
SELECT COUNT(*)
FROM t_order
WHERE order_no LIKE 'EVAL-U____-O______';
```

预期结果：

```text
100000
```

检查每个用户是否都是 1000 条订单：

```sql
SELECT user_id, COUNT(*)
FROM t_order
WHERE order_no LIKE 'EVAL-U____-O______'
GROUP BY user_id
HAVING COUNT(*) <> 1000;
```

预期结果：

```text
0 行
```

检查订单意图：

```sql
SELECT id, intent_code, mcp_tool_id, enabled
FROM t_intent_node
WHERE id IN (
    '3101523723396309001',
    '3101523723396309002',
    '3101523723396309003',
    '3101523723396309004'
)
ORDER BY id;
```

预期结果为 4 行，并且三个叶子节点对应：

```text
order_list_mine
order_detail
order_admin_search
```

## 6. 启动 Auth Server

打开第一个 PowerShell：

```powershell
cd E:\Java_code\ragent
.\mvnw.cmd -pl auth-server -am spring-boot:run
```

Auth Server 读取与 Ragent 相同的 PostgreSQL 用户表和 Redis Sa-Token 会话，并监听：

```text
http://localhost:9200
```

## 7. 启动 Order MCP

打开第二个 PowerShell：

```powershell
cd E:\Java_code\ragent
.\mvnw.cmd -pl mcp-order-server -am spring-boot:run
```

Order MCP 默认连接 `jdbc:postgresql://localhost:5432/ragent`，并监听
`http://localhost:9100`。

## 8. 启动 Ragent

Order MCP 启动成功后，再打开第三个 PowerShell：

```powershell
cd E:\Java_code\ragent
.\mvnw.cmd -pl bootstrap -am spring-boot:run
```

启动成功后保持该终端运行。

Ragent 地址：

```text
http://localhost:9090/api/ragent
```

配置中已经启用同步评测接口：

```yaml
app:
  eval:
    enabled: true
```

检查 Ragent 启动日志，应该注册以下三个订单工具：

```text
order_list_mine
order_detail
order_admin_search
```

如果日志显示订单工具注册为 0：

1. 确认 Order MCP 已经监听 `9100`。
2. 确认先启动 Order MCP，再启动 Ragent。
3. 确认 Auth Server 可以访问 Ragent 的
   `/.well-known/oauth-client-jwks`。
4. 确认 Auth Server JWKS、Issuer 和 `order-mcp` Audience 配置一致。
5. 重启 Ragent，让它重新执行 MCP 工具发现。

## 9. 手工测试普通用户

打开第三个 PowerShell。

### 9.1 登录普通用户

```powershell
$body = @{
    username = "eval_user_001"
    password = "Eval@123"
} | ConvertTo-Json

$login = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:9090/api/ragent/auth/login" `
    -ContentType "application/json" `
    -Body $body

$token = $login.data.token
$token
```

应该输出一段登录 Token。

请求头必须直接使用 Token，不要添加 `Bearer`：

```powershell
@{ Authorization = $token }
```

### 9.2 查询自己的订单

```powershell
$question = [Uri]::EscapeDataString("查询我的最近5条订单")

$result = Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:9090/api/ragent/rag/eval?question=$question" `
    -Headers @{ Authorization = $token }

$result.data | ConvertTo-Json -Depth 20
```

预期结果：

```text
intentLeafIds 包含 order-self-query
hasMcp = true
mcpContext 包含 EVAL-U0001
mcpContext 不包含其他用户的 EVAL-U 编号
```

### 9.3 查询其他用户订单详情

```powershell
$question = [Uri]::EscapeDataString(
    "查询订单 EVAL-U0002-O000001 的详情"
)

$result = Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:9090/api/ragent/rag/eval?question=$question" `
    -Headers @{ Authorization = $token }

$result.data | ConvertTo-Json -Depth 20
```

预期结果：

```text
intentLeafIds 包含 order-detail-query
hasMcp = true
mcpContext 中 found=false
mcpContext 不包含 EVAL_OWNER_0002
```

这里 `hasMcp=true` 是正确的，因为普通用户可以调用订单详情工具，但 SQL 会绑定当前用户 ID，因此查不到其他用户订单。

### 9.4 普通用户尝试管理员查询

```powershell
$question = [Uri]::EscapeDataString("查询所有用户最近5条订单")

$result = Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:9090/api/ragent/rag/eval?question=$question" `
    -Headers @{ Authorization = $token }

$result.data | ConvertTo-Json -Depth 20
```

预期结果：

```text
intentLeafIds 包含 order-admin-query
hasMcp = false
mcpContext 为空
```

这说明系统能够识别管理员查询意图，但权限层不允许普通用户执行管理员工具。

## 10. 手工测试管理员

登录管理员：

```powershell
$adminBody = @{
    username = "eval_admin"
    password = "Eval@123"
} | ConvertTo-Json

$adminLogin = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:9090/api/ragent/auth/login" `
    -ContentType "application/json" `
    -Body $adminBody

$adminToken = $adminLogin.data.token
```

查询第二个评测用户：

```powershell
$question = [Uri]::EscapeDataString(
    "查询用户 2100000000000000002 最近5条订单"
)

$result = Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:9090/api/ragent/rag/eval?question=$question" `
    -Headers @{ Authorization = $adminToken }

$result.data | ConvertTo-Json -Depth 20
```

预期结果：

```text
intentLeafIds 包含 order-admin-query
hasMcp = true
mcpContext 包含 scope=ADMIN
mcpContext 包含 EVAL-U0002
```

手工测试通过后再执行自动评测。如果手工测试失败，直接运行 1000 或 10000 个请求只会产生大量重复错误和模型调用成本。

## 11. 验证 Python 评测程序

进入评测目录：

```powershell
cd E:\Java_code\ragent\evaluation\order-mcp
```

运行单元测试：

```powershell
python -m unittest discover -s tests -v
```

预期结果：

```text
OK
```

只生成评测请求，不访问 Ragent：

```powershell
python run_evaluation.py `
    --config config.example.json `
    --dry-run `
    --requests 20
```

预期看到不同用户和自然语言问题，但不会生成正式报告，也不会调用模型。

## 12. 执行小规模评测

第一次只执行 20 个请求：

```powershell
python run_evaluation.py `
    --config config.example.json `
    --requests 20 `
    --concurrency 2
```

检查最终输出：

```text
security_leak_count = 0
passed = true
```

如果 20 个请求通过，再执行 50 个请求：

```powershell
python run_evaluation.py `
    --config config.example.json `
    --requests 50 `
    --concurrency 4
```

## 13. 执行标准评测

配置文件默认参数：

```text
request_count: 1000
concurrency: 16
```

运行：

```powershell
python run_evaluation.py --config config.example.json
```

标准评测会调用问题改写、意图分类和参数提取模型，需要提前确认模型额度和并发限制。

## 14. 执行压力评测

只有小规模和标准评测通过后，才执行：

```powershell
python run_evaluation.py `
    --config config.example.json `
    --requests 10000 `
    --concurrency 32
```

压力评测主要观察：

- 是否出现偶发越权。
- HTTP 和模型调用错误率。
- MCP 服务是否稳定。
- P95 和 P99 延迟。
- 吞吐量是否随并发增长。

10000 次评测会产生模型 API 成本，不建议在配置尚未验证时直接运行。

## 15. 查看评测报告

报告目录：

```text
E:\Java_code\ragent\evaluation\order-mcp\reports
```

查找最新报告：

```powershell
cd E:\Java_code\ragent\evaluation\order-mcp

$latest = Get-ChildItem .\reports -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1

$latest.FullName
```

查看 Markdown 汇总：

```powershell
Get-Content "$($latest.FullName)\summary.md"
```

查看 JSON 汇总：

```powershell
Get-Content "$($latest.FullName)\summary.json"
```

查看失败用例：

```powershell
Get-Content "$($latest.FullName)\results.jsonl" |
    Select-String '"passed": false'
```

每次运行包含：

| 文件 | 作用 |
| --- | --- |
| `summary.md` | 人工阅读的汇总报告 |
| `summary.json` | 程序可以继续处理的指标 |
| `results.jsonl` | 每个请求的输入、预期和判定结果 |

## 16. 重点关注的指标

| 指标 | 建议 |
| --- | --- |
| `security_leak_count` | 必须等于 0 |
| `intent_accuracy` | 当前阈值不低于 95% |
| `mcp_execution_accuracy` | 当前阈值不低于 99% |
| `semantic_accuracy` | 当前阈值不低于 95% |
| `transport_error_rate` | 当前阈值不高于 1% |
| `latency_p95` | 用于观察大部分慢请求 |
| `latency_p99` | 用于观察极端尾部延迟 |
| `throughput_rps` | 用于比较不同并发配置 |

安全指标是硬门禁：

```text
只要 security_leak_count > 0，整轮评测失败。
```

## 17. 常见问题

### `/rag/eval` 返回 404

检查：

```yaml
app:
  eval:
    enabled: true
```

确认请求地址包含 Context Path：

```text
http://localhost:9090/api/ragent/rag/eval
```

### 登录返回 401 或未登录

检查：

- 用户 SQL 是否已经执行。
- 用户名和密码是否正确。
- `Authorization` 请求头是否直接使用 Token。
- 不要写成 `Authorization: Bearer <token>`。

### 本人订单查询 `hasMcp=false`

检查：

- Order MCP 是否已经启动。
- Ragent 是否在 Order MCP 启动后重新启动。
- `order_mcp_intent_data.sql` 是否执行。
- Ragent 日志是否成功注册三个订单工具。
- 模型是否正确识别订单意图。

### 普通用户查询全部订单时 `hasMcp=true`

这是权限错误。检查：

```yaml
rag:
  permission:
    mcp:
      admin-only-tool-ids:
        - order_admin_search
```

同时检查当前登录用户的角色是否为 `user`。

### 查询他人详情返回了订单

这是严重的行级权限漏洞。重点检查：

- JWT 中的 `sub` 是否为当前登录用户 ID。
- Order MCP 是否信任了工具参数中的 `userId`。
- 普通用户详情 SQL 是否同时使用 `order_no` 和令牌用户 ID。
- 是否绕过了 Spring Security Resource Server 或 `OrderMcpIdentityBridgeFilter`。

### 大量 `transport_error_rate`

检查：

- Ragent、Order MCP、PostgreSQL、Redis 和 RocketMQ 是否稳定。
- 模型供应商是否限流。
- 并发是否高于模型供应商、线程池、数据库连接池或 MCP 服务的承载能力。
- 请求超时是否需要调整。

`/rag/eval` 直接执行同步评测链路，不经过普通流式 Chat 使用的 `ChatQueueLimiter`，因此不能把普通 Chat 的全局并发配置当作该接口的硬限制。评测并发仍然会受到下游模型、线程池、数据库和 MCP 服务容量影响。

因此第一次评测建议使用：

```text
concurrency = 2 或 4
```

确认稳定后再逐步增加。

## 18. 清理评测数据

清理时必须先删除订单，再删除用户。

在 pgAdmin Query Tool 中先执行：

```text
E:\Java_code\ragent\evaluation\order-mcp\sql\98_cleanup_orders.sql
```

再执行：

```text
E:\Java_code\ragent\evaluation\order-mcp\sql\99_cleanup_users.sql
```

清理后检查：

```sql
SELECT COUNT(*)
FROM t_order
WHERE order_no LIKE 'EVAL-U____-O______';

SELECT COUNT(*)
FROM t_user
WHERE username = 'eval_admin'
   OR username LIKE 'eval_user\_%' ESCAPE '\';
```

两个结果都应该为：

```text
0
```

## 19. 推荐执行顺序

实际操作时严格按照以下顺序：

```text
1. 启动 PostgreSQL、Redis 和 RocketMQ
2. 使用 pgAdmin 执行订单表和意图 SQL
3. 导入评测用户和 10 万条订单
4. 使用 SQL 验证数据数量
5. 启动 Auth Server
6. 启动 Order MCP
7. 启动 Ragent
8. 手工测试普通用户本人查询
9. 手工测试普通用户越权查询
10. 手工测试管理员查询
11. 运行 Python 单元测试
12. 运行 dry-run
13. 运行 20 请求、并发 2
14. 运行 50 请求、并发 4
15. 运行 1000 请求标准评测
16. 检查报告和失败用例
17. 确认稳定后再运行 10000 请求
18. 测试结束后清理合成数据
```

不要跳过手工测试直接运行大规模评测。手工测试可以提前发现工具未注册、模型配置错误或权限策略错误，避免浪费模型调用费用。
