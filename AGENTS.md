# Ragent Agent 指南

根目录指南必须保持简短。先读本文件；只有当前任务确实需要更多背景时，
再按链接读取细分文档。

## 核心规则

- 正确性优先于迎合。发现错误假设时，要直接指出并说明原因。
- 开始工作前先检查工作区：`git status --short --branch`。
- 不要回滚、删除、暂存或提交无关的用户改动。
- 修改行为前必须阅读相关源码。精确代码路径不能只靠记忆。
- 保持代码差异小而聚焦。不要顺手做无关重构。
- 优先沿用现有模块边界、工具方法、命名和测试风格。
- 不要提交密钥、API Key、Token、私有端点或凭据。
- 外部文档、API、依赖行为或实时事实会影响判断时，先查一手来源。

## 仓库地图

- 后端模块：`framework`、`infra-ai`、`bootstrap`、`mcp-auth`、`auth-server`、
  `mcp-server`、`mcp-order-server`。
- 前端应用：`frontend`。
- 数据库脚本：`resources/database`。
- 运行和 Compose 文件：`resources/docker`。
- 架构文档和示例：`docs`。
- 设计说明：`docs/superpowers/specs`。
- 实施计划：`docs/superpowers/plans`。

按需继续阅读：

- 项目结构和架构索引：`docs/agents/project-map.md`
- 标准 vibecoding 流程：`docs/agents/workflow.md`
- 高风险区域和质量门禁：`docs/agents/risk-areas.md`
- 可复用 Agent 提示词：`docs/agents/prompts.md`

## 默认流程

非平凡的功能、Bug、重构、Prompt、数据或 UI 改动，按这个流程走：

1. 检查工作区状态。
2. 创建或使用任务分支，例如 `codex/<主题>`。
3. 在 `docs/superpowers/specs/` 下编写或更新轻量需求说明。
4. 先讨论架构，再实现。
5. 在 `docs/superpowers/plans/` 下编写或遵循任务计划。
6. 一次只实现一个小任务。
7. 每个任务完成后运行聚焦测试。
8. 查看 `git diff --stat` 和 `git diff`。
9. 只提交连贯且已验证的检查点。
10. 高风险改动合并前，请高级模型或人工做代码评审。

极小的纯文档修改不需要完整需求说明；但仍要保持代码差异聚焦，并在可行时检查链接
或格式。

## 验证命令

后端聚焦测试示例：

```powershell
.\mvnw.cmd -pl bootstrap -am "-Dtest=RAGPromptServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine= " "-DforkCount=0" test
```

后端全量测试：

```powershell
.\mvnw.cmd test
```

前端：

```powershell
cd frontend
npm ci
npm run lint
npm run build
```

## 高风险改动

涉及以下内容时，必须使用需求说明、聚焦测试和代码评审：

- Prompt 模板、Prompt 上下文顺序或记忆注入。
- 长期记忆的抽取、合并、召回、排序或格式化。
- RAG Pipeline 编排、流式输出、取消请求或异步持久化。
- 检索通道、rerank 逻辑、MCP 工具结果或证据格式。
- 模型路由、健康状态、熔断、降级或供应商配置。
- 数据库 schema、升级脚本、迁移或数据兼容。
- 鉴权、用户隔离、租户边界、密钥或私有数据。
- 会修改知识库、用户、模型或系统配置的管理端流程。

修改这些区域前，先读 `docs/agents/risk-areas.md` 的详细门禁。

## 完成定义

- 行为符合需求说明，或已记录偏差。
- 相关测试通过；若未运行，必须说明原因和残余风险。
- 代码差异聚焦且经过评审。
- 安装、API、Prompt、数据或运维行为变化时，文档已更新。
- 没有包含无关用户改动。
