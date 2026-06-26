# RAG Permission Scope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first permission-control layer for personal/global knowledge bases and MCP tool execution.

**Architecture:** Knowledge bases gain explicit `scope` and `ownerUserId` fields. A focused `RagResourcePermissionService` owns authorization decisions, while services and retrieval channels consume those decisions instead of open-coding role checks. MCP execution checks the same permission boundary before parameter extraction or tool execution.

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, Sa-Token user context, JUnit 5, Mockito, PostgreSQL SQL scripts.

---

## File Structure

- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeBaseDO.java`
  - Add `ownerUserId` and `scope`.
- Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/enums/KnowledgeBaseScope.java`
  - Normalize and validate `GLOBAL` / `PERSONAL`.
- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeBaseCreateRequest.java`
  - Add optional `scope`.
- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/vo/KnowledgeBaseVO.java`
  - Return `ownerUserId` and `scope`.
- Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/McpToolPermissionProperties.java`
  - Store default config-backed MCP policy lists.
- Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/permission/RagResourcePermissionService.java`
  - Expose KB and MCP authorization decisions.
- Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/permission/DefaultRagResourcePermissionService.java`
  - Implement first-version role/scope policy.
- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java`
  - Set KB scope on create and enforce management/listing visibility.
- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`
  - Enforce KB management permission before document operations.
- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContext.java`
  - Carry authorized collection names.
- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`
  - Resolve authorized collections once and place them in `SearchContext`.
- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java`
  - Retrieve only authorized collections.
- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/IntentDirectedSearchChannel.java`
  - Skip KB intents whose collection is not authorized.
- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java`
  - Skip denied MCP tools before parameter extraction and execution.
- Modify `resources/database/schema_pg.sql`
  - Add `owner_user_id` and `scope` to `t_knowledge_base`.
- Create `resources/database/upgrade_v1.3_to_v1.4.sql`
  - Add idempotent migration for existing deployments.
- Test `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/permission/DefaultRagResourcePermissionServiceTest.java`
- Test `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImplTest.java`
- Test `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannelTest.java`
- Test `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/IntentDirectedSearchChannelTest.java`
- Test `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEnginePermissionTest.java`

## Task 1: Permission Service And KB Creation Tests

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/permission/DefaultRagResourcePermissionServiceTest.java`
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImplTest.java`

- [ ] **Step 1: Write failing permission service tests**

```java
@Test
void shouldReturnGlobalAndOwnPersonalCollectionsForOrdinaryChat() {
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
            kb("global", "global_collection", "GLOBAL", "admin-1"),
            kb("own", "own_collection", "PERSONAL", "user-1"),
            kb("other", "other_collection", "PERSONAL", "user-2")
    ));

    List<String> result = service.listRetrievableCollections(loginUser("user-1", "user"));

    assertThat(result).containsExactlyInAnyOrder("global_collection", "own_collection");
}

@Test
void shouldDenyUserManagingAnotherUsersPersonalKnowledgeBase() {
    KnowledgeBaseDO kb = kb("other", "other_collection", "PERSONAL", "user-2");

    boolean result = service.canManageKnowledgeBase(loginUser("user-1", "user"), kb);

    assertThat(result).isFalse();
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\mvnw.cmd -pl bootstrap -am "-Dtest=DefaultRagResourcePermissionServiceTest,KnowledgeBaseServiceImplTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine= " "-DforkCount=0" test
```

Expected: FAIL because permission service and KB scope fields do not exist.

- [ ] **Step 3: Write failing KB creation tests**

