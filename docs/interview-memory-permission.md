# 长期记忆与权限控制面试讲解

## 简历表述

- 设计结构化长期记忆与四层 Prompt 上下文编排机制，基于 LLM 异步抽取 `PREFERENCE/PROJECT/CONSTRAINT/FACT` 记忆并按用户维度合并召回，结合会话摘要检查点和稳定前缀编排提升 KV Cache 复用率，兼顾跨会话上下文连续性与 Token 成本。
- 面向多用户电商订单场景构建 MCP 权限评测体系，基于短期 JWT 身份透传与 SQL 行级过滤实现管理员和普通用户隔离，生成 100 用户、10 万订单的合成数据集并支持扩展至 1 万次 Chat 决策链路请求，覆盖意图识别、工具调用、P95/P99 延迟与越权检测；首轮 Chat 链路实测中 MCP 跨用户数据泄露 0 次。

## Harness Engineering 定位

不同团队对 Harness 的分层命名并不完全一致。为了面试表述统一，可以把本项目归纳为模型接入、Prompt/Context、Memory、Tool/MCP、Permission、Runtime/Observability 六个维度。

| 功能 | 主要维度 | 关联维度 |
| --- | --- | --- |
| 长期记忆 | Memory | Prompt/Context、Runtime/Observability |
| 知识库与 MCP 权限 | Permission | Tool/MCP、Prompt/Context、Runtime/Observability |

核心观点：Harness Engineering 不是继续训练模型，而是在模型外部控制“给模型什么上下文、允许调用什么工具、以谁的身份调用、失败后如何降级以及如何审计”。

## 功能一：结构化长期记忆

### 业务场景

会话窗口和摘要只能保持单个会话的连续性。用户进入新会话后，技术偏好、项目背景和长期约束会丢失；如果始终携带全部历史消息，Token 成本和噪声又会持续增加。

目标是在不阻塞主问答链路的前提下，保存可复用的用户信息，并在后续问题中只召回少量相关记忆。

### 一分钟回答

> 原项目只有会话消息和会话摘要，解决的是单会话内的上下文增长，不能解决跨会话冷启动。我增加了结构化长期记忆闭环：回答完成后异步读取当前用户的近期对话，让 LLM 按固定 JSON Schema 抽取偏好、项目、约束和事实；服务端校验后，按 `user_id + memory_type + memory_key` 做幂等合并。下一次提问先改写问题，再只召回当前用户的 ACTIVE 记忆，按词法相关性、重要度、置信度和访问次数排序，取 Top N 作为 Semi-stable 上下文放在会话摘要之前。抽取和召回失败都降级为空，不影响 SSE 回答。

### 技术链路

```text
StreamChatEventHandler.onComplete
  -> 保存 assistant 消息
  -> LongTermMemoryService.extractAsync
  -> 独立线程池加载当前用户近期消息
  -> LLM 输出结构化 JSON
  -> 类型白名单、长度、置信度和重要度校验
  -> 唯一键合并到 t_user_long_term_memory

StreamChatPipeline.execute
  -> 加载会话摘要和近期历史
  -> 改写问题
  -> LongTermMemoryService.recall(userId, rewrittenQuery)
  -> 排序并限制 Top N
  -> RAGPromptService 按四层顺序组装 Prompt
```

### 关键实现

1. 数据模型：记忆表保存类型、合并键、内容、来源消息、置信度、重要度、访问次数和状态，并通过唯一约束避免同一用户的相同记忆重复写入。
2. 抽取：使用低温度 LLM 调用和固定 JSON 输出；无效 JSON、未知类型和空内容直接忽略。
3. 合并：新内容更具体时替换旧内容，置信度和重要度取较高值，同时保留访问统计。
4. 召回：评分公式以词法相关性为主，再叠加重要度、置信度和访问次数；召回后更新访问次数和最近访问时间。
5. Prompt：长期记忆以普通用户上下文注入，不放入 System Prompt，也不与本轮检索证据混合。
6. 可靠性：抽取使用独立线程池，所有异常仅记录日志；记忆属于增强能力，不能成为聊天成功的前置条件。

