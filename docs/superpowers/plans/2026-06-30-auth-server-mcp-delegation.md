# Auth Server 与 Order MCP 委托授权实施计划

> 本计划对应
> `docs/superpowers/specs/2026-06-30-auth-server-mcp-delegation-design.md`。
> 每个任务应独立完成、运行聚焦测试、检查差异并形成可回滚检查点。

**目标：** 新增独立 Auth Server，由 Ragent 使用当前 Sa-Token 交换
`order-mcp` 专用短期 JWT；Order MCP 使用 Auth Server JWKS 验签，并继续执行
Scope、角色和 SQL 行级过滤。

**架构：** 浏览器登录继续使用 Sa-Token。Ragent 是 OAuth Confidential Client，
使用独有私钥生成 `private_key_jwt` Client Assertion，再通过 Token Exchange 把当前
Sa-Token 交换为 Audience 绑定的 RS256 Access Token。
Auth Server 验证 Sa-Token Redis 会话并查询 `t_user` 决定用户角色和最终 Scope。
Order MCP 作为 OAuth Resource Server 只持有公钥验证能力。

**技术栈：** Java 17、Spring Boot 3.5.7、Spring Authorization Server、
Spring Security OAuth2 Resource Server、Sa-Token 1.43.0、Redis、PostgreSQL、
MCP Java SDK 1.1.2、JUnit 5、Mockito、MockMvc。

---

## 当前实施状态（2026-06-30）

已完成：

- 新增独立 `auth-server`，支持配置多个 `private_key_jwt` Client 及 JWKS 地址。
- 使用 Redis `SET NX + TTL` 对 Client Assertion `jti` 做集群级防重放。
- 使用 Sa-Token `subject_token` 完成 Token Exchange，并从 `t_user` 重新读取角色。
- Ragent 已完成用户凭证上下文、RS256 Client Assertion、短期 Token 缓存、请求超时和
  401 单次重试。
- Order MCP 已迁移为 RS256 Resource Server，并执行 Scope、角色和 SQL 行级过滤。
- 旧 HS256 共享密钥、Codec 和自定义验签 Filter 已删除，不保留双算法降级入口。
- 聚焦验证通过：Auth Server 9 个、Ragent MCP 11 个、Order MCP 15 个、Python
  评测 15 个测试。

尚需在完整本地基础设施启动后执行：

- PostgreSQL、Redis、Auth Server、Order MCP、Ragent 的真实进程联调。
- 从 `/rag/eval` 发起 Chat 权限评测，并记录 Token Exchange 与缓存命中的 P95/P99。
- `bootstrap` 全量测试需要 Redis 等外部依赖；当前环境未启动 Redis，因此全量上下文
  测试不作为本次代码回归结论。

实施偏差：

- Token Exchange 请求转换直接使用 Spring Authorization Server 1.5.3 内置
  `OAuth2TokenExchangeAuthenticationToken`，没有重复实现自定义 Converter/Token。
- 未实现计划中的 `legacy-hmac` 兼容模式，而是一次性删除旧链路，避免长期保留算法
  降级入口。
- 当前生产密钥入口支持 PKCS12；PEM、KMS/HSM 和双 `kid` 平滑轮换保留为后续增强。

下面任务清单保留原始设计分解，以上状态为当前实现结果。

---

## 目标文件结构

### 新增模块

