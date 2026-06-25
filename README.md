<p align="center">
  <a href="https://github.com/cz1015/cz">
    <img src="assets/ragent-ai-banner.png" alt="Ragent AI" />
  </a>
</p>

<h1 align="center">Ragent AI</h1>

<p align="center">
  企业级 Agentic RAG 知识库与智能问答平台
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-007396?style=flat-square&logo=openjdk&logoColor=white" alt="Java 17" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5.7-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot 3.5.7" />
  <img src="https://img.shields.io/badge/React-18-61DAFB?style=flat-square&logo=react&logoColor=111111" alt="React 18" />
  <img src="https://img.shields.io/badge/PostgreSQL-PGVector-4169E1?style=flat-square&logo=postgresql&logoColor=white" alt="PostgreSQL" />
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-Apache--2.0-4A90E2?style=flat-square" alt="Apache-2.0" /></a>
</p>

> 维护者：**cz**
>
> 本仓库基于 [nageoffer/ragent](https://github.com/nageoffer/ragent) 进行二次开发，重点增强会话记忆、长期记忆和 Prompt 上下文编排能力。

## 项目概览

Ragent AI 是一个前后端分离的 Agentic RAG 平台，覆盖文档入库、知识检索、意图识别、MCP 工具调用、模型路由、流式问答、会话记忆和链路追踪等完整流程。

系统后端采用 Java 17 和 Spring Boot 3.5.7，按通用框架、AI 基础设施、业务服务和 MCP Server 拆分为多个 Maven 模块；前端使用 React 18、TypeScript 和 Vite 构建用户问答界面及管理控制台。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 知识库管理 | 支持知识库、数据集、文档和分块管理，记录文档处理状态及执行日志 |
| 文档入库 Pipeline | 通过可编排节点完成解析、切分、向量化和持久化，支持节点级日志与失败定位 |
| 多路检索 | 支持全局向量检索、意图定向检索及后处理链，兼顾召回率与结果精度 |
| 意图识别 | 使用树形意图结构完成多级分类，对低置信度结果进行歧义引导 |
| 问题处理 | 支持问题重写、子问题拆分和多意图解析，为后续检索提供结构化输入 |
| MCP 集成 | 根据意图提取参数并调用 MCP 工具，可与知识库检索结果组合生成答案 |
| 模型路由 | 支持多模型候选、优先级调度、健康检查、三态熔断和自动降级 |
| 会话记忆 | 支持近期对话窗口、会话摘要和结构化长期记忆，控制上下文长度 |
| 流式输出 | 基于 SSE 返回模型响应，支持首包探测、取消请求和模型切换 |
| 可观测性 | 记录 RAG 全链路 Trace、节点耗时、输入输出及异常信息 |
| 管理控制台 | 提供知识库、意图树、模型、入库任务、链路追踪、用户和系统设置页面 |

## 本分支增强

### 1. 结构化长期记忆

- 新增用户长期记忆表，支持 `PREFERENCE`、`PROJECT`、`CONSTRAINT` 和 `FACT` 四类记忆。
- 在回答完成后异步分析近期对话，通过模型抽取可复用信息并按用户、类型和记忆键执行新增或更新。
- 召回时结合问题相关性、重要度、置信度和历史访问次数进行排序，并限制注入数量。
- 长期记忆加载失败时采用降级处理，不阻断主问答链路。

主要实现：

- `JdbcLongTermMemoryService`
- `LongTermMemoryService`
- `UserLongTermMemoryDO`
- `long-term-memory-extract.st`

### 2. 会话摘要检查点

- 将近期原始对话和历史摘要分开管理，避免每轮请求携带完整聊天记录。
- 根据摘要后的用户轮次和预估输入 Token 数决定是否更新摘要。
- 支持摘要起始轮数、保留轮数、最小更新轮数和 Token 触发阈值配置。
- 保留摘要边界后的原始消息，降低上下文遗漏风险。

### 3. Prompt 上下文分层

发送给模型的消息按稳定性和用途组织为四层：

| 层级 | 内容 |
| --- | --- |
| Stable | 系统提示词与长期稳定规则 |
| Semi-stable | 用户长期记忆与会话摘要 |
| History | 当前摘要边界后的近期原始对话 |
| Ephemeral | 本次检索证据、MCP 结果、子问题和用户问题 |

`PromptContextAssembler` 负责保持各层顺序并过滤空内容，`RAGPromptService` 根据 KB、MCP 或混合场景构造最终消息列表。

### 4. 流式问答链路整合

当前流式问答 Pipeline 的主要阶段如下：

```text
加载会话记忆
  -> 问题重写与拆分
  -> 召回长期记忆
  -> 意图识别与歧义引导
  -> 多通道检索 / MCP 调用
  -> Prompt 分层组装
  -> 模型路由与流式生成
  -> 消息持久化与长期记忆异步抽取
```

相关改造包含配置项、数据库升级脚本和针对摘要、长期记忆、Prompt 组装及流式处理的回归测试。

## 系统架构

后端按职责拆分为四个 Maven 模块：

| 模块 | 职责 |
| --- | --- |
| `framework` | 通用异常、响应体、幂等、分布式 ID、上下文透传和 SSE 等基础能力 |
| `infra-ai` | 模型客户端、Embedding、Rerank、模型路由、健康状态和供应商适配 |
| `bootstrap` | RAG 业务、知识库、意图、检索、记忆、入库 Pipeline、接口和配置 |
| `mcp-server` | 独立 MCP 工具服务及协议接入 |

![Ragent 模块分层](assets/ragent-module-layering-v2.png)

一次完整问答涉及会话记忆、问题改写、意图识别、检索、Prompt 编排、模型调用和结果持久化：

![Ragent 核心链路](assets/ragent-chain-v3.png)

## 关键设计

### 多通道检索

检索通道彼此独立，通过线程池并行执行，结果进入统一后处理链完成去重、过滤和重排序。

![多通道检索架构](assets/multi-channel-retrieval.png)

扩展新的检索策略时，实现 `SearchChannel` 并注册为 Spring Bean 即可接入现有流程；新的后处理逻辑通过 `SearchResultPostProcessor` 加入处理链。

### 模型路由与容错

模型候选按优先级调度，每个模型维护独立健康状态。连续失败达到阈值后进入熔断状态，冷却期后通过半开探测决定恢复或继续熔断。

![模型路由与降级](assets/model-routing-failover.svg)

![模型健康状态](assets/model-health-store.svg)

### 文档入库

文档上传后进入节点化 Pipeline，各节点支持独立配置、条件执行、输出传递和执行日志。

![文档入库 Pipeline](assets/ingestion-pipeline.png)

### 设计模式

| 设计模式 | 应用位置 |
| --- | --- |
| 策略模式 | 检索通道、检索后处理器、MCP 工具执行器 |
| 工厂模式 | 意图树、流式回调等复杂对象创建 |
| 注册表模式 | MCP 工具和意图节点自动发现 |
| 模板方法 | 文档入库节点的统一执行流程 |
| 装饰器模式 | 模型流式响应首包探测 |
| 责任链模式 | 检索后处理链和模型降级链 |
| 观察者模式 | 流式事件通知 |
| AOP | RAG 链路追踪和请求限流 |

## 技术栈

### 后端

| 分类 | 技术 |
| --- | --- |
| 基础框架 | Java 17、Spring Boot 3.5.7、Maven |
| 数据访问 | PostgreSQL、MyBatis-Plus、HikariCP |
| 向量检索 | PGVector、Milvus |
| 缓存与并发 | Redis、Redisson、Transmittable ThreadLocal |
| 消息队列 | RocketMQ |
| 文档处理 | Apache Tika、S3 兼容对象存储 |
| 认证鉴权 | Sa-Token |
| AI 接入 | 自定义模型客户端、OpenAI 兼容接口、Ollama、百炼等供应商 |
| 工具协议 | Model Context Protocol Java SDK |
| 测试 | JUnit 5、Mockito |

### 前端

| 分类 | 技术 |
| --- | --- |
| 基础框架 | React 18、TypeScript、Vite |
| 路由与状态 | React Router、Zustand |
| UI | Tailwind CSS、Radix UI、Lucide React |
| 表单与校验 | React Hook Form、Zod |
| 图表与表格 | Recharts、TanStack Table |
| Markdown | React Markdown、Remark GFM、代码高亮 |

## 项目结构

```text
ragent
├── bootstrap                 # RAG 核心业务与应用启动模块
│   ├── src/main/java         # 控制器、服务、Pipeline、检索、记忆等
│   ├── src/main/resources    # 配置、Prompt 模板和静态资源
│   └── src/test              # 后端测试
├── framework                 # 通用工程基础设施
├── infra-ai                  # 模型与 AI 基础设施适配
├── mcp-server                # MCP 工具服务
├── frontend                  # React 管理端与问答端
├── resources
│   ├── database              # PostgreSQL 建表、初始化及升级脚本
│   ├── docker                # Milvus、RocketMQ 等 Compose 配置
│   └── format                # Java 格式化配置
├── assets                    # README 架构图与界面截图
└── pom.xml                   # Maven 聚合工程
```

## 功能界面

### 用户问答

支持自然语言提问、示例问题、深度思考、Markdown 渲染、代码高亮和回答评价。

![用户问答首页](assets/qa-home.png)

![问答结果](assets/qa-answer.png)

### 管理控制台

管理端覆盖系统概览、知识库、数据集、入库任务、模型管理、意图树、链路追踪、用户和系统设置。

![管理端概览](assets/admin-overview.png)

![知识库管理](assets/admin-knowledge-base.png)

![链路追踪](assets/admin-trace.png)

## 快速启动

### 1. 环境要求

- JDK 17
- Node.js 18+
- PostgreSQL
- Redis
- RocketMQ
- Maven Wrapper 或 Maven 3.9+
- Docker Compose，可用于启动 RocketMQ 或可选的 Milvus

### 2. 初始化数据库

创建名为 `ragent` 的 PostgreSQL 数据库，然后执行：

```bash
psql -U postgres -d ragent -f resources/database/schema_pg.sql
psql -U postgres -d ragent -f resources/database/init_data_pg.sql
```

旧版本升级时按版本顺序执行 `resources/database/upgrade_*.sql`。本分支长期记忆表对应：

```bash
psql -U postgres -d ragent -f resources/database/upgrade_v1.2_to_v1.3.sql
```

### 3. 启动基础服务

启动 RocketMQ：

```bash
docker compose -f resources/docker/rocketmq-stack-5.2.0.compose.yaml up -d
```

默认向量存储为 PostgreSQL。需要切换到 Milvus 时，将 `rag.vector.type` 改为 `milvus`，然后启动：

```bash
docker compose -f resources/docker/milvus-stack-2.6.6.compose.yaml up -d
```

Redis 需运行在配置文件指定的地址和端口。

### 4. 配置后端

修改 `bootstrap/src/main/resources/application.yaml`：

- PostgreSQL 连接信息
- Redis 地址、端口和密码
- RocketMQ NameServer 地址
- 对象存储连接信息
- 模型供应商地址、API Key 和候选模型
- Embedding、Rerank 及默认向量维度

不要在公开仓库中提交真实 API Key。建议使用环境变量，例如：

```yaml
api-key: ${BAILIAN_API_KEY:}
```

### 5. 启动后端

Windows：

```powershell
.\mvnw.cmd -pl bootstrap -am spring-boot:run
```

Linux / macOS：

```bash
./mvnw -pl bootstrap -am spring-boot:run
```

后端默认地址为 `http://localhost:9090/api/ragent`。

### 6. 启动前端

```bash
cd frontend
npm ci
npm run dev
```

前端默认地址为 `http://localhost:5173`，开发服务器会将 `/api` 请求代理到后端 `9090` 端口。

## 常用验证命令

后端测试：

```bash
./mvnw test
```

前端构建：

```bash
cd frontend
npm ci
npm run build
```

前端代码检查：

```bash
cd frontend
npm run lint
```

## 项目来源与许可

- 维护者：`cz`
- 个人仓库：[cz1015/cz](https://github.com/cz1015/cz)
- 上游项目：[nageoffer/ragent](https://github.com/nageoffer/ragent)
- 开源协议：[Apache License 2.0](LICENSE)

本仓库保留上游项目许可，并在其基础上进行了功能扩展和工程调整。
