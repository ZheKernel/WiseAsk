-- Order MCP service schema.
-- Execute this file against the separate ragent_order database.

CREATE TABLE IF NOT EXISTS t_order (
    id           VARCHAR(20)    NOT NULL PRIMARY KEY,
    order_no     VARCHAR(32)    NOT NULL,
    user_id      VARCHAR(20)    NOT NULL,
    product_name VARCHAR(128)   NOT NULL,
    quantity     INTEGER        NOT NULL,
    unit_price   NUMERIC(12, 2) NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL,
    status       VARCHAR(32)    NOT NULL,
    create_time  TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pay_time     TIMESTAMP,
    update_time  TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_order_no UNIQUE (order_no),
    CONSTRAINT ck_order_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_status CHECK (
        status IN ('PENDING', 'PAID', 'SHIPPED', 'COMPLETED', 'CANCELLED')
    )
);

CREATE INDEX IF NOT EXISTS idx_order_user_time
    ON t_order (user_id, create_time DESC);

CREATE INDEX IF NOT EXISTS idx_order_status_time
    ON t_order (status, create_time DESC);

COMMENT ON TABLE t_order IS 'Order MCP service order data';
COMMENT ON COLUMN t_order.user_id IS 'Ragent user ID that owns the order';