- `auth-server/pom.xml`
- `auth-server/src/main/java/com/nageoffer/ai/ragent/authserver/AuthServerApplication.java`
- `auth-server/src/main/java/com/nageoffer/ai/ragent/authserver/config/AuthServerProperties.java`
- `auth-server/src/main/java/com/nageoffer/ai/ragent/authserver/config/AuthorizationServerConfig.java`
- `auth-server/src/main/java/com/nageoffer/ai/ragent/authserver/config/JwkSourceConfig.java`
- `auth-server/src/main/java/com/nageoffer/ai/ragent/authserver/config/RegisteredClientConfig.java`
- `auth-server/src/main/java/com/nageoffer/ai/ragent/authserver/token/McpTokenClaimsCustomizer.java`
- `auth-server/src/main/java/com/nageoffer/ai/ragent/authserver/tokenexchange/McpTokenExchangeAuthenticationConverter.java`
- `auth-server/src/main/java/com/nageoffer/ai/ragent/authserver/tokenexchange/McpTokenExchangeAuthenticationToken.java`
- `auth-server/src/main/java/com/nageoffer/ai/ragent/authserver/tokenexchange/McpTokenExchangeAuthenticationProvider.java`
- `auth-server/src/main/java/com/nageoffer/ai/ragent/authserver/session/SaTokenSubjectVerifier.java`
- `auth-server/src/main/java/com/nageoffer/ai/ragent/authserver/user/AuthUser.java`
- `auth-server/src/main/java/com/nageoffer/ai/ragent/authserver/user/AuthUserRepository.java`
- `auth-server/src/main/resources/application.yml`

### Ragent 调整

- 修改 `pom.xml`
  - 将 `auth-server` 加入 Maven Reactor。

- 修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/UserContextInterceptor.java`
  - 捕获当前 Sa-Token，并写入专用凭证上下文。

- 新增 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpSubjectTokenContext.java`
  - 仅在内部保存当前请求的 Sa-Token。
  - 禁止实现会输出原始 Token 的 `toString()`。

- 新增 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpAccessToken.java`
  - 保存 Access Token、过期时间和授予 Scope。

- 新增 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpAccessTokenProvider.java`
  - 定义服务令牌和用户委托令牌获取接口。

- 新增 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/AuthServerMcpAccessTokenProvider.java`
  - 使用 OAuth Token Endpoint 请求令牌。

- 新增 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpAccessTokenCache.java`
  - 按会话摘要、Audience 和 Scope 缓存短期 Token。

- 新增 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/McpAuthorizationProperties.java`
  - 替代共享 Secret 配置。

- 修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpClientProperties.java`
  - 增加服务发现和用户调用 Scope 配置。

- 修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpIdentityTokenService.java`
  - 先改为委托 `McpAccessTokenProvider`，最后重命名或删除。

- 修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpClientToolExecutor.java`
  - 使用用户委托 Token，不再本地签发 HS256 JWT。

