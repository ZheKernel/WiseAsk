# Auth Server 与 Order MCP 委托授权设计

## 背景

当前订单 MCP 权限链路已经具备两层控制：

1. Ragent 在执行工具前通过 `RagResourcePermissionService` 判断当前用户能否调用工具。
2. Order MCP 验证 Ragent 本地签发的 HS256 JWT，再按角色和 `user_id` 执行服务端过滤。

当前内部 JWT 使用共享 HMAC Secret：

```text
Ragent 持有 Secret并签发
Order MCP 持有相同 Secret并验签
```

这能满足固定的内部服务调用，但签发权和验签权没有分离。Order MCP 一旦拿到共享
Secret，就同时具备伪造任意用户令牌的能力；新增其他 MCP 服务时也需要继续分发共享
Secret，密钥泄露影响范围会不断扩大。

本次设计引入独立 `auth-server`，把订单访问令牌的签发权从 Ragent 迁移到统一认证服务，
并使用非对称 JWT：

```text
Auth Server 持有 RSA 私钥并签发
Order MCP 只获取 Auth Server 公钥并验签
Ragent 只申请和转发令牌
```

## 目标

1. 新增独立 `auth-server` Spring Boot 模块。
2. 保留现有 Sa-Token 浏览器登录，不在本阶段重写前端登录流程。
3. Ragent 使用当前用户的 Sa-Token 作为 `subject_token`，向 Auth Server 交换
   `order-mcp` 专用短期 Access Token。
4. Auth Server 必须重新验证 Sa-Token 会话并查询 `t_user`，不能信任请求中自报的
   `userId`、`username` 或 `role`。
5. Auth Server 使用 RS256 私钥签发 JWT，并通过 JWKS 暴露公钥。
6. Order MCP 使用 Spring Security Resource Server 验证 JWT 的签名、签发者、受众、
   有效期和 Scope。
7. Order MCP 在验签后继续执行工具权限判断和 SQL 行级过滤。
8. 移除 Ragent 与 Order MCP 之间的共享 HMAC Secret。
9. 工具 Schema 只保留业务参数，用户身份不能进入模型可生成的工具参数。

## 非目标

- 本阶段不把浏览器登录迁移为 Authorization Code + PKCE 或 OIDC。
- 本阶段不实现第三方客户端动态注册、用户授权同意页和 Refresh Token。
- 本阶段不实现订单新增、修改、取消或支付操作。
- 本阶段不让 Auth Server 代理订单查询。
- 本阶段不让 Order MCP 连接 Ragent 用户库重新查询角色；它信任 Auth Server 的已签名
  身份结论，但必须独立完成资源授权。
- 本阶段不同时接受 HS256 和 RS256 两种令牌作为长期运行模式。
- 本阶段不处理现有用户密码散列迁移。当前登录代码仍采用字符串比较，生产化前必须另行
  迁移到 BCrypt 或 Argon2，并提供兼容已有用户数据的升级方案。

## 方案定位

本阶段采用 OAuth 2.0 Token Exchange 的请求形态，将现有 Sa-Token 会话凭证作为
`subject_token`，交换面向 Order MCP 的短期 Access Token。

这是从项目内部 JWT 向企业 IAM 演进的过渡方案：

- Token Endpoint、客户端认证、Scope、Audience、Bearer Token、JWT 和 JWKS 使用
  OAuth 资源服务器模型。
- 用户登录凭证仍由 Sa-Token 签发和存储，Auth Server 通过共享 Redis 会话状态验证。
- 因此不能描述为“已经完成统一 OAuth 2.1/OIDC 登录体系”。
- 后续可以将 Sa-Token `subject_token` 替换为 Auth Server 自己签发的用户 Access Token，
  此时 Ragent 和 Auth Server 不再共享 Sa-Token 会话存储。

## 信任边界

### 密钥和凭证归属

