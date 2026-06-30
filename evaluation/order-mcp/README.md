# Order MCP Chat Evaluation

该评测工程从 Ragent 的同步 Chat 评测入口进入，不直接调用 Order MCP：

```text
登录用户
  -> GET /rag/eval
  -> 问题改写
  -> 意图识别
  -> MCP 工具选择与参数抽取
  -> 用户身份令牌透传
  -> Order MCP 权限与 SQL 行级过滤
  -> intentLeafIds + hasMcp + mcpContext
```

`/rag/eval` 不生成最终自然语言回答，但会执行订单场景所需的完整决策和 MCP 链路，并返回适合自动判定的结构化结果。直接调用 MCP 只能作为故障定位手段，不作为本评测的主入口。

## 目录

```text
evaluation/order-mcp/
  datasets/chat_cases.jsonl
  sql/
  order_mcp_eval/
  tests/
  config.example.json
  run_evaluation.py
```

评测器只依赖 Python 标准库，要求 Python 3.10 或更高版本。

## 1. 准备数据库

当前本地默认配置中，Ragent 和 Order MCP 都使用：

```text
localhost:5432/ragent
username: postgres
password: postgres
```

先创建订单表和订单意图：

```powershell
psql -h localhost -p 5432 -U postgres -d ragent `
  -v ON_ERROR_STOP=1 -f resources/database/order_service_schema.sql

psql -h localhost -p 5432 -U postgres -d ragent `
  -v ON_ERROR_STOP=1 -f resources/database/order_mcp_intent_data.sql
```

再导入 1 个管理员、100 个普通用户和 100000 条订单：

```powershell
psql -h localhost -p 5432 -U postgres -d ragent `
  -v ON_ERROR_STOP=1 -f evaluation/order-mcp/sql/01_eval_users.sql

psql -h localhost -p 5432 -U postgres -d ragent `
  -v ON_ERROR_STOP=1 -f evaluation/order-mcp/sql/02_eval_orders.sql
```

如果 Order MCP 使用独立数据库，只需把订单表 schema、`02_eval_orders.sql` 和 `98_cleanup_orders.sql` 执行到该数据库；用户和意图 SQL 仍执行到 Ragent 主库。

合成数据使用固定凭据：

```text
eval_admin / Eval@123
eval_user_001 ... eval_user_100 / Eval@123
```

这些账号只用于本地或隔离测试环境，不能部署到生产环境。

## 2. 启动服务

先启动 Auth Server：

```powershell
.\mvnw.cmd -pl auth-server -am spring-boot:run
```

再启动 Order MCP：

```powershell
.\mvnw.cmd -pl mcp-order-server -am spring-boot:run
```

最后在另一个终端启动 Ragent：

```powershell
.\mvnw.cmd -pl bootstrap -am spring-boot:run
```

Ragent 使用独有 RSA 私钥通过 `private_key_jwt` 向 Auth Server 认证；Order MCP 只使用
Auth Server JWKS 验证 RS256 Access Token，不再配置共享 HMAC Secret。

确认以下配置生效：

```yaml
app:
  eval:
    enabled: true
```

同步评测接口为：

```text
http://localhost:9090/api/ragent/rag/eval
```

## 3. 验证评测工程

运行单元测试：

```powershell
cd evaluation/order-mcp
python -m unittest discover -s tests -v
```

只生成请求、不访问服务：

```powershell
python run_evaluation.py --config config.example.json --dry-run --requests 100
```

## 4. 执行评测

先运行 50 个请求的 smoke：

```powershell
python run_evaluation.py --config config.example.json `
  --requests 50 --concurrency 4
```

标准评测：

```powershell
python run_evaluation.py --config config.example.json
```

10000 请求压力评测：

```powershell
python run_evaluation.py --config config.example.json `
  --requests 10000 --concurrency 32
```

`/rag/eval` 会调用问题改写、意图分类和参数抽取模型。大规模测试前应先确认模型 API 额度、并发限制和费用。

## 5. 判定规则

合成订单号和商品名编码了所属用户：

```text
EVAL-U0001-O000001
EVAL_OWNER_0001_PRODUCT_001
```

评测器检查：

- `intentLeafIds[0]` 是否命中预期订单意图。
- `hasMcp` 是否符合当前角色和场景。
- `mcpContext` 是否包含预期用户、订单、状态、`scope` 和 `found`。
- 普通用户结果中是否出现其他用户的归属标记。

任何普通用户跨用户数据泄露都会使评测进程返回非零退出码。其他准确率和错误率阈值可在 `config.example.json` 中修改。

## 6. 报告

每次正式运行会在 `reports/<时间>/` 下生成：

- `results.jsonl`：每个请求的输入、期望、判定和响应摘要。
- `summary.json`：机器可读的总体指标。
- `summary.md`：面试展示或人工复盘用汇总。

核心指标包括意图准确率、MCP 执行准确率、语义准确率、传输错误率、安全泄露次数、P50/P95/P99 延迟和吞吐量。

## 7. 清理数据

先清订单，再清用户：

```powershell
psql -h localhost -p 5432 -U postgres -d ragent `
  -v ON_ERROR_STOP=1 -f evaluation/order-mcp/sql/98_cleanup_orders.sql

psql -h localhost -p 5432 -U postgres -d ragent `
  -v ON_ERROR_STOP=1 -f evaluation/order-mcp/sql/99_cleanup_users.sql
```