- 修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpClientAutoConfiguration.java`
  - 启动工具发现使用 Client Credentials 服务令牌。

- 保留 `McpHttpAuthorizationSupport` 和 `McpRequestIdentityContext`
  - 它们继续负责把 Access Token 写入 HTTP Authorization Header。

### Order MCP 调整

- 修改 `mcp-order-server/pom.xml`
  - 增加 `spring-boot-starter-security`。
  - 增加 `spring-boot-starter-oauth2-resource-server`。

- 修改 `mcp-order-server/src/main/java/com/nageoffer/ai/ragent/ordermcp/config/OrderMcpAuthProperties.java`
  - 删除共享 Secret 和旧 Issuer 配置。
  - 保留 Audience 和受保护资源元数据配置。

- 重写 `mcp-order-server/src/main/java/com/nageoffer/ai/ragent/ordermcp/config/OrderMcpSecurityConfig.java`
  - 配置 Resource Server、Issuer、JWKS、Audience Validator 和 401/403 行为。

- 删除 `mcp-order-server/src/main/java/com/nageoffer/ai/ragent/ordermcp/security/OrderMcpAuthenticationFilter.java`
  - 改由 Spring Security Bearer Token Filter 验签。

- 新增 `mcp-order-server/src/main/java/com/nageoffer/ai/ragent/ordermcp/security/OrderMcpIdentityBridgeFilter.java`
  - 将 Spring Security JWT Authentication 映射到 MCP TransportContext。

- 新增 `mcp-order-server/src/main/java/com/nageoffer/ai/ragent/ordermcp/security/OrderMcpAuthorizationService.java`
  - 集中判断 Scope、角色和工具权限。

- 新增 `mcp-order-server/src/main/java/com/nageoffer/ai/ragent/ordermcp/web/OAuthProtectedResourceController.java`
  - 暴露 `/.well-known/oauth-protected-resource`。

- 修改 `mcp-order-server/src/main/java/com/nageoffer/ai/ragent/ordermcp/order/OrderQueryService.java`
  - 从只检查角色升级为 Scope、角色和行归属联合判断。

### 共享模型调整

- 修改 `mcp-auth/src/main/java/com/nageoffer/ai/ragent/mcpauth/McpCallerIdentity.java`
  - 增加 `scopes` 和 `clientId`。

- 删除 `mcp-auth/src/main/java/com/nageoffer/ai/ragent/mcpauth/McpIdentityTokenCodec.java`
  - 仅在新链路通过全部测试后删除。

- 删除对应 HS256 Codec 测试并替换为 Auth Server 和 Resource Server 测试。

---

## Task 1：建立基线和失败测试

- [ ] 运行当前聚焦测试，记录迁移前基线。

```powershell
.\mvnw.cmd -pl mcp-auth,bootstrap,mcp-order-server -am `
  "-Dtest=McpIdentityTokenCodecTest,McpClientToolExecutorTest,McpHttpAuthorizationSupportTest,OrderMcpAuthenticationFilterTest,OrderQueryServiceTest,OrderMcpExecutorTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine= " "-DforkCount=0" test
```

- [ ] 新增 Auth Server Token Exchange 测试骨架。
- [ ] 新增 Ragent Access Token Provider 测试骨架。
- [ ] 新增 Order MCP RS256 Resource Server 测试骨架。
- [ ] 测试必须先覆盖以下预期：

```text
valid Sa-Token -> user access token
invalid Sa-Token -> invalid_grant
user requesting order:read:any -> not granted
admin requesting order:read:any -> granted
wrong audience -> rejected
wrong JWT signature -> order MCP 401
missing scope -> tool forbidden
```

- [ ] 确认测试在实现前按预期失败，而不是因为测试配置错误失败。

---

## Task 2：创建 Auth Server 模块

- [ ] 在根 `pom.xml` 增加 `auth-server` 模块。
- [ ] 创建 `auth-server/pom.xml`。
- [ ] 使用 Spring Boot BOM 管理版本，不单独覆盖 Spring Security 组件版本。
- [ ] 添加依赖：

```xml
spring-boot-starter-oauth2-authorization-server
spring-boot-starter-security
spring-boot-starter-jdbc
spring-boot-starter-data-redis
postgresql
sa-token-spring-boot3-starter
sa-token-redis-template
```

- [ ] 创建 `AuthServerApplication`。
- [ ] 配置本地端口 `9200` 和 Issuer `http://localhost:9200`。
- [ ] 配置与 Ragent 一致的 PostgreSQL 和 Redis 连接。
- [ ] 不在仓库中写入真实 Client Secret、私钥或生产端点。

聚焦验证：

```powershell
.\mvnw.cmd -pl auth-server -am "-DskipTests" package
```

---

## Task 3：实现 RSA 签名、JWKS 和客户端注册

- [ ] 实现 `JwkSourceConfig`。
- [ ] 本地开发环境生成临时 RSA 2048 位密钥。
- [ ] JWT Header 设置稳定格式的 `kid`。
- [ ] 暴露 Spring Authorization Server JWKS Endpoint。
- [ ] 预留从 PKCS12、PEM 或外部密钥系统加载生产私钥的配置接口。
- [ ] 实现 `RegisteredClientConfig`。
- [x] 支持配置多个 Confidential Client。
- [x] 客户端认证只使用 `private_key_jwt`，Auth Server 保存各 Client 的 JWKS 地址。
- [ ] Client 只允许：