### 为什么这样设计

**为什么第一版不用向量检索？**

记忆量较小时，关系库加确定性排序更便宜、可解释、容易测试。后续数据量增大后，可以保留现有过滤和排序，在前面增加 Embedding 候选召回，形成混合检索。

**为什么在问题改写后召回？**

原始问题可能包含指代和口语表达，改写后的问题语义更完整，更适合作为记忆检索键。

**为什么不把记忆放进 System Prompt？**

长期记忆来自用户对话，可信级别低于系统规则。放在 Semi-stable 用户上下文可以保持优先级边界，降低记忆污染系统行为的风险。

**如何避免记忆污染？**

使用类型白名单、固定 Schema、长度限制、合并键、用户隔离和召回上限；抽取 Prompt 明确排除密码、Token 和敏感属性。当前仍缺少独立敏感信息分类器和人工管理界面，这是后续优化点。

### 可说明的结果

- 新会话能够复用用户明确表达的长期偏好和项目背景。
- 会话摘要与 Top N 长期记忆共同控制上下文规模，避免无限追加全部历史。
- 记忆失败不会拖垮主问答链路。

不要虚构 Token 降低比例或记忆命中率；没有压测或评测报告时，只描述机制和可验证行为。

## 功能二：知识库与 MCP 权限控制

### 业务场景

普通用户可以上传个人资料，管理员可以维护公共知识库并管理全部资源。系统必须保证检索阶段不会读取其他用户的私有文档，同时独立 MCP 服务也不能信任模型参数中的 `userId`。

### 一分钟回答

> 我把权限控制放在数据进入模型上下文之前。知识库增加 `GLOBAL/PERSONAL` 作用域和 `owner_user_id`，普通用户只能管理自己的个人知识库，管理员可以在管理端管理全部资源；但普通聊天无论角色都只检索全局库和自己的个人库，避免管理员日常问答意外混入其他用户数据。检索引擎先计算授权 Collection，向量全局检索和意图定向检索都只在这个集合内执行。对订单 MCP，Ragent 先做工具策略检查，再把当前登录身份签成短期 JWT；订单服务验证签名、受众和有效期后，从 TransportContext 取得调用者，普通用户的 SQL 强制追加 `user_id = token.sub`，管理员工具才允许跨用户查询，从而形成入口授权和服务端行级过滤两层防线。

### 知识库权限链路

```text
Sa-Token 登录用户
  -> RagResourcePermissionService
  -> 计算 GLOBAL + 当前用户 PERSONAL Collection
  -> 写入 SearchContext.authorizedCollections
  -> VectorGlobalSearchChannel 只遍历授权集合
  -> IntentDirectedSearchChannel 过滤未授权意图
  -> 只有授权 Chunk 可以进入 Prompt
```

管理端和检索端是两个不同边界：

| 边界 | 普通用户 | 管理员 |
| --- | --- | --- |
| 管理端 | 管理自己的个人知识库，全局库只读 | 管理全部知识库、文档和 Chunk |
| 普通聊天 | 全局库 + 自己的个人库 | 全局库 + 自己的个人库 |

管理员普通聊天不自动检索全部用户个人知识库。跨用户检索应设计单独入口、明确目的并记录审计日志。

### MCP 鉴权链路

```text
Ragent 当前 LoginUser + Sa-Token
  -> canCallMcpTool
  -> 使用 Ragent 私钥生成 private_key_jwt Client Assertion
  -> Auth Server 验证 Client JWKS、Sa-Token 和 t_user
  -> 签发 aud=order-mcp 的 RS256 Access Token
  -> Authorization: Bearer <access-token>
  -> Spring Security Resource Server + OrderMcpIdentityBridgeFilter
  -> 校验 signature / issuer / audience / time / scope
  -> McpCallerIdentity 写入 TransportContext
  -> OrderMcpExecutor / OrderQueryService 再鉴权
  -> OrderRepository 参数化 SQL 行级过滤
```

