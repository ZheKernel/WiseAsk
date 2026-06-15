# Prompt Context Layering Design

## Goal

Build a prompt context layering architecture for the RAG chat pipeline so stable context is kept ahead of volatile context, improving cache friendliness and creating a clean insertion point for long-term memory.

## Problem

The current RAG prompt is assembled as:

```text
system -> recent history -> conversation summary + retrieval evidence + current question
```

This has three issues:

- Conversation summary is mixed with retrieval evidence and the current question, so a low-frequency context block is bundled into a high-frequency user message.
- Recent history appears before all evidence, so the cacheable prefix changes as the conversation window moves.
- Future long-term memory has no explicit place to enter the prompt without further mixing unrelated context types.

The previous session-summary implementation still reduces token growth, but it does not create a reliable KV-cache story because it changes prompt length and does not control where stable and volatile blocks appear.

## Design

Introduce an explicit context layering model:

```text
StableContext
SemiStableContext
EphemeralContext
```

The first implementation keeps provider compatibility by continuing to use the existing `system`, `user`, and `assistant` roles only. Layer identity is represented by structured section text, not custom roles.

### Stable Context

Stable context contains the system prompt and fixed behavior rules. It should be the first message and should change as rarely as possible.

Initial source:

- Prompt template selected by `RAGPromptService.buildSystemPrompt`

### Semi-Stable Context

Semi-stable context contains information that changes less frequently than each request but more often than the system prompt.

Initial source:

- Conversation summary

Reserved future source:

- Long-term memory snippets
- Stable intent rules or tool usage hints

It is emitted as a separate `user` message before recent raw history and retrieval evidence. This separates low-frequency memory from high-frequency evidence and question blocks.

### Ephemeral Context

Ephemeral context contains request-specific material.

Initial sources:

- Recent user/assistant history
- KB evidence
- MCP evidence
- Rewritten question
- Split sub-questions

Recent history keeps its original roles because it represents actual dialogue. Retrieval evidence and the current question stay in a final dynamic `user` message.

## Target Message Order

```text
system: stable instructions
user: semi-stable conversation memory
user/assistant: recent raw history
user: dynamic evidence + current question
```

When there is no conversation summary, the semi-stable memory message is omitted.

## Components

### `PromptContextLayer`

A small value object representing one assembled prompt layer:

- `name`: stable machine-readable layer name
- `stability`: `STABLE`, `SEMI_STABLE`, or `EPHEMERAL`
- `role`: output `ChatMessage.Role`
- `content`: rendered layer body

### `PromptContextAssembler`

Responsible for flattening already-rendered layers and raw history into final `List<ChatMessage>` order.

Responsibilities:

- Preserve system as the first message.
- Emit semi-stable context before recent history.
- Keep raw history roles unchanged.
- Emit dynamic evidence and question last.
- Drop blank layers.

### `RAGPromptService`

Keeps business-specific rendering:

- Select system prompt.
- Render conversation summary section.
- Render KB/MCP evidence.
- Render single-question or multi-question blocks.
- Delegate final ordering to `PromptContextAssembler`.

## Non-Goals

- Do not introduce custom message roles.
- Do not implement long-term memory in this phase.
- Do not implement token budget pruning in this phase.
- Do not claim KV-cache hit rate must rise. This phase makes prompt layout cache-aware and measurable; actual hit rate depends on provider behavior and request mix.

## Tests

Add or update prompt tests to verify:

- System prompt is always the first message.
- Summary is emitted as a separate semi-stable `user` message.
- Summary is before recent raw history and dynamic evidence.
- Recent history keeps original `user` and `assistant` roles.
- KB/MCP evidence and current question remain in the final dynamic `user` message.
- No blank semi-stable message is emitted when summary is absent.

## Interview Framing

This change moves the project from ad hoc prompt string concatenation to a context-engineering layer. The important point is not "I reordered strings"; it is that context is classified by volatility. Stable and semi-stable context gets a controlled position, dynamic retrieval and current user input are kept later, and future long-term memory can be added without polluting the system prompt or mixing with evidence.