```text
client_credentials
urn:ietf:params:oauth:grant-type:token-exchange
```

- [ ] Client 只允许申请：

```text
mcp:discover
order:read:self
order:read:any
```

- [ ] Access Token 使用自包含 JWT，TTL 默认 300 秒。
- [ ] 禁止 Refresh Token。

测试：

```text
JWKS 包含当前 kid
私钥签发的 JWT 能被 JWKS 公钥验证
未知客户端无法调用 Token Endpoint
未允许的 Grant Type 被拒绝
```

---

## Task 4：实现 Sa-Token Token Exchange

- [ ] 实现 `SaTokenSubjectVerifier`。
- [ ] 使用 `StpUtil.getLoginIdByToken(subjectToken)` 验证当前 Sa-Token。
- [ ] Sa-Token 无效、冻结、过期或已登出时返回 `invalid_grant`。
- [ ] 实现 `AuthUserRepository`，固定查询：

```sql
SELECT id, username, role
FROM t_user
WHERE id = ?
  AND deleted = 0
```

- [ ] 不接收来自 Ragent 的 `username` 或 `role`。
- [ ] 实现 Token Exchange Converter、AuthenticationToken 和 Provider。
- [ ] 校验：

```text
subject_token 非空
subject_token_type 合法
requested_token_type 合法
audience == order-mcp
scope 属于客户端允许集合
```

- [ ] Scope 授予规则：

```text
user  -> order:read:self
admin -> order:read:self + order:read:any
```

- [ ] 实现 `McpTokenClaimsCustomizer`，写入：

```text
sub, username, role, aud, scope, client_id, azp, iat, nbf, exp, jti
```

- [ ] Client Credentials 令牌只写入 `mcp:discover`，不能包含订单读取 Scope。
- [ ] 审计日志记录 `clientId`、`sub`、Audience、Scope、`jti` 和结果。
- [ ] 审计日志不得记录 `subject_token`、Client Secret 或 Access Token。

测试类建议：

- `McpTokenExchangeAuthenticationProviderTest`
- `SaTokenSubjectVerifierTest`
- `McpTokenClaimsCustomizerTest`
- `AuthServerTokenEndpointTest`

---

## Task 5：在 Ragent 捕获用户会话凭证

- [ ] 新增 `McpSubjectTokenContext`。
- [ ] 使用 `TransmittableThreadLocal` 或显式调用上下文保证异步 MCP 任务可访问。
- [ ] `UserContextInterceptor.preHandle` 在 Sa-Token 登录检查通过后写入当前 Token。
- [ ] `afterCompletion` 同时清理 `UserContext` 和 `McpSubjectTokenContext`。
- [ ] 异步调度和异常路径不能遗留上一个请求的 Token。
- [ ] 不把 Sa-Token 字段加入 `LoginUser`。
- [ ] 不允许凭证上下文被 JSON 序列化或打印。

测试：

```text
authenticated request captures token
afterCompletion clears token
next request cannot read previous token
async MCP task receives intended token
```

---

## Task 6：实现 Ragent OAuth Token Client 和缓存

- [ ] 新增 `McpAuthorizationProperties`。
- [ ] 配置 Token URI、Client ID、Client 私钥、连接超时、读取超时和缓存提前量。
- [ ] 使用 Spring `RestClient` 调用 `/oauth2/token`。
- [ ] 服务发现使用：

```text
grant_type=client_credentials
scope=mcp:discover
```

- [ ] 用户调用使用：

```text
grant_type=urn:ietf:params:oauth:grant-type:token-exchange
subject_token=<current sa-token>
subject_token_type=urn:ietf:params:oauth:token-type:access_token
requested_token_type=urn:ietf:params:oauth:token-type:access_token
audience=order-mcp
scope=<requested scopes>
```