Access Token 包含 `sub`、`username`、`role`、`scope`、`client_id`、`iss`、`aud`、
`iat`、`nbf`、`exp` 和 `jti`。模型只能生成工具参数，不能生成或覆盖已签名的调用者身份。

### 三个订单工具

| 工具 | 服务端规则 |
| --- | --- |
| `order_list_mine` | 不接受用户 ID，始终使用令牌中的 `sub` 查询 |
| `order_detail` | 普通用户按 `order_no + user_id` 查询，管理员按订单号查询 |
| `order_admin_search` | Ragent 配置为管理员工具，订单服务再次校验 `role=admin` 和 `order:read:any` |

订单服务只暴露固定、参数化、只读 SQL，并限制查询条数。启动发现使用仅含
`mcp:discover` 的服务令牌，它可以执行 `tools/list`，但会被订单业务层拒绝执行查询。

### 为什么不能直接传 userId

工具参数来自 LLM 或客户端输入，属于不可信数据。攻击者可以通过 Prompt Injection 诱导模型传入其他用户 ID，也可以绕过主应用直接调用 MCP。短期签名令牌提供身份完整性、过期时间和服务受众约束，远端服务只信任验证后的身份声明。

### 为什么要双层鉴权

Ragent 的检查用于减少无权限工具被调用，改善用户体验；MCP 服务的检查才是最终安全边界。只在主应用鉴权会使远端服务在被直接访问时失去保护，只在远端鉴权则会让无权限调用无谓跨网络执行。

### 关键取舍与扩展

- 当前知识库只有 `GLOBAL/PERSONAL` 两级，后续可扩展租户、部门、项目组和共享 ACL。
- 当前 MCP 工具策略主要由配置维护，后续可迁移到策略表或统一 Policy Engine。
- 当前内部 JWT 使用共享 HMAC 密钥，适合项目内服务；生产环境应接入密钥轮换、KMS、非对称签名、mTLS 或企业 IAM。
- 管理员跨用户检索应采用显式开关、用途说明和完整审计，不能直接放开普通聊天。

## 两项功能的共同设计原则

1. **先确定边界，再组装 Prompt**：长期记忆按 `user_id` 召回，知识和工具证据先鉴权，最终 Prompt 不承担权限判断。
2. **模型输入不等于可信身份**：LLM 可以决定参数，但用户身份、角色和数据范围由服务端上下文决定。
3. **增强能力可降级，安全能力要失败关闭**：记忆失败返回空上下文；权限判断异常时不返回私有数据。
4. **主链路与后台任务隔离**：流式回答优先完成，长期记忆抽取异步执行。
5. **保留扩展边界**：记忆服务可以增加向量召回，权限服务可以增加租户 ACL，MCP 身份可以替换为企业 IAM。

## 面试追问速答

**这两个功能和普通 RAG 有什么区别？**

普通 RAG 重点是从文档中检索证据；这里进一步解决“跨会话保留什么用户上下文”和“哪些证据、工具对当前用户合法”，属于 Agent Harness 对上下文和执行边界的治理。

**怎样证明权限没有只做在前端？**

后端知识库、文档和 Chunk 服务分别校验查看与管理权限；检索通道只接收授权 Collection；订单 MCP 还会独立验证令牌并在 SQL 层绑定用户 ID。

**怎样证明记忆不会阻塞回答？**

抽取发生在助手消息持久化后，通过独立线程池异步执行；召回阶段捕获异常并返回空记忆，SSE 主链路继续运行。

**最大的后续优化是什么？**

长期记忆增加敏感信息检测、人工编辑和混合向量召回；权限增加租户/组织 ACL、统一策略中心、密钥轮换和跨用户操作审计。
