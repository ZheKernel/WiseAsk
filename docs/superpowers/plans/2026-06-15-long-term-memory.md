# Long-Term Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add structured long-term memory extraction, merge, recall, and semi-stable prompt injection.

**Architecture:** Store long-term memory in PostgreSQL through MyBatis-Plus, recall it after query rewrite, and inject formatted memories into the prompt before conversation summary. Extraction is asynchronous and triggered only after normal assistant completion, so queue rejections and cancelled partial responses do not pollute long-term memory.

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, PostgreSQL schema SQL, JUnit 5, Mockito, Gson, Maven Surefire.

---

## File Structure

- Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/UserLongTermMemoryDO.java`
  - Entity for `t_user_long_term_memory`.

- Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/mapper/UserLongTermMemoryMapper.java`
  - MyBatis-Plus mapper.

- Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/LongTermMemoryService.java`
  - Interface for `recall(userId, query)` and `extractAsync(conversationId, userId, sourceMessageId)`.

- Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcLongTermMemoryService.java`
  - Implements recall ranking, formatting, async LLM extraction, JSON parsing, and upsert.

- Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/LongTermMemoryCandidate.java`
  - Internal candidate record parsed from LLM JSON.

- Create `bootstrap/src/main/resources/prompt/long-term-memory-extract.st`
  - LLM extraction system prompt.

- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/MemoryProperties.java`
  - Add long-term memory config fields.

- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/ThreadPoolExecutorConfig.java`
  - Add `longTermMemoryExecutor`.

- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/constant/RAGConstant.java`
  - Add prompt path constant.

- Modify `bootstrap/src/main/resources/application.yaml`
  - Add long-term memory config defaults.

- Modify `resources/database/schema_pg.sql`
  - Add table and comments.

- Create `resources/database/upgrade_v1.2_to_v1.3.sql`
  - Add migration SQL for existing databases.

- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContext.java`
  - Add `longTermMemory`.

- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java`
  - Add long-term memory semi-stable layer before conversation summary.

- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatContext.java`
  - Add `longTermMemory`.

- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatPipeline.java`
  - Recall long-term memory after rewrite and pass it into `PromptContext`.

- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java`
  - Add `LongTermMemoryService`.

- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java`
  - Inject and pass `LongTermMemoryService`.

- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java`
  - Trigger extraction after normal assistant completion only.

- Modify tests:
  - `RAGPromptServiceTest`
  - `StreamChatPipelineTest`
  - Add `JdbcLongTermMemoryServiceTest`

---

### Task 1: Add Prompt and Pipeline Tests First

- [ ] Update `RAGPromptServiceTest` with a test named `shouldPlaceLongTermMemoryBeforeConversationSummary`.

Expected message order:

```text
0 system
1 user long-term-memory
2 user conversation-summary
3 user history
4 assistant history
5 user dynamic evidence/question
```

- [ ] Update `StreamChatPipelineTest` to mock `LongTermMemoryService`.

Expected behavior:

```text
rewriteWithSplit("current question", recentHistory)
longTermMemoryService.recall("user-1", "rewritten question")
ctx.longTermMemory == recalled memory text
```

- [ ] Run focused tests.

```powershell
mvn -pl bootstrap -am '-Dtest=RAGPromptServiceTest,StreamChatPipelineTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine= ' '-DforkCount=0' test
```

Expected before implementation: compilation or assertion failure because long-term memory fields and service do not exist yet.

---

### Task 2: Add Data Model and Configuration

- [ ] Add `UserLongTermMemoryDO`.

Required fields:

```text
id, userId, memoryType, memoryKey, content, confidence, importance,
sourceConversationId, sourceMessageId, accessCount, lastAccessTime,
status, createTime, updateTime, deleted
```

- [ ] Add `UserLongTermMemoryMapper extends BaseMapper<UserLongTermMemoryDO>`.

- [ ] Add long-term memory config fields to `MemoryProperties`.

Defaults:

```text
longTermEnabled = true
longTermExtractionEnabled = true
longTermRecallLimit = 5
longTermExtractionRecentMessages = 6
longTermMaxContentLength = 300
```

- [ ] Add `long-term-*` config keys to `application.yaml`.

- [ ] Add `t_user_long_term_memory` to `resources/database/schema_pg.sql` and `resources/database/upgrade_v1.2_to_v1.3.sql`.

---

### Task 3: Implement Long-Term Memory Service

- [ ] Add `LongTermMemoryCandidate` record.

Fields:

```java
record LongTermMemoryCandidate(String type,
                               String key,
                               String content,
                               int confidence,
                               int importance) {
}
```

- [ ] Add `LongTermMemoryService`.

Methods:

```java
String recall(String userId, String query);