| 组件 | 持有内容 | 能力 |
| --- | --- | --- |
| 浏览器 | Sa-Token 登录凭证 | 调用 Ragent，不直接调用 Order MCP |
| Ragent | OAuth Client ID 和独有 RSA 私钥 | 使用 `private_key_jwt` 向 Auth Server 证明自己是受信任客户端 |
| Ragent | 当前请求的 Sa-Token | 作为 Token Exchange 的 `subject_token` |
| Auth Server | JWT RSA 私钥 | 唯一有权签发 Order MCP Access Token |
| Auth Server | 多个 Client 的公钥/JWKS 地址 | 校验客户端签名、允许的 Grant 和 Scope |
| Auth Server | Redis 读写能力 | 验证 Sa-Token 会话，并原子记录 Client Assertion `jti` 防重放 |
| Auth Server | `t_user` 只读访问 | 查询用户状态、用户名和角色 |
| Order MCP | Auth Server JWKS 公钥 | 只能验证 Access Token，不能签发 |
| Order MCP | PostgreSQL 订单只读账号 | 按已验证用户执行行级查询 |

Auth Server 的 RSA 私钥不得进入 Ragent 或 Order MCP。Order MCP 获取到的 JWKS 是
公开验证材料，泄露公钥不会获得令牌签发能力。

### 服务身份和用户身份

需要区分两类令牌：

1. 服务发现令牌
   - Ragent 启动时通过 Client Credentials 获取。
   - 用于 MCP `initialize` 和 `tools/list`。
   - `sub` 为 Ragent 客户端身份。
   - Scope 为 `mcp:discover`。
   - 不能执行订单查询工具。

2. 用户委托令牌
   - Ragent 在真实用户触发订单工具时通过 Token Exchange 获取。
   - `sub` 为 Sa-Token 对应的真实用户 ID。
   - `aud` 为 `order-mcp`。
   - Scope 根据 Auth Server 查询到的角色授予。
   - 用于 MCP `tools/call`。

## 完整链路

### 1. 用户登录

浏览器继续调用：

```http
POST /api/ragent/auth/login
Content-Type: application/json

{
  "username": "alice",
  "password": "..."
}
```

Ragent 继续使用 Sa-Token 创建登录会话并返回不透明 Token。该流程不是本次改造对象。

### 2. 用户发起 Chat

```http
POST /api/ragent/rag/eval?question=查询我的订单
Authorization: Bearer <sa-token>
```

`UserContextInterceptor` 完成两件事：

1. 根据 Sa-Token 加载 `LoginUser`。
2. 将本次请求的原始 Sa-Token 保存到专用的凭证上下文。

凭证上下文必须与普通 `LoginUser` 分离，避免 Token 被 `toString()`、JSON 序列化或普通
业务日志意外输出。

### 3. Ragent 请求用户委托令牌

当模型决策链路命中订单 MCP 工具后，Ragent 向 Auth Server 发起 Token Exchange：

```http
POST /oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange
&client_id=ragent
&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
&client_assertion=<ragent-private-key-signed-jwt>
&subject_token=<sa-token>
&subject_token_type=urn:ietf:params:oauth:token-type:access_token
&requested_token_type=urn:ietf:params:oauth:token-type:access_token
&audience=order-mcp
&scope=order:read:self order:read:any
```

请求中的 Scope 是申请值，不是最终权限结论。普通用户即使请求
`order:read:any`，Auth Server 也必须拒绝或从最终 Scope 中删除。

### 4. Auth Server 验证和授权

Auth Server 按顺序执行：

1. 根据 `client_id` 找到客户端 JWKS，验证 `private_key_jwt` 的 RS256 签名、`iss`、
   `sub`、Token Endpoint `aud`、`exp` 和 `jti`，并通过 Redis `SET NX + TTL`
   原子拒绝重复 `jti`。
