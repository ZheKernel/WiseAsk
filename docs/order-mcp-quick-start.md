# Order MCP OAuth 快速启动

## 1. 准备数据

本地默认使用 `localhost:5432/ragent`，账号和密码均为 `postgres`。

```powershell
psql -h localhost -p 5432 -U postgres -W -d ragent `
  -v ON_ERROR_STOP=1 -1 `
  -f .\resources\database\order_service_schema.sql

psql -h localhost -p 5432 -U postgres -W -d ragent `
  -v ON_ERROR_STOP=1 -1 `
  -f .\resources\database\order_service_sample_data.sql

psql -h localhost -p 5432 -U postgres -W -d ragent `
  -v ON_ERROR_STOP=1 -1 `
  -f .\resources\database\order_mcp_intent_data.sql
```

Redis 必须与 Ragent 使用同一实例和 Sa-Token 配置，因为 Auth Server 要验证
Ragent 登录会话，并用 Redis `SET NX + TTL` 防止 `private_key_jwt` Client
Assertion 重放。当前本地默认 Redis 为 `127.0.0.1:6379`，密码 `123456`。

## 2. 启动 Auth Server

第一个 PowerShell：

```powershell
cd E:\Java_code\ragent
.\mvnw.cmd -pl auth-server -am spring-boot:run
```

默认地址：

```text
Issuer: http://localhost:9200
Token Endpoint: http://localhost:9200/oauth2/token
JWKS: http://localhost:9200/oauth2/jwks
```

本地未配置 KeyStore 时会生成临时 RSA 签名密钥。Auth Server 重启后，旧 Access
Token 会立即失效，这是开发环境的预期行为。

## 3. 启动 Order MCP

第二个 PowerShell：

```powershell
cd E:\Java_code\ragent
$env:ORDER_DB_URL="jdbc:postgresql://localhost:5432/ragent"
$env:ORDER_DB_USERNAME="postgres"
$env:ORDER_DB_PASSWORD="postgres"

.\mvnw.cmd -pl mcp-order-server -am spring-boot:run
```

Order MCP 监听：

```text
http://localhost:9100/mcp
```

它只接受 Auth Server 签发、`aud=order-mcp`、算法为 RS256 的 Bearer Token。

## 4. 启动 Ragent

第三个 PowerShell：

```powershell
cd E:\Java_code\ragent
$env:ORDER_MCP_ENABLED="true"
$env:AUTH_SERVER_CONNECT_TIMEOUT_MILLIS="3000"
$env:AUTH_SERVER_READ_TIMEOUT_MILLIS="5000"

.\mvnw.cmd -pl bootstrap -am spring-boot:run
```

Ragent 在 Web Server 就绪后执行 MCP 工具发现。此时会：

1. 使用自己的 RSA 私钥生成短期 `private_key_jwt`。
2. 向 Auth Server 申请仅含 `mcp:discover` 的服务令牌。
3. 使用服务令牌完成 MCP `initialize` 和 `tools/list`。
4. 注册 `order_list_mine`、`order_detail` 和 `order_admin_search`。

真实用户调用订单工具时，Ragent 会把当前 Sa-Token 作为 `subject_token` 发送给 Auth
Server。Auth Server 验证 Redis 会话并查询 `t_user` 后，再签发用户委托令牌。

## 5. 检查元数据

```powershell
Invoke-RestMethod "http://localhost:9200/.well-known/oauth-authorization-server"
Invoke-RestMethod "http://localhost:9200/oauth2/jwks"
Invoke-RestMethod "http://localhost:9100/.well-known/oauth-protected-resource"
Invoke-RestMethod "http://localhost:9090/api/ragent/.well-known/oauth-client-jwks"
```

Ragent 日志应显示三个订单工具已注册。Auth Server 日志会记录 Client ID、用户 ID、
Audience、Scope 和 Access Token `jti`，但不会记录 Sa-Token、Client Assertion 或
Access Token。

一次真实用户查询应能在三个控制台看到类似日志：

