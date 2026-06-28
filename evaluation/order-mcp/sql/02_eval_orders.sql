-- Execute against the database used by mcp-order-server.
-- Creates 1000 deterministic orders for each of the 100 evaluation users.

WITH generated AS (
    SELECT
        user_no,
        order_index,
        (user_no - 1) * 1000 + order_index AS serial_no,
        CASE order_index % 5
            WHEN 0 THEN 'PENDING'
            WHEN 1 THEN 'PAID'
            WHEN 2 THEN 'SHIPPED'
            WHEN 3 THEN 'COMPLETED'
            ELSE 'CANCELLED'
        END AS order_status,
        TIMESTAMP '2026-01-01 00:00:00'
            + ((order_index - 1) % 180) * INTERVAL '1 day'
            + ((user_no - 1) % 24) * INTERVAL '1 hour' AS created_at
    FROM generate_series(1, 100) AS users(user_no)
    CROSS JOIN generate_series(1, 1000) AS orders(order_index)
),
priced AS (
    SELECT
        *,
        1 + order_index % 5 AS quantity_value,
        (10 + order_index % 91)::numeric(12, 2) AS unit_price_value
    FROM generated
)
INSERT INTO t_order
    (id, order_no, user_id, product_name, quantity, unit_price, total_amount,
     status, create_time, pay_time, update_time, deleted)
SELECT
    (3100000000000000000::numeric + serial_no)::text,
    'EVAL-U' || lpad(user_no::text, 4, '0')
        || '-O' || lpad(order_index::text, 6, '0'),
    (2100000000000000000::numeric + user_no)::text,
    'EVAL_OWNER_' || lpad(user_no::text, 4, '0')
        || '_PRODUCT_' || lpad((1 + order_index % 50)::text, 3, '0'),
    quantity_value,
    unit_price_value,
    quantity_value * unit_price_value,
    order_status,
    created_at,
    CASE
        WHEN order_status IN ('PAID', 'SHIPPED', 'COMPLETED')
            THEN created_at + INTERVAL '1 hour'
        ELSE NULL
    END,
    created_at,
    0
FROM priced
ON CONFLICT (order_no) DO UPDATE SET
    user_id = EXCLUDED.user_id,
    product_name = EXCLUDED.product_name,
    quantity = EXCLUDED.quantity,
    unit_price = EXCLUDED.unit_price,
    total_amount = EXCLUDED.total_amount,
    status = EXCLUDED.status,
    create_time = EXCLUDED.create_time,
    pay_time = EXCLUDED.pay_time,
    update_time = EXCLUDED.update_time,
    deleted = 0;
