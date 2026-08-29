ALTER TABLE knowledge_chunk
  ADD COLUMN IF NOT EXISTS embedding JSONB,
  ADD COLUMN IF NOT EXISTS embedding_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CONFIGURED',
  ADD COLUMN IF NOT EXISTS vector_dimension INTEGER,
  ADD COLUMN IF NOT EXISTS embedding_updated_at TIMESTAMPTZ;

UPDATE knowledge_chunk
   SET embedding_status = CASE
       WHEN embedding IS NOT NULL THEN 'READY'
       ELSE 'NOT_CONFIGURED'
   END
 WHERE embedding_status IS NULL
    OR embedding_status NOT IN ('NOT_CONFIGURED', 'READY', 'FAILED');

ALTER TABLE knowledge_chunk DROP CONSTRAINT IF EXISTS knowledge_chunk_embedding_status_ck;
ALTER TABLE knowledge_chunk ADD CONSTRAINT knowledge_chunk_embedding_status_ck CHECK (
  embedding_status IN ('NOT_CONFIGURED', 'READY', 'FAILED')
);

ALTER TABLE knowledge_chunk DROP CONSTRAINT IF EXISTS knowledge_chunk_embedding_shape_ck;
ALTER TABLE knowledge_chunk ADD CONSTRAINT knowledge_chunk_embedding_shape_ck CHECK (
  (embedding_status = 'READY'
    AND embedding IS NOT NULL
    AND jsonb_typeof(embedding) = 'array'
    AND vector_dimension IS NOT NULL
    AND vector_dimension BETWEEN 8 AND 4096
    AND jsonb_array_length(embedding) = vector_dimension
    AND embedding_provider IS NOT NULL
    AND embedding_model IS NOT NULL
    AND embedding_updated_at IS NOT NULL)
  OR
  (embedding_status <> 'READY' AND embedding IS NULL AND vector_dimension IS NULL)
);

CREATE INDEX IF NOT EXISTS knowledge_chunk_embedding_ready_idx
  ON knowledge_chunk (embedding_provider, embedding_model, document_version_id)
  WHERE embedding_status = 'READY';
