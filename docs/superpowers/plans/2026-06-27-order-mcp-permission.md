# Order MCP Permission Implementation Plan

## Task 1: Shared MCP Identity Module

- [x] Add `mcp-auth` to the Maven reactor.
- [x] Implement caller identity and HMAC JWT codec.
- [x] Test valid, invalid, expired, issuer and audience behavior.

## Task 2: Ragent Identity Propagation

- [x] Extend MCP server configuration with enabled/auth/audience fields.
- [x] Add per-call transport context and Authorization header customization.
- [x] Pass the authenticated caller explicitly into MCP tool execution.
- [x] Use a system token only for MCP initialization and tool discovery.
- [x] Configure `order_admin_search` as an admin-only tool.
- [x] Add focused client and permission tests.

## Task 3: Order MCP Service

- [x] Add standalone `mcp-order-server` module on port `9100`.
- [x] Add bearer authentication filter and MCP transport context extraction.
- [x] Add fixed JDBC repository queries.
- [x] Implement `order_list_mine`, `order_detail` and `order_admin_search`.
- [x] Add authorization and repository tests.

## Task 4: Database and Integration Data

- [x] Add idempotent order schema and sample order SQL.
- [x] Add optional Ragent intent-node SQL for the three order tools.
- [x] Document database creation, environment variables and startup commands.

## Task 5: Verification

- [x] Run focused `mcp-auth`, bootstrap and order-server tests.
- [x] Build all affected Maven modules.
- [x] Review `git diff --stat` and the full diff.
- [x] Confirm unrelated user changes remain untouched.
- [x] Commit the verified checkpoint.
