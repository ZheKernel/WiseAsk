# Order MCP Quick Start

## 1. Create the Separate Database

```powershell
createdb -h 127.0.0.1 -p 5432 -U postgres -W ragent_order
```

Initialize the order table and sample rows:

```powershell
psql -h 127.0.0.1 -p 5432 -U postgres -W -d ragent_order `
  -v ON_ERROR_STOP=1 -1 `
  -f .\resources\database\order_service_schema.sql

psql -h 127.0.0.1 -p 5432 -U postgres -W -d ragent_order `
  -v ON_ERROR_STOP=1 -1 `
  -f .\resources\database\order_service_sample_data.sql
```

The sample orders use these Ragent user IDs:

```text
2001523723396309001
2001523723396309002
2001523723396309003
```

Replace those values in the sample SQL when your actual `t_user.id` values are
different.

## 2. Add Order Intent Nodes

Execute this script against the main `ragent` database:

```powershell
psql -h 127.0.0.1 -p 5432 -U postgres -W -d ragent `
  -v ON_ERROR_STOP=1 -1 `
  -f .\resources\database\order_mcp_intent_data.sql
```

## 3. Set the Shared Development Secret

Use the same value in the order-service and Ragent terminals. It must contain
at least 32 bytes.

```powershell
$env:RAGENT_MCP_SHARED_SECRET="<replace-with-a-random-32-byte-secret>"
```

Do not store the real value in `application.yml` or commit it.

## 4. Start the Order MCP Service

```powershell
$env:RAGENT_MCP_SHARED_SECRET="<same-secret>"
$env:ORDER_DB_URL="jdbc:postgresql://127.0.0.1:5432/ragent_order"
$env:ORDER_DB_USERNAME="postgres"
$env:ORDER_DB_PASSWORD="<your-postgres-password>"

.\mvnw.cmd -pl mcp-order-server -am spring-boot:run
```

The MCP endpoint is:

```text
http://localhost:9100/mcp
```

## 5. Start Ragent

In another terminal:

```powershell
$env:RAGENT_MCP_SHARED_SECRET="<same-secret>"
$env:ORDER_MCP_ENABLED="true"

.\mvnw.cmd -pl bootstrap -am spring-boot:run
```

`ORDER_MCP_ENABLED` defaults to `false`, so an existing Ragent deployment is
not forced to connect to the optional order service.

Ragent initializes the order MCP server using a short-lived `system` token.
Actual tool calls use a new token containing the current Ragent user ID and
role.

## 6. Verify Permissions

Log in as a normal user whose ID appears in the sample data:

```text
查询我的订单
查询订单 ORD-20260601-001 的详情
查询所有用户订单
```

The first two queries can only return that user's rows. The third query must
not execute `order_admin_search`.

Log in as `admin`:

```text
查询所有用户订单
查询用户 2001523723396309002 的订单
```

The administrator can use `order_admin_search` and see rows across users.

## Production Notes

- Create a dedicated PostgreSQL login with `SELECT` permission only.
- Replace the shared HMAC secret with a managed secret or enterprise IAM.
- Restrict port `9100` to internal service traffic.
- Keep query limits and audit logging enabled.