2. 验证 `grant_type`、`subject_token_type` 和 `audience`。
3. 使用 Sa-Token 的 `getLoginIdByToken(subject_token)` 读取共享 Redis 会话。
4. 根据登录 ID 查询 `t_user`，要求用户存在且 `deleted = 0`。
5. 从数据库读取 `username` 和 `role`，忽略 Ragent 请求中任何身份字段。
6. 根据角色和目标 Audience 计算最终 Scope。
7. 使用 Auth Server RSA 私钥签发短期 JWT。
8. 记录客户端、用户、Audience、Scope、`jti` 和结果，不记录原始 Token。

### 5. Access Token

用户令牌至少包含：

```json
{
  "iss": "http://localhost:9200",
  "sub": "user-1001",
  "aud": ["order-mcp"],
  "username": "alice",
  "role": "user",
  "scope": ["order:read:self"],
  "client_id": "ragent",
  "azp": "ragent",
  "iat": 1782810900,
  "nbf": 1782810900,
  "exp": 1782811200,
  "jti": "..."
}
```

要求：

- 默认有效期 300 秒。
- 不签发 Refresh Token。
- `aud` 必须明确绑定 `order-mcp`。
- 使用 RS256，JWT Header 包含 `kid`。
- Payload 可读但不可篡改，不能放入密码、Sa-Token 或其他敏感信息。

### 6. Ragent 调用 Order MCP

Ragent 保持现有 MCP 工具参数：

```http
POST /mcp
Authorization: Bearer <auth-server-access-token>
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "order_detail",
    "arguments": {
      "orderNo": "ORDER-001"
    }
  },
  "id": 1
}
```

`arguments` 中不能加入 `callerUserId`、`callerRole` 或原始 Sa-Token。

### 7. Order MCP 二次判断

Order MCP 依次执行：

1. Spring Security 从 `Authorization` 读取 Bearer Token。
2. 根据 JWT Header 的 `kid` 从 Auth Server JWKS 选择公钥。
3. 验证 RS256 签名。
4. 验证 `iss`、`aud`、`exp`、`nbf`。
5. 把 `sub`、`username`、`role`、`scope` 和 `client_id` 映射到 MCP 身份上下文。
6. 工具层检查所需 Scope。
7. 业务层检查角色和订单归属。
8. Repository 使用固定参数化 SQL 查询。

这里的“二次判断”不是重新验证用户密码，而是资源服务独立完成：

```text
令牌真实性校验
  + Scope/角色授权
  + SQL 数据归属过滤
```

## Scope 设计

| Scope | 含义 | 可授予对象 |
| --- | --- | --- |
| `mcp:discover` | 初始化连接和发现工具 | Ragent 服务客户端 |
| `order:read:self` | 查询当前用户自己的订单 | user、admin |
| `order:read:any` | 查询任意用户订单 | admin |

工具授权规则：

| 工具 | 需要的 Scope | 数据规则 |
| --- | --- | --- |
| `order_list_mine` | `order:read:self` | 始终按 `sub` 查询 |
| `order_detail` | `order:read:self` 或 `order:read:any` | 没有 `order:read:any` 时必须按 `order_no + sub` 查询 |
| `order_admin_search` | `order:read:any` 且 `role=admin` | 可以按用户筛选或查询全部 |

管理员权限采用 Scope 和角色双条件，不只依赖 `role=admin`。

## Token 缓存

Ragent 不需要每次 MCP HTTP 调用都重新访问 Auth Server。用户委托 Token 可以在短有效期
内复用。

缓存键使用：

```text
SHA-256(subject_token) + audience + normalized_scopes
```

缓存值包含：

```text
accessToken
expiresAt
grantedScopes
```

规则：

- 原始 Sa-Token 不作为日志或可观察缓存键。
- 在 `expiresAt - 30 秒` 时视为失效并重新交换。
- 401 时清除对应缓存并最多重试一次 Token Exchange。
- Auth Server 不可用时失败关闭，禁止回退为模型参数身份或本地伪造 JWT。
- 用户登出后，已经签发的 Order MCP Token 最多继续有效到其 `exp`，因此 TTL 保持较短。

