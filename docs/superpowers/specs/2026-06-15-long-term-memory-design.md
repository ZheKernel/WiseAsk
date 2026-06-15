# Long-Term Memory Design

## Goal

Add a real long-term memory system to the RAG chat pipeline so reusable user preferences, project context, and durable constraints can survive across conversations and be injected through the semi-stable prompt layer added in the prompt context layering phase.

## Problem

The project currently has conversation memory, but it is session-scoped:

```text
t_message -> raw conversation messages
t_conversation_summary -> compressed summary for one conversation
```

That solves short-term context growth, but it does not solve cross-conversation continuity. If the user repeatedly says they prefer a Java/Spring implementation style, are preparing for interviews, or are working on a specific RAG project, every new conversation starts cold unless the user repeats that context.

This should not be implemented as a generic user profile blob. A strong long-term memory system needs extraction, filtering, merge/update, retrieval, and prompt injection boundaries.

## Design

Implement structured long-term memory with a closed lifecycle:

```text
assistant response completes
    -> async memory extraction
    -> candidate validation
    -> upsert/merge by user + type + key
    -> later chat loads relevant memories
    -> prompt injects memories as semi-stable context
```

The first version uses relational storage and deterministic ranking. It does not require vector retrieval. The schema and service boundaries leave room to add embedding-based recall later.

## Memory Types

Use explicit memory types so the system can reason about what it stores and how it should be used:

- `PREFERENCE`: user preferences that should influence style, tooling, language, or defaults.
- `PROJECT`: durable context about a project, system, or ongoing work.
- `CONSTRAINT`: explicit long-lived rules, requirements, or boundaries.
- `FACT`: stable factual context the user directly provided and that is useful later.

Examples that should be stored:

- User prefers Java/Spring style implementations.
- User is optimizing a RAG project for interview discussion.
- User wants prompt cache, long-term memory, and observability improvements.
- Current project uses a staged RAG pipeline with intent recognition and retrieval.

Examples that should not be stored:

- Passwords, API keys, tokens, private identifiers, or secrets.
- Sensitive personal attributes.
- One-off task details with no likely future reuse.
- Inferred personality judgments that the user did not state.

## Data Model

Add `t_user_long_term_memory`:

```text
id
user_id
memory_type
memory_key
content
confidence
importance
source_conversation_id
source_message_id
access_count
last_access_time
status
create_time
update_time
deleted
```

`memory_key` is a normalized merge key produced by extraction or deterministic fallback. It prevents storing repeated memories like "prefers Java" many times.

`confidence` and `importance` are integers from 1 to 5. They allow ranking without adding a vector store in the first version.

`status` starts with:

- `ACTIVE`: eligible for recall.
- `ARCHIVED`: retained but not recalled.

## Extraction

Extraction runs asynchronously after an assistant message is persisted. It receives a compact view of the latest user and assistant exchange plus existing memories for merge awareness.

The LLM extraction prompt must return JSON:

```json
{
  "memories": [
    {
      "type": "PREFERENCE",
      "key": "preferred_backend_stack",
      "content": "User prefers Java/Spring style implementations.",
      "confidence": 5,
      "importance": 4
    }
  ]
}
```

If extraction fails or JSON is invalid, the chat flow must remain successful. Long-term memory is opportunistic and must never block the user response.

## Upsert And Merge

Long-term memory is merged by:

```text
user_id + memory_type + memory_key
```

If no existing record exists, insert a new active memory.

If an existing record exists, update only when the new candidate has useful content:

- Replace content when the new content is more specific.
- Raise confidence or importance when the new candidate is stronger.
- Keep source fields pointing to the latest supporting message.
- Preserve access count.

The first version uses simple deterministic merge rules. It does not need another LLM call for merge.

## Retrieval

Before final prompt assembly, load active memories for the current user and select the top memories.

First-version ranking:

```text
score = importance * 10 + confidence * 5 + access_count
```

Then prefer memories whose content or key has lexical overlap with the current rewritten question. This keeps implementation simple and testable.

Default limit:

```text
rag.memory.long-term-recall-limit: 5
```

Each recalled memory increments `access_count` and updates `last_access_time`.

## Prompt Injection

Extend the prompt context model with `longTermMemory`.

Final message order:

```text
system
long-term memory
conversation summary
recent raw history
dynamic evidence + current question
```

Long-term memory belongs in `SEMI_STABLE` context. It should not be injected into the system prompt, and it should not be mixed with KB evidence.

This continues the context layering design:

- System prompt stays stable.
- Long-term memory changes less frequently than current question or retrieval evidence.
- Retrieval evidence and current question stay dynamic and late.

## Components

### `LongTermMemoryProperties`

Configuration for:

- enabled
- extraction enabled
- recall limit
- extraction recent message window
- max memory content length

### `LongTermMemoryService`

Main interface:

```text
recall(userId, query)
extractAsync(conversationId, userId, sourceMessage)
```

### `JdbcLongTermMemoryService`

JDBC/MyBatis-Plus implementation:

- Loads active memories for ranking.
- Parses extraction JSON.
- Upserts by user/type/key.
- Updates access metadata on recall.

### `LongTermMemoryFormatter`

Formats recalled memories into a compact prompt section:

```text
<long-term-memory>
- [PREFERENCE] User prefers Java/Spring style implementations.
- [PROJECT] User is optimizing a RAG system for interview discussion.
</long-term-memory>
```

### Pipeline Changes

`StreamChatPipeline` loads long-term memories after query rewrite, because rewritten question is a better recall key than the raw user question.

`PromptContext` carries formatted long-term memory into `RAGPromptService`.

`RAGPromptService` emits long-term memory before conversation summary.

`StreamChatEventHandler` or `ConversationMemoryService.append` triggers extraction after assistant messages are saved.

## Error Handling

- Recall failure returns empty memory context.
- Extraction failure is logged and ignored.
- Invalid JSON extraction response is ignored.
- Memory upsert failures do not affect chat response.

## Non-Goals

- No vector search for memories in this phase.
- No manual memory management UI in this phase.
- No sensitive-data classifier beyond prompt rules and conservative filtering.
- No deletion or user memory editing API in this phase.

## Tests

Add focused tests for:

- Prompt assembly places long-term memory before conversation summary.
- Recall ranks memories by relevance, importance, confidence, and access count.
- Blank or disabled long-term memory does not change prompt shape.
- Extraction ignores invalid JSON.
- Upsert merges duplicate memory keys instead of inserting duplicates.
- Pipeline passes recalled memory into prompt context.

## Interview Framing

This phase separates short-term conversation memory from long-term user memory. Conversation summary compresses one chat session. Long-term memory extracts reusable facts, preferences, project context, and durable constraints across sessions.

The important architectural point is the memory lifecycle: extraction, filtering, merge, recall, and semi-stable prompt injection. This is closer to context engineering than traditional RAG, because the system decides which non-document context belongs in the model input and where it belongs in the prompt volatility hierarchy.