- [ ] Ragent 可以根据本地用户角色申请 Scope，但 Auth Server 必须再次裁决最终 Scope。
- [ ] 实现 Token Response 解析：

```text
access_token
token_type
expires_in
scope
issued_token_type
```

- [ ] 实现 `McpAccessTokenCache`。
- [ ] 缓存键使用 Sa-Token 摘要、Audience 和标准化 Scope，不存储或输出原始 Token。
- [ ] 在过期前 30 秒重新交换。
- [ ] 并发缓存未命中时合并同一个 Key 的 Token Exchange，避免请求风暴。
- [ ] 401 后清除缓存并最多重试一次。
- [ ] Auth Server 不可用且无有效缓存时失败关闭。

测试类建议：

- `AuthServerMcpAccessTokenProviderTest`
- `McpAccessTokenCacheTest`
- `McpSubjectTokenContextTest`

---

## Task 7：替换 Ragent 本地 HS256 签发

- [ ] 修改 `McpClientAutoConfiguration`。
- [ ] 启动时从 Auth Server 获取 `mcp:discover` 服务令牌。
- [ ] 保留 `McpHttpAuthorizationSupport.bearerTokenCustomizer()`。
- [ ] 修改 `McpClientToolExecutor`，每次工具调用获取当前用户委托 Token。
- [ ] Token 获取和 MCP 调用日志不得输出 Authorization Header。
- [ ] `McpIdentityTokenService` 暂时变为 Access Token Provider 适配层，减少一次性改动。
- [ ] 所有调用迁移后删除或重命名旧 Service，避免“本地签发”语义残留。
- [ ] 更新 `McpClientToolExecutorTest`：

```text
authenticated tool uses exchanged token
unauthenticated MCP server does not request token
service discovery uses service token
user call does not reuse service token
```

- [ ] 更新 `RetrievalEnginePermissionTest`，继续证明 Ragent 调用前权限判断存在。

---

## Task 8：将 Order MCP 改为 OAuth Resource Server

- [ ] 增加 Spring Security Resource Server 依赖。
- [ ] 配置 `/mcp` 必须认证。
- [ ] 配置 `issuer-uri` 和 `jwk-set-uri`。
- [ ] 增加 Audience Validator，要求包含 `order-mcp`。
- [ ] 保留时间、签发者和签名默认验证。
- [ ] 明确只允许 RS256，拒绝 HS256 和 `alg=none`。
- [ ] 删除自定义 `OrderMcpAuthenticationFilter`。
- [ ] 新增 `OrderMcpIdentityBridgeFilter`：

```text
JwtAuthenticationToken
  -> sub
  -> username
  -> role
  -> scope
  -> client_id/azp
  -> McpCallerIdentity
  -> request attribute
  -> MCP transport context
```

- [ ] 401 返回标准 Bearer Challenge。
- [ ] Scope 不足使用 403 或稳定的 MCP 错误结果。
- [ ] 增加受保护资源元数据 Endpoint。

测试类建议：

- `OrderMcpResourceServerTest`
- `OrderMcpIdentityBridgeFilterTest`
- `OAuthProtectedResourceControllerTest`

测试必须覆盖：

```text
valid RS256 token
missing token
wrong signature
wrong issuer
wrong audience
expired token
HS256 downgrade token
missing scope
```

---

## Task 9：强化 Order MCP 二次授权

- [ ] 扩展 `McpCallerIdentity`，增加不可变 Scope 集合和 Client ID。
- [ ] 新增 `OrderMcpAuthorizationService`。
- [ ] `order_list_mine` 要求 `order:read:self`。
- [ ] `order_detail`：

```text
有 order:read:any 且 role=admin -> 可以按 order_no 查询
否则要求 order:read:self -> 按 order_no + sub 查询
```

- [ ] `order_admin_search` 同时要求：

```text
role=admin
scope contains order:read:any
```