```java
@Test
void shouldCreatePersonalKnowledgeBaseForNormalUser() {
    UserContext.set(loginUser("user-1", "user"));
    when(request.getName()).thenReturn("Personal KB");
    when(request.getEmbeddingModel()).thenReturn("bge");
    when(request.getCollectionName()).thenReturn("user_personal");

    service.create(request);

    ArgumentCaptor<KnowledgeBaseDO> captor = ArgumentCaptor.forClass(KnowledgeBaseDO.class);
    verify(knowledgeBaseMapper).insert(captor.capture());
    assertThat(captor.getValue().getScope()).isEqualTo("PERSONAL");
    assertThat(captor.getValue().getOwnerUserId()).isEqualTo("user-1");
}

@Test
void shouldCreateGlobalKnowledgeBaseForAdminByDefault() {
    UserContext.set(loginUser("admin-1", "admin"));
    when(request.getName()).thenReturn("Global KB");
    when(request.getEmbeddingModel()).thenReturn("bge");
    when(request.getCollectionName()).thenReturn("global_kb");

    service.create(request);

    ArgumentCaptor<KnowledgeBaseDO> captor = ArgumentCaptor.forClass(KnowledgeBaseDO.class);
    verify(knowledgeBaseMapper).insert(captor.capture());
    assertThat(captor.getValue().getScope()).isEqualTo("GLOBAL");
    assertThat(captor.getValue().getOwnerUserId()).isEqualTo("admin-1");
}
```

- [ ] **Step 4: Run tests and verify RED**

Use the same Maven command. Expected: FAIL because creation does not set scope or owner.

## Task 2: Data Model And Permission Service

**Files:**
- Modify: `KnowledgeBaseDO.java`
- Create: `KnowledgeBaseScope.java`
- Modify: `KnowledgeBaseCreateRequest.java`
- Modify: `KnowledgeBaseVO.java`
- Create: `McpToolPermissionProperties.java`
- Create: `RagResourcePermissionService.java`
- Create: `DefaultRagResourcePermissionService.java`

- [ ] **Step 1: Add KB scope fields and enum**

Add `ownerUserId` and `scope` to `KnowledgeBaseDO`, `KnowledgeBaseVO`, and optional `scope` to `KnowledgeBaseCreateRequest`. Create `KnowledgeBaseScope` with:

```java
public enum KnowledgeBaseScope {
    GLOBAL,
    PERSONAL;

    public static String normalize(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        for (KnowledgeBaseScope scope : values()) {
            if (scope.name().equalsIgnoreCase(value.trim())) {
                return scope.name();
            }
        }
        throw new ClientException("不支持的知识库作用域：" + value);
    }
}
```

- [ ] **Step 2: Add MCP permission properties**

Create config properties with defaults:

```java
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.permission.mcp")
public class McpToolPermissionProperties {
    private List<String> disabledToolIds = List.of();
    private List<String> adminOnlyToolIds = List.of();
}
```

- [ ] **Step 3: Implement `RagResourcePermissionService`**

Expose:

```java
List<String> listRetrievableCollections(LoginUser user);
boolean canManageKnowledgeBase(LoginUser user, KnowledgeBaseDO kb);
boolean canCallMcpTool(LoginUser user, String toolId);
```

Rules:

- Retrievable collections are active `GLOBAL` plus active `PERSONAL` owned by `user.userId`.
- Admin can manage any KB.
- User can manage only own `PERSONAL` KB.
- MCP disabled list denies everyone.
- MCP admin-only list allows admins only.
- All other tools are allowed for authenticated users.

- [ ] **Step 4: Run Task 1 tests and verify GREEN for permission service**

Run the Task 1 Maven command. Expected: permission service tests pass; KB creation tests may still fail until Task 3.

## Task 3: Knowledge Base Service Enforcement

**Files:**
- Modify: `KnowledgeBaseServiceImpl.java`
- Modify: `KnowledgeDocumentServiceImpl.java`

- [ ] **Step 1: Implement KB create scope defaults**

In `KnowledgeBaseServiceImpl.create`:

```java
LoginUser user = UserContext.requireUser();
String defaultScope = "admin".equalsIgnoreCase(user.getRole()) ? "GLOBAL" : "PERSONAL";
String scope = KnowledgeBaseScope.normalize(requestParam.getScope(), defaultScope);
if (!"admin".equalsIgnoreCase(user.getRole())) {
    scope = "PERSONAL";
}
```

