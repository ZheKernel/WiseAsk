# Order MCP Permission Design

## Goal

Add a standalone PostgreSQL-backed order MCP service to Ragent.

- Administrators can query orders across all users.
- Normal users can query only rows whose `user_id` matches their authenticated Ragent user ID.
- User identity must not be accepted from LLM-generated tool arguments.
- Direct calls that bypass Ragent must still be authenticated and authorized by the order MCP service.

## Current Gap

Ragent already checks whether the current user may call a tool through
`RagResourcePermissionService.canCallMcpTool`. The remote MCP client currently
forwards only LLM-extracted tool arguments, so the remote service cannot
distinguish administrators from normal users or enforce row ownership.

## Module Layout

### `mcp-auth`（历史设计）

A lightweight shared module containing:

- `McpCallerIdentity`
- HMAC-SHA256 JWT issue and verification
- issuer, audience, expiry and role validation

It has no dependency on Ragent login state or the order database.

> This module was removed on 2026-06-30 after the OAuth migration. The verified
> caller model now belongs to `mcp-order-server`; each service owns the Scope
> constants needed at its protocol boundary.

### `mcp-order-server`

A standalone Spring Boot service on port `9100` containing:

- MCP HTTP transport
- bearer-token authentication filter
- order query tools
- JDBC repository with fixed parameterized SQL
- PostgreSQL datasource configuration

The service owns the `ragent_order` database. Ragent does not connect to that
database directly.

## Identity Flow

1. The user authenticates to Ragent with the existing Sa-Token flow.
2. Ragent captures `LoginUser` before executing an MCP tool.
3. Ragent issues a short-lived internal JWT with:
   - `sub`: Ragent user ID
   - `username`
   - `role`: `admin` or `user`
   - `iss`: configured issuer
   - `aud`: configured MCP server audience
   - `iat`, `nbf`, `exp`, `jti`
4. The MCP HTTP transport sends `Authorization: Bearer <token>`.
5. The order service validates signature, issuer, audience and time claims.
6. The verified identity is added to MCP transport context.
7. Tool handlers authorize the operation and apply server-side row filters.

Startup-time MCP initialization and tool discovery use a short-lived
`system` identity. The `system` role may initialize and list tools but may not
execute order query tools.

> This original HMAC design was superseded on 2026-06-30 by
> `2026-06-30-auth-server-mcp-delegation-design.md`.

The current implementation uses Ragent `private_key_jwt` client authentication,
an independent Auth Server signing key, and Order MCP JWKS verification. No
shared HMAC secret remains between Ragent and Order MCP.

## Tools

### `order_list_mine`

Available to `user` and `admin`.

- Does not accept a user ID.
- Always queries by the verified caller ID.
- Supports status, date range and a bounded result limit.

### `order_detail`

Available to `user` and `admin`.

- Accepts only an order number.
- Administrators may view any order.
- Normal users query by both order number and verified caller ID.
- An inaccessible order is returned as not found to avoid existence leaks.

### `order_admin_search`

Available only to administrators.

- May accept an optional user ID.
- Supports status, date range and a bounded result limit.
- Listed in Ragent's `admin-only-tool-ids` configuration.

## Database Rules

- No generic SQL execution MCP tool.
- Every query is fixed, parameterized and read-only.
- Query limits are clamped to `1..100`.
- The service should use a dedicated read-only PostgreSQL account outside local
  development.
- Sample data contains no payment credentials or unnecessary personal data.
- Application logs record actor, role, tool, filters, result count and elapsed
  time without logging bearer tokens.

## Failure Behavior

- Missing, malformed, expired, wrong-issuer or wrong-audience tokens return
  HTTP 401 before MCP request handling.
- Authenticated users without tool permission receive an MCP error result.
- Database failures return a generic MCP error and retain detailed server logs.
- Order-service startup or discovery failure does not prevent the main Ragent
  application from starting; the remote tool is skipped as in the existing MCP
  client behavior.

## Test Matrix

- JWT round trip and invalid signature.
- Expired, wrong issuer and wrong audience tokens.
- Normal user list query always binds caller ID.
- Normal user cannot read another user's order detail.
- Administrator can list and inspect all users' orders.
- `system` identity cannot execute query tools.
- Ragent attaches a user token to authenticated tool calls.
- Ragent uses a service token for startup discovery.
- Ragent rejects `order_admin_search` for normal users before remote execution.

## Out of Scope

- Order creation, update, cancellation or payment operations.
- Exposing arbitrary SQL.
- Sharing frontend Sa-Token credentials with MCP services.
- A centralized OAuth authorization server. The internal JWT boundary is a
  project-local stepping stone and can later be replaced by enterprise IAM.