- [ ] `mcp:discover` 服务身份不得执行任何订单工具。
- [ ] 保持 SQL 固定、参数化和只读。
- [ ] 保持查询数量限制 `1..100`。
- [ ] 普通用户查询他人订单仍返回未找到，不能暴露订单存在性。

更新测试：

- `OrderQueryServiceTest`
- `OrderMcpExecutorTest`

新增断言：

```text
admin role without order:read:any is forbidden
order:read:any without admin role is forbidden
system discovery identity cannot query
user detail always binds JWT sub
```

---

## Task 10：配置、启动和迁移文档

- [ ] 修改 `bootstrap/src/main/resources/application.yaml`。
- [ ] 修改 `mcp-order-server/src/main/resources/application.yml`。
- [ ] 新增 `auth-server/src/main/resources/application.yml`。
- [ ] 删除 `RAGENT_MCP_SHARED_SECRET` 依赖。
- [ ] 新增环境变量：

```text
AUTH_SERVER_ISSUER
AUTH_SERVER_TOKEN_URI
AUTH_SERVER_JWK_SET_URI
RAGENT_OAUTH_CLIENT_ID
RAGENT_CLIENT_JWK_SET_URI
RAGENT_CLIENT_KEYSTORE_PATH
RAGENT_CLIENT_KEYSTORE_PASSWORD
RAGENT_CLIENT_KEY_ALIAS
AUTH_SERVER_KEYSTORE_PATH
AUTH_SERVER_KEYSTORE_PASSWORD
AUTH_SERVER_KEY_ALIAS
```

- [ ] 更新 `README.md` 和 `docs/order-mcp-quick-start.md`。
- [ ] 文档启动顺序：

```text
PostgreSQL、Redis
Auth Server: 9200
Order MCP: 9100
Ragent: 9090
```

- [ ] 文档提供 PowerShell 环境变量和启动命令。
- [ ] 文档明确开发临时 RSA Key 与生产密钥管理区别。
- [ ] 文档明确本阶段仍使用 Sa-Token 登录，不宣称完整 OIDC 登录。
- [ ] 单独记录现有明文密码比较风险；密码散列迁移不混入本次 MCP 授权提交。

---

## Task 11：兼容切换和旧代码清理

- [ ] 迁移期间提供互斥模式：

```text
legacy-hmac
auth-server
```

- [ ] Ragent 和 Order MCP 必须使用同一模式。
- [ ] 单个 Order MCP 实例禁止同时接受 HS256 和 RS256。
- [ ] 测试环境切换到 `auth-server` 并通过端到端验证后：

```text
删除 McpIdentityTokenCodec
删除 rag.mcp.identity.secret
删除 order-mcp.auth.secret
删除旧 HS256 测试
移除 Hutool JWT 的仅此用途依赖
```

- [ ] 回滚只能通过两端同时切回旧模式并重启，不能通过运行时双算法兼容。
- [ ] 稳定后删除 `legacy-hmac` 模式，避免长期保留降级入口。

---

## Task 12：端到端验证和评测

- [ ] 启动 PostgreSQL、Redis 和订单数据。
- [ ] 启动 Auth Server。
- [ ] 验证：

```powershell
Invoke-WebRequest -Uri http://localhost:9200/.well-known/oauth-authorization-server
Invoke-WebRequest -Uri http://localhost:9200/oauth2/jwks
```

- [ ] 启动 Order MCP。
- [ ] 验证受保护资源元数据。
- [ ] 启动 Ragent。
- [ ] 确认日志显示注册：

```text
order_list_mine
order_detail
order_admin_search
```

- [ ] 从 Chat 评测入口执行：

```text
普通用户查询本人订单
普通用户查询他人订单详情
普通用户尝试查询全部订单
管理员查询指定用户订单
管理员查询全部订单
```

- [ ] 运行小规模 smoke 评测。
- [ ] 再运行标准订单 Chat 权限评测。
- [ ] 验收：