Set `.ownerUserId(user.getUserId())` and `.scope(scope)` on insert.

- [ ] **Step 2: Enforce read/update/delete/list management visibility**

Before returning or mutating a KB, call `permissionService.canManageKnowledgeBase(UserContext.requireUser(), kb)`. User listing filters to `GLOBAL` plus own `PERSONAL`; admin management listing remains all.

- [ ] **Step 3: Enforce document operations**

For document operations that receive `kbId`, check that KB. For operations that receive `docId`, load the document, then load its KB and check it before mutating, previewing, chunking, or returning file metadata.

- [ ] **Step 4: Run Task 1 tests and verify GREEN**

Run the Task 1 Maven command. Expected: all Task 1 tests pass.

## Task 4: Retrieval Channel Permission Tests And Implementation

**Files:**
- Modify/Test: `SearchContext.java`
- Modify/Test: `MultiChannelRetrievalEngine.java`
- Modify/Test: `VectorGlobalSearchChannel.java`
- Modify/Test: `IntentDirectedSearchChannel.java`

- [ ] **Step 1: Write failing vector global retrieval test**

```java
@Test
void shouldSearchOnlyAuthorizedCollections() {
    RetrieverService retrieverService = mock(RetrieverService.class);
    VectorGlobalSearchChannel channel = new VectorGlobalSearchChannel(retrieverService, properties(), Runnable::run);
    SearchContext context = SearchContext.builder()
            .rewrittenQuestion("question")
            .topK(3)
            .authorizedCollections(Set.of("global_collection", "own_collection"))
            .build();

    channel.search(context);

    verify(retrieverService).retrieve(argThat(req -> "global_collection".equals(req.getCollectionName())));
    verify(retrieverService).retrieve(argThat(req -> "own_collection".equals(req.getCollectionName())));
    verifyNoMoreInteractions(retrieverService);
}
```

- [ ] **Step 2: Write failing intent-directed retrieval test**

```java
@Test
void shouldSkipUnauthorizedIntentCollections() {
    IntentDirectedSearchChannel channel = new IntentDirectedSearchChannel(retrieverService, properties(), Runnable::run);
    SearchContext context = SearchContext.builder()
            .rewrittenQuestion("question")
            .topK(3)
            .authorizedCollections(Set.of("allowed_collection"))
            .intents(List.of(new SubQuestionIntent("question", List.of(
                    score(node("allowed_collection")),
                    score(node("denied_collection"))
            ))))
            .build();

    channel.search(context);

    verify(retrieverService).retrieve(argThat(req -> "allowed_collection".equals(req.getCollectionName())));
    verify(retrieverService, never()).retrieve(argThat(req -> "denied_collection".equals(req.getCollectionName())));
}
```

- [ ] **Step 3: Run channel tests and verify RED**

Run:

```powershell
.\mvnw.cmd -pl bootstrap -am "-Dtest=VectorGlobalSearchChannelTest,IntentDirectedSearchChannelTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine= " "-DforkCount=0" test
```

Expected: FAIL because `authorizedCollections` does not exist and channels do not filter.

- [ ] **Step 4: Implement retrieval filtering**

Add `Set<String> authorizedCollections` to `SearchContext`. `MultiChannelRetrievalEngine` calls `permissionService.listRetrievableCollections(UserContext.requireUser())` and sets the result on context. `VectorGlobalSearchChannel` uses only `context.getAuthorizedCollections()`. `IntentDirectedSearchChannel` filters KB intents by collection membership.

- [ ] **Step 5: Run channel tests and verify GREEN**

Run the same Maven command. Expected: PASS.

## Task 5: MCP Authorization Tests And Implementation

**Files:**
- Modify/Test: `RetrievalEngine.java`
- Test: `RetrievalEnginePermissionTest.java`

- [ ] **Step 1: Write failing MCP denial test**