## JWKS 和密钥轮换

Auth Server 暴露 JWKS，例如：

```text
GET /oauth2/jwks
```

本地开发：

- Auth Server 启动时可以生成临时 RSA 2048 位密钥。
- 重启后旧 Token 立即不可验证，可接受用于开发环境。

生产环境：

- 从 KMS、HSM、Vault、PKCS12 或受保护 PEM 加载私钥。
- 私钥不得提交到 Git。
- 每把公钥使用不同 `kid`。
- 轮换时先发布新公钥，再切换签名私钥；旧公钥至少保留到旧 Token 全部过期。

Order MCP 配置 `issuer-uri` 和 `jwk-set-uri`。同时配置 `jwk-set-uri`，可以避免 Order MCP
启动阶段强依赖 Auth Server 在线；第一次遇到未知 `kid` 时再刷新 JWKS。

## MCP 受保护资源元数据

Order MCP 增加受保护资源元数据：

```text
GET /.well-known/oauth-protected-resource
```

至少返回：

```json
{
  "resource": "http://localhost:9100",
  "authorization_servers": ["http://localhost:9200"],
  "scopes_supported": [
    "mcp:discover",
    "order:read:self",
    "order:read:any"
  ],
  "bearer_methods_supported": ["header"]
}
```

固定的内部 Ragent 客户端不会依赖动态发现才能工作，但提供元数据可以让 Order MCP 更
接近标准受保护 MCP 资源服务。

## 模块调整

### 新增 `auth-server`

职责：

- Spring Authorization Server Token Endpoint。
- Ragent OAuth Client 注册。
- Client Credentials 服务令牌。
- Sa-Token `subject_token` 验证。
- Token Exchange 用户委托令牌。
- `t_user` 身份和角色读取。
- RS256 JWT 签发和 JWKS。
- Audience、Scope 和审计策略。

### 调整 `bootstrap`

职责变化：

- 不再本地签发 Order MCP Access Token。
- 从当前请求安全地捕获 Sa-Token。
- 调用 Auth Server Token Endpoint。
- 缓存短期 Access Token。
- 继续通过 MCP TransportContext 设置 `Authorization: Bearer`。
- 启动工具发现使用服务令牌，真实工具调用使用用户委托令牌。

### 调整 `mcp-order-server`

职责变化：

- 从自定义 HS256 Filter 迁移到 Spring Security Resource Server。
- 通过 Auth Server JWKS 验证 RS256 JWT。
- 将已验证 Claims 映射为 MCP 调用者上下文。
- 增加 Scope 判断，同时保留角色和 SQL 行级过滤。
- 提供 OAuth Protected Resource Metadata。

### 调整 `mcp-auth`

保留：

- MCP 调用者身份模型。
- 公共 Scope 常量或授权上下文模型。

移除：

- `McpIdentityTokenCodec` 的 HS256 签发和验证职责。
- Ragent 与 Order MCP 共享 Secret 的配置依赖。

## 配置草案

### Auth Server

```yaml
server:
  address: localhost
  port: 9200

auth-server:
  issuer: ${AUTH_SERVER_ISSUER:http://localhost:9200}
  access-token-ttl-seconds: 300
  key-store-path: ${AUTH_SERVER_KEYSTORE_PATH:}
  key-store-password: ${AUTH_SERVER_KEYSTORE_PASSWORD:}
  key-alias: ${AUTH_SERVER_KEY_ALIAS:}
  clients:
    - client-id: ${RAGENT_OAUTH_CLIENT_ID:ragent}
      jwk-set-uri: ${RAGENT_CLIENT_JWK_SET_URI:http://localhost:9090/api/ragent/.well-known/oauth-client-jwks}
      scopes:
        - mcp:discover
        - order:read:self
        - order:read:any
```