```text
Ragent      [MCP-AUTH][CLIENT_ASSERTION] ... assertionJti=...
Auth Server [MCP-AUTH][CLIENT_VERIFIED] ... assertionJti=...
Auth Server [MCP-AUTH][SUBJECT_VERIFIED] userId=..., role=...
Auth Server [MCP-AUTH][SCOPE_GRANTED] requested=..., granted=...
Auth Server [MCP-AUTH][TOKEN_ISSUED] ... tokenJti=...
Ragent      [MCP-AUTH][TOKEN_RECEIVED] ... tokenJti=...
Ragent      [MCP-AUTH][MCP_CALL] ... tokenJti=...
Order MCP   [MCP-AUTH][TOKEN_VERIFIED] ... tokenJti=...
Order MCP   [MCP-AUTH][AUTHZ_ALLOW] ... tokenJti=...
Order MCP   [MCP-AUTH][TOOL_RESULT] ... tokenJti=...
```

`assertionJti` 关联 Ragent 与 Auth Server 的客户端认证，`tokenJti` 关联 Access Token
签发、携带和资源服务校验。日志中不应出现任何完整 JWT、Sa-Token 或
`Authorization` Header。

## 6. 验证权限

普通用户：

```text
查询我的订单
查询订单 ORD-20260601-001 的详情
查询所有用户订单
```

前两个请求只能返回本人数据；第三个请求不能执行 `order_admin_search`。

管理员：

```text
查询所有用户订单
查询用户 2001523723396309002 的订单
```

管理员令牌必须同时包含 `role=admin` 和 `order:read:any` 才能跨用户查询。

## 7. 工具注册为 0 的排查

按顺序检查：

1. Auth Server 是否监听 `9200`。
2. PostgreSQL 和 Redis 是否可用，且 Auth Server 与 Ragent 使用相同 Sa-Token Redis。
3. Order MCP 是否监听 `9100`。
4. Ragent Client JWKS 是否能通过
   `http://localhost:9090/api/ragent/.well-known/oauth-client-jwks` 访问。
5. Auth Server 的 `RAGENT_CLIENT_JWK_SET_URI` 是否与上面的地址一致。
6. 三个服务的 Issuer 和 Audience 是否分别为
   `http://localhost:9200`、`order-mcp`。
7. 修改配置后是否重新启动 Ragent，让它重新执行工具发现。

当前链路不再使用 `RAGENT_MCP_SHARED_SECRET`。

## 8. 生产密钥

生产环境必须为 Ragent Client Key 和 Auth Server Signing Key 分别创建独立 PKCS12：

```powershell
keytool -genkeypair -alias ragent-client -keyalg RSA -keysize 2048 `
  -storetype PKCS12 -keystore ragent-client.p12 -validity 3650

keytool -genkeypair -alias auth-server-signing -keyalg RSA -keysize 2048 `
  -storetype PKCS12 -keystore auth-server-signing.p12 -validity 3650
```

Ragent：

```powershell
$env:RAGENT_CLIENT_KEYSTORE_PATH="C:\secure\ragent-client.p12"
$env:RAGENT_CLIENT_KEYSTORE_PASSWORD="<password>"
$env:RAGENT_CLIENT_KEY_ALIAS="ragent-client"
```

Auth Server：

```powershell
$env:AUTH_SERVER_KEYSTORE_PATH="C:\secure\auth-server-signing.p12"
$env:AUTH_SERVER_KEYSTORE_PASSWORD="<password>"
$env:AUTH_SERVER_KEY_ALIAS="auth-server-signing"
```

两个私钥不能相同，也不能提交到 Git。Order MCP 只配置 Auth Server JWKS 地址，不持有
任何签名私钥。

## 9. 运行 Chat 评测

```powershell
cd .\evaluation\order-mcp
python run_evaluation.py --config config.example.json --dry-run
python run_evaluation.py --config config.example.json --requests 50 --concurrency 4
```

完整数据导入、1000/10000 请求评测和报告解释见
`docs/order-mcp-evaluation-operation-guide.md`。
