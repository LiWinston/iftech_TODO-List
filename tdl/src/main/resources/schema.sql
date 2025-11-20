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
    status SMALLINT NOT NULL DEFAULT 0, -- 0=ACTIVE,1=COMPLETED,2=TRASHED
    trash_purge_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    -- LangChain4j PgVectorEmbeddingStore expected columns
    embedding VECTOR(384),
    text TEXT,        -- concatenated content for semantic storage
    metadata JSONB    -- dynamic metadata for filtering
);

-- Ensure columns exist when table was created earlier without them
ALTER TABLE todo_item ADD COLUMN IF NOT EXISTS embedding VECTOR(384);
ALTER TABLE todo_item ADD COLUMN IF NOT EXISTS text TEXT;
ALTER TABLE todo_item ADD COLUMN IF NOT EXISTS metadata JSONB;
ALTER TABLE todo_item ADD COLUMN IF NOT EXISTS trash_purge_at TIMESTAMP NULL;

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

-- Indexes for keyset pagination and search
CREATE INDEX IF NOT EXISTS idx_todo_item_user_created_id ON todo_item (user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_todo_item_user_priority_id ON todo_item (user_id, priority_score DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_todo_item_text_trgm ON todo_item USING GIN (title gin_trgm_ops, description gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_todo_item_metadata_gin ON todo_item USING GIN (metadata);

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