Auth Server 还需要与 Ragent 相同的 Sa-Token Redis 配置和 `t_user` 数据源读取配置。

### Ragent

```yaml
rag:
  mcp:
    authorization:
      token-uri: ${AUTH_SERVER_TOKEN_URI:http://localhost:9200/oauth2/token}
      client-id: ${RAGENT_OAUTH_CLIENT_ID:ragent}
      client-assertion-ttl-seconds: 60
      key-store-path: ${RAGENT_CLIENT_KEYSTORE_PATH:}
      key-store-password: ${RAGENT_CLIENT_KEYSTORE_PASSWORD:}
      key-alias: ${RAGENT_CLIENT_KEY_ALIAS:}
      cache-skew-seconds: 30
```

### Order MCP

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${AUTH_SERVER_ISSUER:http://localhost:9200}
          jwk-set-uri: ${AUTH_SERVER_JWK_SET_URI:http://localhost:9200/oauth2/jwks}

order-mcp:
  auth:
    audience: order-mcp
    resource-metadata-url: http://localhost:9100/.well-known/oauth-protected-resource
```

## 失败行为

| 场景 | 结果 |
| --- | --- |
| Ragent Client 凭证错误 | Auth Server 返回 `invalid_client` |
| Sa-Token 缺失、无效或已登出 | Auth Server 返回 `invalid_grant` |
| 用户不存在或已删除 | Auth Server 返回 `invalid_grant` |
| 普通用户申请 `order:read:any` | 不授予该 Scope或返回 `invalid_scope` |
| Audience 不是 `order-mcp` | Auth Server 拒绝签发 |
| JWT 缺失、过期、签名错误 | Order MCP 返回 401 |
| JWT 有效但 Scope 不足 | MCP 工具返回无权执行，必要时 HTTP 层返回 403 |
| 普通用户查询他人订单号 | 返回未找到，不泄露订单是否存在 |
| Auth Server 暂时不可用 | Ragent 使用未过期缓存；无缓存时失败关闭 |

错误响应和日志不得包含 Client Secret、Sa-Token、Access Token 或完整 Authorization Header。

## 迁移策略

### 阶段 1：并行部署

- 新增 Auth Server，但 Ragent 和 Order MCP 仍运行旧 HS256 模式。
- 完成 Token Exchange、JWKS 和单元测试。

### 阶段 2：测试环境切换

- Ragent 切换到 `auth-server` 模式。
- Order MCP 切换到 `oauth-jwt` 模式。
- 两端必须同时切换，避免一端签发 HS256、另一端只接受 RS256。
- 同一实例不能同时接受两种算法，防止降级攻击。

### 阶段 3：验证

- 验证启动工具注册。
- 验证普通用户本人订单。
- 验证普通用户他人订单为未找到。
- 验证普通用户无法调用管理员工具。
- 验证管理员查询全部订单。
- 运行现有 Chat 链路权限评测，要求 `security_leak_count = 0`。

### 阶段 4：清理

- 删除共享 Secret 配置。
- 删除本地 HS256 签发和验签代码。
- 更新启动文档、部署顺序和环境变量。

## 风险和约束

### Auth Server 可用性

Auth Server 不可用会阻止新 Token 签发。短期 Token 缓存可以缓解瞬时故障，但不能无限
延长授权。部署顺序应为：

```text
PostgreSQL、Redis
  -> Auth Server
  -> Order MCP
  -> Ragent
```

### Sa-Token 共享状态

过渡阶段 Auth Server 需要访问与 Ragent 相同的 Sa-Token Redis。配置不一致会导致所有
Token Exchange 返回 `invalid_grant`。必须保持 Token Name、登录类型和 Redis Key 空间
一致。

### 权限变更延迟

用户角色降级后，已经签发的短期 JWT 会在过期前继续有效。默认 300 秒 TTL 是安全性与
Auth Server 压力之间的折中。高风险写操作若以后引入，需要更短 TTL、令牌撤销或实时
策略检查。

### Ragent 是受信任客户端

Ragent 能读取用户的 Sa-Token 并发起 Token Exchange，因此仍属于高信任服务。当前使用
`private_key_jwt`：每个业务客户端持有独立私钥，Auth Server 可以登记多个客户端 JWKS；
单个客户端私钥泄露不会获得 Auth Server 的签名私钥，也不能冒充其他 Client。后续仍可
叠加 mTLS，并把浏览器登录迁移到 Auth Server，进一步减少共享状态。

### 当前密码校验

`AuthServiceImpl.passwordMatches` 当前使用字符串直接比较。这与 MCP Access Token 签发是
两个独立问题，但属于生产部署前必须处理的安全风险。本计划不能因为引入 Auth Server
而错误宣称用户登录链路已经达到生产安全要求。

## 测试矩阵

### Auth Server

- 正确 Client 和有效 Sa-Token可以交换用户 Token。
- 错误 Client Secret 返回 `invalid_client`。
- 无效、过期和已登出的 Sa-Token 返回 `invalid_grant`。
- 不存在或已删除用户不能获得 Token。
- 普通用户只能获得 `order:read:self`。
- 管理员可以获得 `order:read:self` 和 `order:read:any`。
- Token 的 `iss`、`aud`、`sub`、`scope`、`role`、`exp` 和 `kid` 正确。
- Client Credentials 只能获得 `mcp:discover`。

### Ragent

- 启动工具发现使用服务令牌。
- 用户工具调用使用 Token Exchange，不再调用本地 HS256 Codec。
- 同一会话、Audience 和 Scope 在有效期内复用缓存 Token。
- Token 接近过期时重新交换。
- 401 后清缓存并最多重试一次。
- Auth Server 不可用且无缓存时工具调用失败关闭。
- 日志不包含 Sa-Token、Client Secret 或 Access Token。

### Order MCP

- 有效 RS256 Token 可以进入 MCP TransportContext。
- 缺失 Token、错误签名、错误 Issuer、错误 Audience 和过期 Token 返回 401。
- `mcp:discover` Token 不能执行订单工具。
- 普通用户列表始终绑定 `sub`。
- 普通用户不能读取其他用户订单详情。
- 普通用户不能执行 `order_admin_search`。
- 管理员同时具备角色和 Scope 时可以查询全部订单。
- 只有角色没有 `order:read:any` 时仍然拒绝管理员查询。

### 端到端

- Auth Server、Order MCP、Ragent 按顺序启动后注册 3 个订单工具。
- 从 `/rag/eval` 发起本人查询、越权详情、普通用户管理员查询和管理员查询。
- 原有订单评测的 `security_leak_count` 必须保持为 0。
- 比较迁移前后的 P95/P99，区分首次 Token Exchange 和缓存命中延迟。

## 验收标准

1. Ragent 和 Order MCP 配置中不再存在共享 JWT Secret。
2. Auth Server 是唯一持有订单 Access Token 签名私钥的组件。
3. Order MCP 只使用 JWKS 公钥验证 RS256 Token。
4. 用户身份来自有效 Sa-Token 和 `t_user` 查询，不来自模型参数。
5. 普通用户无法获得 `order:read:any`。
6. Order MCP 同时执行 Scope、角色和 SQL 行级过滤。
7. 所有聚焦测试通过。
8. Chat 链路评测 `security_leak_count = 0`。
9. 文档包含启动顺序、配置、密钥管理和回滚方式。

## 参考资料

- [Spring Authorization Server Getting Started](https://docs.spring.io/spring-authorization-server/reference/getting-started.html)
- [Spring Authorization Server Protocol Endpoints](https://docs.spring.io/spring-authorization-server/reference/protocol-endpoints.html)
- [Spring Security OAuth 2.0 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [RFC 8693 OAuth 2.0 Token Exchange](https://www.rfc-editor.org/rfc/rfc8693)
- [MCP Authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