```java
@Test
void shouldNotExecuteDeniedMcpTool() {
    when(permissionService.canCallMcpTool(any(), eq("admin-tool"))).thenReturn(false);
    when(registry.getExecutor("admin-tool")).thenReturn(Optional.of(executor));

    RetrievalContext result = engine.retrieve(List.of(new SubQuestionIntent("question", List.of(
            NodeScore.builder().node(mcpNode("admin-tool")).score(0.95).build()
    ))), 3);

    assertThat(result.hasMcp()).isFalse();
    verify(executor, never()).execute(anyMap());
    verify(parameterExtractor, never()).extractParameters(anyString(), any(), any());
}
```

- [ ] **Step 2: Run MCP test and verify RED**

Run:

```powershell
.\mvnw.cmd -pl bootstrap -am "-Dtest=RetrievalEnginePermissionTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine= " "-DforkCount=0" test
```

Expected: FAIL because `RetrievalEngine` does not check permission service.

- [ ] **Step 3: Implement MCP check**

In `executeSingleMcpTool`, before registry lookup and parameter extraction:

```java
if (!permissionService.canCallMcpTool(UserContext.requireUser(), toolId)) {
    log.warn("MCP 工具无权限调用, toolId: {}, userId: {}", toolId, UserContext.getUserId());
    return null;
}
```

- [ ] **Step 4: Run MCP test and verify GREEN**

Run the same Maven command. Expected: PASS.

## Task 6: Database Migration And Focused Verification

**Files:**
- Modify: `resources/database/schema_pg.sql`
- Create: `resources/database/upgrade_v1.3_to_v1.4.sql`

- [ ] **Step 1: Update schema**

Add:

```sql
owner_user_id VARCHAR(20),
scope         VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
```

to `t_knowledge_base` and add comments.

- [ ] **Step 2: Add idempotent upgrade script**

```sql
ALTER TABLE t_knowledge_base
    ADD COLUMN IF NOT EXISTS owner_user_id VARCHAR(20);

ALTER TABLE t_knowledge_base
    ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'GLOBAL';

UPDATE t_knowledge_base
SET scope = 'GLOBAL'
WHERE scope IS NULL OR scope = '';

COMMENT ON COLUMN t_knowledge_base.owner_user_id IS '知识库所有者用户ID';
COMMENT ON COLUMN t_knowledge_base.scope IS '知识库作用域：GLOBAL/PERSONAL';
```

- [ ] **Step 3: Run focused backend tests**

Run:

```powershell
.\mvnw.cmd -pl bootstrap -am "-Dtest=DefaultRagResourcePermissionServiceTest,KnowledgeBaseServiceImplTest,VectorGlobalSearchChannelTest,IntentDirectedSearchChannelTest,RetrievalEnginePermissionTest,StreamChatPipelineTest,RAGPromptServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine= " "-DforkCount=0" test
```

Expected: PASS.

- [ ] **Step 4: Review diff**

Run:

```powershell
git diff --stat
git diff
```

Expected: only permission-scope, schema, tests, and docs changed. `.codegraph` changes are not staged.

- [ ] **Step 5: Commit**

Run:

```powershell
git add docs/superpowers/specs/2026-06-27-rag-permission-scope-design.md docs/superpowers/plans/2026-06-27-rag-permission-scope.md bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge bootstrap/src/main/java/com/nageoffer/ai/ragent/rag bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge bootstrap/src/test/java/com/nageoffer/ai/ragent/rag resources/database/schema_pg.sql resources/database/upgrade_v1.3_to_v1.4.sql
git commit -m "feat: add RAG resource permission scope"
```

Expected: commit succeeds without staging `.codegraph`.

## Self-Review

- Spec coverage: covers KB scope, user/admin management boundary, retrieval filtering, MCP policy, schema, and tests.
- Placeholder scan: no TBD/TODO/fill-in instructions remain.
- Type consistency: `ownerUserId`, `scope`, `KnowledgeBaseScope`, `RagResourcePermissionService`, and `authorizedCollections` are used consistently across tasks.
