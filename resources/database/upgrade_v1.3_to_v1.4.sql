-- Ragent v1.3 -> v1.4
-- Add knowledge-base ownership and visibility scope for RAG permission isolation.

ALTER TABLE t_knowledge_base ADD COLUMN IF NOT EXISTS owner_user_id VARCHAR(20);
ALTER TABLE t_knowledge_base ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'GLOBAL';

UPDATE t_knowledge_base
SET scope = 'GLOBAL'
WHERE scope IS NULL OR scope = '';

CREATE INDEX IF NOT EXISTS idx_kb_scope_owner ON t_knowledge_base (scope, owner_user_id);

COMMENT ON COLUMN t_knowledge_base.owner_user_id IS '知识库所有者用户ID';
COMMENT ON COLUMN t_knowledge_base.scope IS '知识库作用域：GLOBAL/PERSONAL';
