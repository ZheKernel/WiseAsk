# Prompt Context Layering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a prompt context layering assembler so semi-stable conversation memory is separated from dynamic evidence and current questions.

**Architecture:** Keep `RAGPromptService` responsible for domain-specific rendering, and add a small `PromptContextAssembler` to flatten stable, semi-stable, raw-history, and ephemeral blocks into provider-compatible `ChatMessage` objects. Only `system`, `user`, and `assistant` roles are emitted.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito, Maven Surefire.

---

## File Structure

- Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContextLayer.java`
  - Immutable value object for rendered context blocks.
  - Defines `Stability` enum: `STABLE`, `SEMI_STABLE`, `EPHEMERAL`.

- Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContextAssembler.java`
  - Converts rendered layers and raw history into ordered `List<ChatMessage>`.
  - Drops blank layer content.
  - Keeps raw history roles unchanged.

- Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java`
  - Render summary as its own semi-stable layer.
  - Render KB/MCP evidence and question as the final ephemeral user layer.
  - Delegate message ordering to `PromptContextAssembler`.

- Modify `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptServiceTest.java`
  - Replace old "summary in final user evidence" assertion.
  - Assert target order: system, semi-stable summary, history, final dynamic user message.
  - Assert no empty summary message is emitted when summary is blank.

---

### Task 1: Update Prompt Test Expectations

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptServiceTest.java`

- [ ] **Step 1: Replace the summary placement test**

Use a test named `shouldEmitConversationSummaryAsSemiStableContextBeforeHistory`:

```java
Assertions.assertEquals(5, messages.size());
Assertions.assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).getRole());
Assertions.assertEquals(ChatMessage.Role.USER, messages.get(1).getRole());
Assertions.assertTrue(messages.get(1).getContent().contains("<conversation-summary>"));
Assertions.assertEquals(ChatMessage.Role.USER, messages.get(2).getRole());
Assertions.assertEquals(ChatMessage.Role.ASSISTANT, messages.get(3).getRole());
Assertions.assertEquals(ChatMessage.Role.USER, messages.get(4).getRole());
Assertions.assertFalse(messages.get(4).getContent().contains("<conversation-summary>"));
Assertions.assertTrue(messages.get(4).getContent().contains("<kb-evidence>"));
Assertions.assertTrue(messages.get(4).getContent().contains("<single-question>"));
```

- [ ] **Step 2: Add a blank-summary regression test**

Use a test named `shouldSkipSemiStableContextWhenSummaryIsBlank`:

```java
Assertions.assertEquals(2, messages.size());
Assertions.assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).getRole());
Assertions.assertEquals(ChatMessage.Role.USER, messages.get(1).getRole());
Assertions.assertFalse(messages.get(1).getContent().contains("<conversation-summary>"));
Assertions.assertTrue(messages.get(1).getContent().contains("<kb-evidence>"));
Assertions.assertTrue(messages.get(1).getContent().contains("<single-question>"));
```

- [ ] **Step 3: Run the focused test and verify failure**

Run:

```powershell
mvn -pl bootstrap -am '-Dtest=RAGPromptServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine= ' '-DforkCount=0' test
```

Expected before implementation: at least one assertion fails because summary is still merged into the final user evidence block.

---

### Task 2: Add Context Layer Types

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContextLayer.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContextAssembler.java`

- [ ] **Step 1: Add `PromptContextLayer`**

Create a record with validation-free construction because callers already control content:

```java
record PromptContextLayer(String name,
                          PromptContextLayer.Stability stability,
                          ChatMessage.Role role,
                          String content) {

    enum Stability {
        STABLE,
        SEMI_STABLE,
        EPHEMERAL
    }
}
```

- [ ] **Step 2: Add `PromptContextAssembler`**

Create a package-private final class:

```java
final class PromptContextAssembler {

    List<ChatMessage> assemble(List<PromptContextLayer> leadingLayers,
                               List<ChatMessage> history,
                               PromptContextLayer finalLayer) {
        List<ChatMessage> messages = new ArrayList<>();
        appendLayers(messages, leadingLayers);
        appendHistory(messages, history);
        appendLayer(messages, finalLayer);
        return messages;
    }

    private void appendLayers(List<ChatMessage> messages, List<PromptContextLayer> layers) {
        if (layers == null || layers.isEmpty()) {
            return;
        }
        layers.forEach(layer -> appendLayer(messages, layer));
    }

    private void appendHistory(List<ChatMessage> messages, List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        history.stream()
                .filter(message -> message != null && StrUtil.isNotBlank(message.getContent()))
                .forEach(messages::add);
    }

    private void appendLayer(List<ChatMessage> messages, PromptContextLayer layer) {
        if (layer == null || StrUtil.isBlank(layer.content())) {
            return;
        }
        messages.add(new ChatMessage(layer.role(), layer.content()));
    }
}
```

- [ ] **Step 3: Run compile/test**

Run the focused Maven command from Task 1. Expected: code compiles, prompt tests still fail until `RAGPromptService` is wired.

---

### Task 3: Wire RAG Prompt Assembly

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java`

- [ ] **Step 1: Add assembler field**

Instantiate a private final assembler:

```java
private final PromptContextAssembler contextAssembler = new PromptContextAssembler();
```

- [ ] **Step 2: Replace manual message ordering**

Build leading layers:

```java
List<PromptContextLayer> leadingLayers = new ArrayList<>();
leadingLayers.add(new PromptContextLayer("system",
        PromptContextLayer.Stability.STABLE,
        ChatMessage.Role.SYSTEM,
        systemPrompt));
leadingLayers.add(buildConversationSummaryLayer(context));
```

Build final layer from dynamic evidence and question:

```java
PromptContextLayer finalLayer = new PromptContextLayer("dynamic-request",
        PromptContextLayer.Stability.EPHEMERAL,
        ChatMessage.Role.USER,
        userContent);
return contextAssembler.assemble(leadingLayers, history, finalLayer);
```

- [ ] **Step 3: Split summary out of `buildEvidenceBody`**

Add:

```java
private PromptContextLayer buildConversationSummaryLayer(PromptContext context) {
    if (StrUtil.isBlank(context.getConversationSummary())) {
        return null;
    }
    String content = renderSection("summary-wrapper", Map.of(
            "content", context.getConversationSummary().trim()
    ));
    return new PromptContextLayer("conversation-summary",
            PromptContextLayer.Stability.SEMI_STABLE,
            ChatMessage.Role.USER,
            content);
}
```

Remove summary rendering from `buildEvidenceBody`.

- [ ] **Step 4: Verify focused tests pass**

Run:

```powershell
mvn -pl bootstrap -am '-Dtest=RAGPromptServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine= ' '-DforkCount=0' test
```

Expected: `RAGPromptServiceTest` passes.

---

### Task 4: Regression Verification and Commit

**Files:**
- Verify all touched Java tests.

- [ ] **Step 1: Run related tests**

Run:

```powershell
mvn -pl bootstrap -am '-Dtest=RAGPromptServiceTest,StreamChatPipelineTest,JdbcConversationMemoryStoreTest,JdbcConversationMemorySummaryServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine= ' '-DforkCount=0' test
```

Expected: all specified tests pass.

- [ ] **Step 2: Review diff**

Run:

```powershell
git diff --stat
git diff -- bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptServiceTest.java
```

Expected: only prompt layering implementation and tests are changed.

- [ ] **Step 3: Commit implementation**

Run:

```powershell
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContextLayer.java bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContextAssembler.java bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptServiceTest.java
git commit -m "Layer RAG prompt context for cache-aware assembly"
```
