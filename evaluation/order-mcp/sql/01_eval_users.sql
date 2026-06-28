-- Execute against the main Ragent database.
-- Creates one evaluation admin and 100 deterministic evaluation users.

INSERT INTO t_user
    (id, username, password, role, avatar, create_time, update_time, deleted)
VALUES
    ('2199999999999999999', 'eval_admin', 'Eval@123', 'admin', NULL,
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (id) DO UPDATE SET
    username = EXCLUDED.username,
    password = EXCLUDED.password,
    role = EXCLUDED.role,
    update_time = CURRENT_TIMESTAMP,
    deleted = 0;

INSERT INTO t_user
    (id, username, password, role, avatar, create_time, update_time, deleted)
SELECT
    (2100000000000000000::numeric + user_no)::text,
    'eval_user_' || lpad(user_no::text, 3, '0'),
    'Eval@123',
    'user',
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
FROM generate_series(1, 100) AS users(user_no)
ON CONFLICT (id) DO UPDATE SET
    username = EXCLUDED.username,
    password = EXCLUDED.password,
    role = EXCLUDED.role,
    update_time = CURRENT_TIMESTAMP,
    deleted = 0;
