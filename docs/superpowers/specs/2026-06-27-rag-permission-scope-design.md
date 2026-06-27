# RAG Permission Scope Design

## Goal

Add a first-version permission control layer for the RAG application so normal users can build personal knowledge bases and memories, admins can maintain global knowledge bases, and chat retrieval or MCP execution only uses resources authorized for the current user.

## Problem

The current project has login state, `admin/user` roles, and `UserContext`, but knowledge bases are effectively global from the RAG pipeline's point of view. `KnowledgeBaseDO` stores `createdBy` and `updatedBy`, which are audit fields, not access-control fields. Vector global search currently enumerates all active KB collections, so a future user-uploaded private KB would be available to unrelated users unless retrieval is constrained.

The important boundary is:

```text
admin management visibility != ordinary chat retrieval visibility
```

Admins may manage all knowledge bases, but an admin's normal chat should not automatically retrieve every user's personal documents. Personal data must enter prompt context only through an explicit authorized path.

## Design

Introduce a lightweight scope model before adding full ACL:

- `GLOBAL`: uploaded by an admin and available to every user's chat.
- `PERSONAL`: owned by a user and available only to that user in ordinary chat.

The first version does not implement shared users, departments, teams, or tenant ACL. Those are extension points after the core boundary is correct.

## Knowledge Base Rules

Creation:

- A normal user creates `PERSONAL` knowledge bases owned by their `userId`.
- An admin can create `GLOBAL` knowledge bases.
- An admin can also create a `PERSONAL` knowledge base for a specific owner later, but this phase does not need the UI unless the API shape already makes it easy.

Management:

- A normal user can list, read, update, upload documents to, and delete only their own `PERSONAL` knowledge bases.
- An admin can list and manage all knowledge bases in the management surface.
- Disabled or deleted KBs are never retrievable.

Chat retrieval:

```text
authorized KBs for ordinary chat =
    GLOBAL KBs
    + PERSONAL KBs where owner_user_id == current user id
```

Admin chat uses the same rule by default. Admin-only all-user retrieval would require a separate audited mode and is out of scope for this phase.

## MCP Rules

Add a lightweight MCP tool policy layer:

- `GLOBAL`: callable by all authenticated users.
- `ADMIN_ONLY`: callable only by admins.
- `PERSONAL`: callable only by the owning user.

The policy check happens before executing any MCP tool. If a tool is denied, the pipeline should treat it as unavailable rather than asking the model to self-police. Denials should be auditable, but they should not break chat if other evidence is available.

## Long-Term Memory Rules

The current long-term memory table already uses `user_id`. Keep recall scoped to the current user only. Admin management of user memory is not part of this phase.

## Data Model

Extend `t_knowledge_base`:

```text
owner_user_id VARCHAR(20)
scope VARCHAR(32) NOT NULL DEFAULT 'GLOBAL'
```

Rules:

- Existing KBs migrate to `GLOBAL` to preserve current behavior.
- New user-created KBs set `scope = PERSONAL` and `owner_user_id = currentUserId`.
- New admin-created global KBs set `scope = GLOBAL` and `owner_user_id = currentUserId` for audit.

Add `t_mcp_tool_policy` only if the existing MCP configuration has no suitable storage point:

```text
id
tool_id
scope
allowed_role
owner_user_id
enabled
create_time
update_time
deleted
```

The first implementation may use configuration-backed default policies if database CRUD is too large for this phase, but the authorization service API should not expose that storage choice to the RAG pipeline.

## Service Boundaries

Add a focused authorization boundary:

```text
RagResourcePermissionService
    listRetrievableCollections(user)
    canManageKnowledgeBase(user, kb)
    canUploadDocument(user, kb)
    canCallMcpTool(user, toolId)
```

The RAG pipeline should not construct permission predicates directly. Retrieval channels and MCP execution should call this boundary or receive authorized resource lists from context.

## Pipeline Integration

The chat pipeline should resolve the current user once and carry enough identity into retrieval:

```text
StreamChatPipeline
  -> RetrievalEngine
     -> SearchContext(userId, role, authorizedCollections)
        -> IntentDirectedSearchChannel
        -> VectorGlobalSearchChannel
     -> MCP execution policy check
```

Prompt assembly receives only already-authorized chunks and MCP results.

## Error Handling

- Unauthorized KB management requests return a client error.
- Retrieval against no authorized KBs returns empty KB context.
- Unauthorized MCP tools are skipped or represented as unavailable; the model does not receive private tool output.
- Permission service failures should fail closed for data access. Chat may continue with no restricted evidence.

## Tests

Cover at least:

- User-created KB defaults to `PERSONAL` and current `owner_user_id`.
- Admin-created KB can be `GLOBAL`.
- User listing only returns global plus own personal KBs, while admin management listing returns all.
- Vector global retrieval only enumerates authorized collections.
- Intent-directed retrieval skips KB intents whose collection is not authorized.
- MCP execution does not call denied tools.
- Prompt context never contains denied KB chunks or MCP results.

## Completed Application Integration

The management application uses the same backend authorization model:

- All authenticated users can open the knowledge-base workspace.
- Admin-only routes remain protected individually.
- Normal users can manage their own `PERSONAL` knowledge bases.
- `GLOBAL` knowledge bases are visible but read-only for normal users.
- Document and chunk mutation controls are hidden when the current user cannot manage the KB.
- Chunk read and mutation APIs enforce view/manage permission independently, so callers cannot bypass document checks through chunk endpoints.
- `/rag/settings` is admin-only. Authenticated users load upload limits and enabled embedding models from `/rag/capabilities`, which does not expose provider URLs or API keys.
- The knowledge workspace uses a mobile drawer navigation so the sidebar does not compress the main content.

The database includes `idx_kb_scope_owner(scope, owner_user_id)` for permission-scoped list and retrieval queries.

## Out Of Scope

- Department, team, tenant, and per-user shared ACL.
- Admin audited impersonation or all-user retrieval mode.
- UI for editing MCP policies.
- Admin management of user long-term memory.