void extractAsync(String conversationId, String userId, String sourceMessageId);
```

- [ ] Add `JdbcLongTermMemoryService`.

Core behavior:

```text
recall:
  if disabled or blank userId -> null
  load ACTIVE memories
  score by lexical overlap + importance + confidence + access_count
  limit to configured recall limit
  update access_count and last_access_time
  return formatted <long-term-memory> block

extractAsync:
  if disabled/extraction disabled/blank sourceMessageId -> return
  run doExtract in longTermMemoryExecutor

doExtract:
  load recent user/assistant messages for source conversation
  call LLM with long-term-memory-extract.st
  parse JSON memories array
  validate type/key/content
  truncate content to max length
  upsert by user_id + memory_type + memory_key
```

- [ ] Add `longTermMemoryExecutor` to `ThreadPoolExecutorConfig`.

---

### Task 4: Wire Prompt and Pipeline

- [ ] Add `longTermMemory` to `PromptContext`.

- [ ] Add long-term memory layer in `RAGPromptService` before conversation summary.

- [ ] Add `longTermMemory` to `StreamChatContext`.

- [ ] Inject `LongTermMemoryService` into `StreamChatPipeline`.

- [ ] Add `loadLongTermMemory(ctx)` after `rewriteQuery(ctx)` and before `resolveIntents(ctx)`.

- [ ] Pass `ctx.getLongTermMemory()` into `PromptContext`.

---

### Task 5: Wire Async Extraction Trigger

- [ ] Add `LongTermMemoryService` to `StreamChatHandlerParams`.

- [ ] Inject it in `StreamCallbackFactory`.

- [ ] In `StreamChatEventHandler.onComplete`, after successful assistant persistence, call:

```java
longTermMemoryService.extractAsync(conversationId, userId, messageId);
```

- [ ] Do not call extraction in cancel persistence or queue rejection flow.

---

### Task 6: Add Service Tests

- [ ] Add `JdbcLongTermMemoryServiceTest`.

Test cases:

```text
shouldRecallRelevantMemoriesInRankOrder
shouldIgnoreInvalidExtractionJson
shouldMergeDuplicateMemoryByTypeAndKey
```

- [ ] Use Mockito mocks for mapper, message mapper, prompt loader, LLM service, properties, and executor.

Use a direct executor for async tests:

```java
Executor directExecutor = Runnable::run;
```

---

### Task 7: Verify and Commit

- [ ] Run related tests.

```powershell
mvn -pl bootstrap -am '-Dtest=RAGPromptServiceTest,StreamChatPipelineTest,JdbcLongTermMemoryServiceTest,JdbcConversationMemoryStoreTest,JdbcConversationMemorySummaryServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine= ' '-DforkCount=0' test
```

- [ ] Review diff and remove unrelated formatter changes.

```powershell
git diff --stat
git status --short
```

- [ ] Commit implementation.

```powershell
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag bootstrap/src/main/resources/prompt/long-term-memory-extract.st bootstrap/src/main/resources/application.yaml bootstrap/src/test/java/com/nageoffer/ai/ragent/rag resources/database/schema_pg.sql resources/database/upgrade_v1.2_to_v1.3.sql
git commit -m "Add structured long-term memory pipeline"
```
