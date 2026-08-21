CREATE TABLE IF NOT EXISTS object_file (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  association_id UUID REFERENCES association(id),
  enterprise_id UUID REFERENCES enterprise(id),
  bucket_name VARCHAR(100) NOT NULL,
  object_key VARCHAR(500) NOT NULL UNIQUE,
  original_filename VARCHAR(255) NOT NULL,
  media_type VARCHAR(200) NOT NULL,
  size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
  sha256 CHAR(64) NOT NULL,
  scan_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  visibility VARCHAR(32) NOT NULL DEFAULT 'PRIVATE',
  uploaded_by_subject VARCHAR(200) NOT NULL,
  uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS object_file_scope_time_idx
  ON object_file (association_id, enterprise_id, uploaded_at DESC)
  WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS knowledge_document (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  association_id UUID REFERENCES association(id),
  title VARCHAR(500) NOT NULL,
  document_type VARCHAR(64) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_url TEXT,
  source_file_id UUID REFERENCES object_file(id),
  visibility VARCHAR(32) NOT NULL DEFAULT 'ASSOCIATION',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  current_version INTEGER NOT NULL DEFAULT 0,
  content_hash CHAR(64),
  created_by_subject VARCHAR(200) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS knowledge_document_version (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
  version INTEGER NOT NULL CHECK (version > 0),
  source_file_id UUID REFERENCES object_file(id),
  parser_name VARCHAR(100),
  parser_version VARCHAR(50),
  page_count INTEGER,
  status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING',
  error_code VARCHAR(100),
  created_by_subject VARCHAR(200) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (document_id, version)
);

CREATE TABLE IF NOT EXISTS knowledge_chunk (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  document_version_id UUID NOT NULL REFERENCES knowledge_document_version(id) ON DELETE CASCADE,
  chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
  heading_path VARCHAR(1000),
  page_from INTEGER,
  page_to INTEGER,
  content TEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  token_count INTEGER CHECK (token_count >= 0),
  embedding_provider VARCHAR(100),
  embedding_model VARCHAR(100),
  vector_store_key VARCHAR(300),
  search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (document_version_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS knowledge_chunk_search_idx
  ON knowledge_chunk USING gin (search_vector);
CREATE INDEX IF NOT EXISTS knowledge_chunk_vector_key_idx
  ON knowledge_chunk (vector_store_key)
  WHERE vector_store_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS retrieval_trace (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  association_id UUID REFERENCES association(id),
  actor_subject VARCHAR(200) NOT NULL,
  question TEXT NOT NULL,
  query_hash CHAR(64) NOT NULL,
  provider VARCHAR(100),
  model VARCHAR(100),
  retrieved_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
  answer_status VARCHAR(32) NOT NULL,
  input_tokens INTEGER,
  output_tokens INTEGER,
  estimated_cost NUMERIC(18,8),
  latency_ms INTEGER,
  request_id VARCHAR(128),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS qa_citation (
  id BIGSERIAL PRIMARY KEY,
  retrieval_trace_id UUID NOT NULL REFERENCES retrieval_trace(id) ON DELETE CASCADE,
  chunk_id UUID NOT NULL REFERENCES knowledge_chunk(id),
  citation_order INTEGER NOT NULL CHECK (citation_order > 0),
  quote_text VARCHAR(2000) NOT NULL,
  score NUMERIC(8,6),
  UNIQUE (retrieval_trace_id, citation_order)
);

CREATE TABLE IF NOT EXISTS policy_impact_analysis (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  policy_document_id UUID NOT NULL REFERENCES policy_document(id),
  enterprise_id UUID NOT NULL REFERENCES enterprise(id),
  impact_level VARCHAR(16) NOT NULL,
  summary TEXT NOT NULL,
  evidence_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
  model_execution_id UUID,
  reviewed_by_subject VARCHAR(200),
  reviewed_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (policy_document_id, enterprise_id)
);

CREATE TABLE IF NOT EXISTS model_execution (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  association_id UUID REFERENCES association(id),
  actor_subject VARCHAR(200),
  purpose VARCHAR(64) NOT NULL,
  provider VARCHAR(100) NOT NULL,
  model VARCHAR(100) NOT NULL,
  status VARCHAR(32) NOT NULL,
  prompt_hash CHAR(64),
  input_tokens INTEGER,
  output_tokens INTEGER,
  estimated_cost NUMERIC(18,8),
  latency_ms INTEGER,
  error_code VARCHAR(100),
  request_id VARCHAR(128),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE policy_impact_analysis
  ADD CONSTRAINT policy_impact_model_execution_fk
  FOREIGN KEY (model_execution_id) REFERENCES model_execution(id);

CREATE TABLE IF NOT EXISTS notification_subscription (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
  association_id UUID REFERENCES association(id),
  subscription_type VARCHAR(64) NOT NULL,
  filters JSONB NOT NULL DEFAULT '{}'::jsonb,
  channels JSONB NOT NULL DEFAULT '["IN_APP"]'::jsonb,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS notification_message (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
  association_id UUID REFERENCES association(id),
  notification_type VARCHAR(64) NOT NULL,
  title VARCHAR(300) NOT NULL,
  body TEXT NOT NULL,
  resource_type VARCHAR(64),
  resource_id UUID,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  idempotency_key VARCHAR(200) NOT NULL UNIQUE,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ,
  read_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  delivered_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS notification_user_status_idx
  ON notification_message (user_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS outbox_event (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id UUID NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload JSONB NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL UNIQUE,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  attempts INTEGER NOT NULL DEFAULT 0,
  available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ,
  last_error_code VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS outbox_pending_idx
  ON outbox_event (available_at, created_at)
  WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS rate_limit_audit (
  id BIGSERIAL PRIMARY KEY,
  subject_key_hash CHAR(64) NOT NULL,
  route_key VARCHAR(200) NOT NULL,
  decision VARCHAR(16) NOT NULL,
  request_id VARCHAR(128),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
