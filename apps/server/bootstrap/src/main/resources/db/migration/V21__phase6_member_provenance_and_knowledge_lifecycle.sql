-- Phase 6 freezes the member survey contract, preserves import provenance, and gives
-- knowledge documents an explicit review/lifecycle version. Existing rows remain
-- readable; provenance fields are nullable only for imports created before V21.

ALTER TABLE enterprise
  ADD COLUMN IF NOT EXISTS contact_email VARCHAR(254),
  ADD COLUMN IF NOT EXISTS services JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS application_scenarios JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE member_import_batch
  ADD COLUMN IF NOT EXISTS template_version VARCHAR(64),
  ADD COLUMN IF NOT EXISTS source_sha256 CHAR(64),
  ADD COLUMN IF NOT EXISTS submitted_unit VARCHAR(200),
  ADD COLUMN IF NOT EXISTS submitted_enterprise_id UUID REFERENCES enterprise(id);

ALTER TABLE member_import_batch DROP CONSTRAINT IF EXISTS member_import_batch_source_sha256_ck;
ALTER TABLE member_import_batch ADD CONSTRAINT member_import_batch_source_sha256_ck CHECK (
  source_sha256 IS NULL OR source_sha256 ~ '^[0-9a-f]{64}$'
);

CREATE INDEX IF NOT EXISTS member_import_row_enterprise_idx
  ON member_import_row (enterprise_id)
  WHERE enterprise_id IS NOT NULL;

ALTER TABLE knowledge_document
  ADD COLUMN IF NOT EXISTS lifecycle_version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS reviewed_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS review_comment VARCHAR(1000),
  ADD COLUMN IF NOT EXISTS deleted_by_subject VARCHAR(200);

ALTER TABLE knowledge_document DROP CONSTRAINT IF EXISTS knowledge_document_status_ck;
ALTER TABLE knowledge_document ADD CONSTRAINT knowledge_document_status_ck CHECK (
  status IN ('DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'DISABLED', 'ARCHIVED')
);

ALTER TABLE knowledge_document DROP CONSTRAINT IF EXISTS knowledge_document_review_ck;
ALTER TABLE knowledge_document ADD CONSTRAINT knowledge_document_review_ck CHECK (
  (reviewed_at IS NULL AND reviewed_by_subject IS NULL)
  OR (reviewed_at IS NOT NULL AND reviewed_by_subject IS NOT NULL)
);

ALTER TABLE knowledge_document DROP CONSTRAINT IF EXISTS knowledge_document_delete_ck;
ALTER TABLE knowledge_document ADD CONSTRAINT knowledge_document_delete_ck CHECK (
  (deleted_at IS NULL AND deleted_by_subject IS NULL)
  OR (deleted_at IS NOT NULL AND deleted_by_subject IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS knowledge_document_history (
  id BIGSERIAL PRIMARY KEY,
  document_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
  association_id UUID REFERENCES association(id),
  lifecycle_version BIGINT NOT NULL CHECK (lifecycle_version >= 0),
  action VARCHAR(64) NOT NULL,
  actor_subject VARCHAR(200) NOT NULL,
  snapshot JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS knowledge_document_history_document_time_idx
  ON knowledge_document_history (document_id, occurred_at DESC);
