# Project README Technical Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the root README as a project-only technical document maintained by `cz`, while clearly attributing the Apache-2.0 upstream project and highlighting verifiable branch enhancements.

**Architecture:** Replace the current teaching and marketing-heavy document with a concise technical reference. Keep useful local architecture and UI assets, separate upstream capabilities from `cz`'s branch-specific work, and validate every branch enhancement against `origin/main...HEAD`.

**Tech Stack:** Markdown, Git, Maven multi-module project metadata, React/Vite package metadata

---

### Task 1: Rewrite The README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Replace the promotional header**

Keep `assets/ragent-ai-banner.png`, set the maintainer link to `https://github.com/cz1015/cz`, and show only project, Java, Spring Boot, React, and Apache-2.0 badges that do not depend on upstream repository statistics.

- [ ] **Step 2: Add project positioning and attribution**

State that Ragent AI is an enterprise-oriented Agentic RAG system and that this repository is a deep secondary development maintained by `cz` based on the Apache-2.0 upstream project:

```markdown
> 本仓库由 **cz** 维护，基于开源项目 [nageoffer/ragent](https://github.com/nageoffer/ragent)
> 进行深度二次开发，重点增强会话记忆与 Prompt 上下文工程能力。
```

- [ ] **Step 3: Add the personal contribution section**

Describe only features present in `origin/main...HEAD`: structured long-term memory, summary checkpoints, layered Prompt context, pipeline integration, database upgrades, configuration, and regression tests.

- [ ] **Step 4: Add technical overview sections**

Include these sections in this order:

```text
项目概览
核心能力
本分支增强
系统架构
关键设计
技术栈
项目结构
功能界面
快速启动
常用验证命令
项目来源与许可
```

- [ ] **Step 5: Add verified run commands**

Use commands supported by the repository:

```bash
docker compose -f resources/docker/milvus-stack-2.6.6.compose.yaml up -d
docker compose -f resources/docker/rocketmq-stack-5.2.0.compose.yaml up -d
./mvnw -pl bootstrap -am spring-boot:run
cd frontend
npm install
npm run dev
```

Mention backend port `9090`, PostgreSQL/Redis configuration in `bootstrap/src/main/resources/application.yaml`, and schema/upgrade scripts under `resources/database/`.

### Task 2: Validate Content And Attribution

**Files:**
- Test: `README.md`

- [ ] **Step 1: Check removed promotional references**

Run:

```powershell
rg -n "nageoffer\.com|star-history|contrib\.rocks|亦菲|彦祖|oneThread|社群|在线体验|简历怎么写" README.md
```

Expected: no matches.

- [ ] **Step 2: Check required identity and attribution**

Run:

```powershell
rg -n "cz1015/cz|维护者.*cz|二次开发|nageoffer/ragent|Apache-2.0" README.md
```

Expected: matches for personal repository, maintainer identity, secondary-development wording, upstream repository, and license.

- [ ] **Step 3: Check local image references**

Extract each `assets/...` reference from `README.md` and verify that the corresponding file exists under `assets/`.

- [ ] **Step 4: Check Markdown whitespace**

Run:

```powershell
git diff --check
```

Expected: exit code `0` with no whitespace errors.

### Task 3: Review And Commit

**Files:**
- Modify: `README.md`
- Create: `docs/superpowers/plans/2026-06-22-project-readme.md`

- [ ] **Step 1: Review the final diff**

Run:

```powershell
git diff -- README.md docs/superpowers/plans/2026-06-22-project-readme.md
```

Expected: only the project README rewrite and its implementation plan are shown.

- [ ] **Step 2: Commit the coherent documentation change**

Run:

```powershell
git add README.md docs/superpowers/plans/2026-06-22-project-readme.md
git commit -m "docs: streamline README to project essentials"
```

Expected: a commit containing only the README and implementation plan.
