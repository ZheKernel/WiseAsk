-- Execute against the database used by mcp-order-server.

DELETE FROM t_order
WHERE order_no LIKE 'EVAL-U____-O______';
