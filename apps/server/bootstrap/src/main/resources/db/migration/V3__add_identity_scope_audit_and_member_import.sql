ALTER TABLE user_account
  ADD COLUMN IF NOT EXISTS association_id UUID REFERENCES association(id),
  ADD COLUMN IF NOT EXISTS external_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS email VARCHAR(254);

UPDATE user_account AS account
SET association_id = enterprise.association_id
FROM enterprise
WHERE account.enterprise_id = enterprise.id
  AND account.association_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS user_account_external_subject_uq
  ON user_account (external_subject)
  WHERE external_subject IS NOT NULL;

CREATE TABLE IF NOT EXISTS association_relationship (
  source_association_id UUID NOT NULL REFERENCES association(id) ON DELETE CASCADE,
  target_association_id UUID NOT NULL REFERENCES association(id) ON DELETE CASCADE,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  allow_member_data BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (source_association_id, target_association_id),
  CHECK (source_association_id <> target_association_id)
);

ALTER TABLE audit_log
  ADD COLUMN IF NOT EXISTS actor_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS actor_username VARCHAR(100),
  ADD COLUMN IF NOT EXISTS association_id UUID,
  ADD COLUMN IF NOT EXISTS enterprise_id UUID,
  ADD COLUMN IF NOT EXISTS request_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS audit_log_association_time_idx
  ON audit_log (association_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS audit_log_resource_idx
  ON audit_log (resource_type, resource_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS member_review (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  enterprise_id UUID NOT NULL REFERENCES enterprise(id) ON DELETE CASCADE,
  reviewer_user_id UUID REFERENCES user_account(id),
  reviewer_subject VARCHAR(200) NOT NULL,
  previous_status VARCHAR(32) NOT NULL,
  decision VARCHAR(32) NOT NULL,
  comment VARCHAR(1000),
  reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS member_review_enterprise_time_idx
  ON member_review (enterprise_id, reviewed_at DESC);

CREATE TABLE IF NOT EXISTS member_import_batch (
  id UUID PRIMARY KEY,
  association_id UUID NOT NULL REFERENCES association(id),
  original_filename VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PREVIEWED',
  total_rows INTEGER NOT NULL CHECK (total_rows >= 0),
  valid_rows INTEGER NOT NULL CHECK (valid_rows >= 0),
  invalid_rows INTEGER NOT NULL CHECK (invalid_rows >= 0),
  created_by_subject VARCHAR(200) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  committed_at TIMESTAMPTZ,
  CHECK (status IN ('PREVIEWED', 'COMMITTED', 'CANCELLED')),
  CHECK (total_rows = valid_rows + invalid_rows)
);

CREATE TABLE IF NOT EXISTS member_import_row (
  id BIGSERIAL PRIMARY KEY,
  batch_id UUID NOT NULL REFERENCES member_import_batch(id) ON DELETE CASCADE,
  row_number INTEGER NOT NULL CHECK (row_number >= 2),
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  errors JSONB NOT NULL DEFAULT '[]'::jsonb,
  status VARCHAR(32) NOT NULL,
  enterprise_id UUID REFERENCES enterprise(id),
  UNIQUE (batch_id, row_number),
  CHECK (status IN ('VALID', 'INVALID', 'IMPORTED'))
);

CREATE INDEX IF NOT EXISTS member_import_batch_association_time_idx
  ON member_import_batch (association_id, created_at DESC);
