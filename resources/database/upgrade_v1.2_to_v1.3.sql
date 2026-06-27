-- Add structured user long-term memory.

CREATE TABLE IF NOT EXISTS t_user_long_term_memory (
    id                     VARCHAR(20)  NOT NULL PRIMARY KEY,
    user_id                VARCHAR(20)  NOT NULL,
    memory_type            VARCHAR(32)  NOT NULL,
    memory_key             VARCHAR(128) NOT NULL,
    content                TEXT         NOT NULL,
    confidence             INTEGER      DEFAULT 3,
    importance             INTEGER      DEFAULT 3,
    source_conversation_id VARCHAR(20),
    source_message_id      VARCHAR(20),
    access_count           INTEGER      DEFAULT 0,
    last_access_time       TIMESTAMP,
    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    create_time            TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time            TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    deleted                SMALLINT     DEFAULT 0,
    CONSTRAINT uk_user_memory_key UNIQUE (user_id, memory_type, memory_key, deleted)
);

CREATE INDEX IF NOT EXISTS idx_user_memory_active
    ON t_user_long_term_memory (user_id, status, deleted, update_time);