```text
security_leak_count == 0
transport_error_rate 在阈值内
intent_accuracy 未发生明显回退
记录首次 Token Exchange 与缓存命中的 P95/P99
```

---

## Task 13：最终测试、评审和提交

- [ ] 运行 Auth Server 聚焦测试。

```powershell
.\mvnw.cmd -pl auth-server -am `
  "-Dtest=SaTokenSubjectVerifierTest,McpTokenExchangeAuthenticationProviderTest,McpTokenClaimsCustomizerTest,AuthServerTokenEndpointTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine= " "-DforkCount=0" test
```

- [ ] 运行 Ragent MCP 聚焦测试。

```powershell
.\mvnw.cmd -pl bootstrap -am `
  "-Dtest=McpSubjectTokenContextTest,McpAccessTokenCacheTest,AuthServerMcpAccessTokenProviderTest,McpClientToolExecutorTest,McpHttpAuthorizationSupportTest,RetrievalEnginePermissionTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine= " "-DforkCount=0" test
```

- [ ] 运行 Order MCP 聚焦测试。

```powershell
.\mvnw.cmd -pl mcp-order-server -am `
  "-Dtest=OrderMcpResourceServerTest,OrderMcpIdentityBridgeFilterTest,OrderQueryServiceTest,OrderMcpExecutorTest,OAuthProtectedResourceControllerTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine= " "-DforkCount=0" test
```

- [ ] 运行所有受影响模块测试。

```powershell
.\mvnw.cmd -pl auth-server,bootstrap,mcp-order-server -am `
  "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine= " "-DforkCount=0" test
```

- [ ] 检查差异。

```powershell
git diff --stat
git diff
git status --short
```

- [ ] 检查配置和日志中没有真实 Secret、私钥、Sa-Token 或 Access Token。
- [ ] 由高级模型或人工重点评审：

```text
Token Exchange subject_token 验证
Scope 授予和降权
Audience 验证
JWT 算法限制
异步凭证上下文清理
Token 缓存隔离
Order MCP SQL 行级过滤
HS256 降级入口是否彻底移除
```

- [ ] 按任务形成独立提交，不把整个高风险改造压成一个无法回滚的提交。

建议提交顺序：

```text
Add standalone MCP authorization server
Exchange Sa-Token sessions for MCP access tokens
Migrate order MCP to OAuth resource server
Enforce order scopes and row-level authorization
Remove legacy shared-secret MCP authentication
Document OAuth MCP deployment and verification
```

---

## Task 10：收敛 MCP 模块结构

- [x] 删除无外部引用的示例 `mcp-server`。
- [x] 删除只包含两个类型的 `mcp-auth` Maven 模块。
- [x] 将 `McpCallerIdentity` 迁入 `mcp-order-server` 安全边界。
- [x] 在 `auth-server`、`bootstrap`、`mcp-order-server` 内分别维护 Scope
  授予、请求和校验常量。
- [x] 移除三个业务模块对 `mcp-auth` 的 Maven 依赖。
- [x] 运行 Auth Server、Ragent MCP Client 和 Order MCP 聚焦测试。
- [x] 检查 Reactor、README、项目地图和启动文档不再引用已删除模块。

---

## Task 11：完善鉴权链路控制台日志

- [x] 定义 `[MCP-AUTH]` 阶段标签、安全字段和敏感字段禁记规则。
- [x] Ragent 记录 Client Assertion、Token Cache、Token Exchange 和 MCP 调用阶段。
- [x] Auth Server 记录客户端断言验证、用户会话验证、Scope 降权和 Token 签发阶段。
- [x] Order MCP 记录 Access Token 验证、Scope/角色授权和工具结果阶段。
- [x] 使用 `assertionJti` 与 `tokenJti` 串联跨进程日志。
- [x] 更新快速启动文档中的预期日志示例。
- [x] 运行三端聚焦测试并检查日志中不包含任何令牌正文。
