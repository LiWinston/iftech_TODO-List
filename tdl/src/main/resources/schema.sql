-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;
-- Optional: trigram for fuzzy/ILIKE acceleration
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Main table storing TODO items and embeddings in the same row
CREATE TABLE IF NOT EXISTS todo_item (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    priority_score NUMERIC(20,6) NOT NULL DEFAULT 0,
    priority_label TEXT,
    category_id TEXT,
    priority_level_id TEXT, -- 引用用户级优先级层级（priority_level.id）
    status SMALLINT NOT NULL DEFAULT 0, -- 0=ACTIVE,1=COMPLETED,2=TRASHED
    trash_purge_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    -- LangChain4j PgVectorEmbeddingStore expected columns
    embedding VECTOR(384),
    text TEXT,        -- concatenated content for semantic storage
  metadata JSONB,   -- dynamic metadata for filtering
  embedding_id TEXT -- compatibility alias if library expects embedding_id
);

-- Ensure columns exist when table was created earlier without them
ALTER TABLE todo_item ADD COLUMN IF NOT EXISTS embedding VECTOR(384);
ALTER TABLE todo_item ADD COLUMN IF NOT EXISTS text TEXT;
ALTER TABLE todo_item ADD COLUMN IF NOT EXISTS metadata JSONB;
ALTER TABLE todo_item ADD COLUMN IF NOT EXISTS trash_purge_at TIMESTAMP NULL;
ALTER TABLE todo_item ADD COLUMN IF NOT EXISTS embedding_id TEXT;
ALTER TABLE todo_item ADD COLUMN IF NOT EXISTS priority_level_id TEXT;
UPDATE todo_item SET embedding_id = id WHERE embedding_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_todo_item_embedding_id ON todo_item(embedding_id);

-- Updated_at trigger
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tg_todo_item_updated_at ON todo_item;
CREATE TRIGGER tg_todo_item_updated_at
BEFORE UPDATE ON todo_item
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Keep embedding_id in sync with id
CREATE OR REPLACE FUNCTION set_embedding_id()
RETURNS TRIGGER AS $$
BEGIN
  NEW.embedding_id = NEW.id;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS tg_todo_item_embedding_id ON todo_item;
CREATE TRIGGER tg_todo_item_embedding_id
BEFORE INSERT OR UPDATE ON todo_item
FOR EACH ROW EXECUTE FUNCTION set_embedding_id();

-- Indexes for keyset pagination and search
CREATE INDEX IF NOT EXISTS idx_todo_item_user_created_id ON todo_item (user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_todo_item_user_priority_id ON todo_item (user_id, priority_score DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_todo_item_text_trgm ON todo_item USING GIN (title gin_trgm_ops, description gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_todo_item_metadata_gin ON todo_item USING GIN (metadata);
CREATE INDEX IF NOT EXISTS idx_todo_item_user_priority_level ON todo_item (user_id, priority_level_id);

-- Vector index (IVFFlat) for fast ANN, requires ANALYZE after sufficient rows
DO $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE c.relname = 'todo_item_embedding_ivfflat'
  ) THEN
      EXECUTE 'CREATE INDEX todo_item_embedding_ivfflat ON todo_item USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100)';
  END IF;
END$$;

-- ===================== 用户级优先级层级表 =====================
-- 使用 rank BIGINT 稀疏分配（初始间隔 1_000_000），插入时若 gap 不足触发整体重排
CREATE TABLE IF NOT EXISTS priority_level (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  name TEXT NOT NULL,
  rank BIGINT NOT NULL, -- 排序用，可索引用于范围/顺序查询
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_priority_level_user_name ON priority_level(user_id, name);
CREATE INDEX IF NOT EXISTS idx_priority_level_user_rank ON priority_level(user_id, rank);

-- ===================== 用户级分类表 =====================
CREATE TABLE IF NOT EXISTS category (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  name TEXT NOT NULL,
  color TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_category_user_name ON category(user_id, name);

-- ===================== 标签与关联 =====================
CREATE TABLE IF NOT EXISTS tag (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  name TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tag_user_name ON tag(user_id, name);

CREATE TABLE IF NOT EXISTS todo_tag (
  todo_id TEXT NOT NULL,
  tag_id TEXT NOT NULL,
  PRIMARY KEY (todo_id, tag_id)
);
CREATE INDEX IF NOT EXISTS idx_todo_tag_tag ON todo_tag(tag_id);
CREATE INDEX IF NOT EXISTS idx_todo_tag_todo ON todo_tag(todo_id);

-- 外键（演示环境可选；生产建议加上）
ALTER TABLE todo_item
  ADD CONSTRAINT fk_todo_item_priority_level
  FOREIGN KEY (priority_level_id) REFERENCES priority_level(id) ON DELETE SET NULL;
ALTER TABLE todo_item
  ADD CONSTRAINT fk_todo_item_category
  FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE SET NULL;
ALTER TABLE todo_tag
  ADD CONSTRAINT fk_todo_tag_todo FOREIGN KEY (todo_id) REFERENCES todo_item(id) ON DELETE CASCADE;
ALTER TABLE todo_tag
  ADD CONSTRAINT fk_todo_tag_tag FOREIGN KEY (tag_id) REFERENCES tag(id) ON DELETE CASCADE;

