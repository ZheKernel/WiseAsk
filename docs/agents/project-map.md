# Agent 项目地图

当根目录 `AGENTS.md` 提供的信息不够，需要进一步了解仓库结构时，再读取本文件。

## 产品形态

Ragent AI 是一个企业级 Agentic RAG 知识库与智能问答平台。它覆盖文档入库、
检索、意图识别、MCP 工具调用、模型路由、流式回答、会话记忆、长期记忆和
链路可观测。

## 后端模块

- `framework`：通用基础设施、统一响应、异常、幂等、分布式 ID、上下文透传
  和 SSE 支持。
- `infra-ai`：模型客户端、Embedding、rerank、供应商适配、模型路由、健康状态
  和降级。
- `bootstrap`：应用启动和核心业务行为，包括 RAG Pipeline、知识库、数据集、
  文档入库、记忆、Prompt、检索、意图识别、API 和配置。
- `mcp-auth`：Ragent 与受保护 MCP 服务共享的调用身份模型和短期 JWT 编解码。
- `mcp-server`：MCP 工具服务和协议集成。
- `mcp-order-server`：独立 PostgreSQL 订单查询服务，在 MCP 服务端再次校验角色
  和订单数据归属。

## 前端

`frontend` 包含 React 18、TypeScript、Vite 管理端和问答端 UI。前端使用
Tailwind CSS、Radix UI、Lucide React、Zustand、React Router、Recharts、
TanStack Table 和 React Hook Form。

## 数据和运行环境

- PostgreSQL schema 和升级脚本在 `resources/database`。
- Docker Compose 文件在 `resources/docker`。
- Prompt 模板和应用配置在 `bootstrap/src/main/resources`。
- 架构图和面向用户的文档在 `assets` 和 `docs`。

## 已有设计记忆

有用的已有文档：

- `README.md`：项目概览、模块结构、启动方式和常用命令。
- `docs/superpowers/specs/2026-06-15-prompt-context-layering-design.md`
- `docs/superpowers/specs/2026-06-15-long-term-memory-design.md`
- `docs/superpowers/specs/2026-06-27-rag-permission-scope-design.md`
- `docs/superpowers/specs/2026-06-27-order-mcp-permission-design.md`
- `docs/superpowers/plans/2026-06-15-prompt-context-layering.md`
- `docs/superpowers/plans/2026-06-15-long-term-memory.md`
- `docs/superpowers/plans/2026-06-27-order-mcp-permission.md`

RAG 权限当前采用两级知识库作用域：`GLOBAL` 和 `PERSONAL`。普通聊天只检索
全局库与当前用户个人库；管理员管理页可以查看全部知识库，但管理员普通聊天不会
自动检索其他用户个人库。知识库、文档、Chunk 和 MCP 用户入口都必须经过
`RagResourcePermissionService` 或其预计算授权集合。

订单 MCP 采用双重权限校验。Ragent 在调用前校验工具权限，并通过每次调用签发的
短期内部 JWT 传递可信用户 ID 和角色；`mcp-order-server` 验证令牌后仍在服务端
强制用户行过滤。模型参数不能决定调用者身份，普通用户无法通过传入其他 `userId`
查询他人订单。部署和验证步骤见 `docs/order-mcp-quick-start.md`。

这些文档只能作为索引。精确行为仍然要读源码确认。
