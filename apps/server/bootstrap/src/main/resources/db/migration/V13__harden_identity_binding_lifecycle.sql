ALTER TABLE user_account
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE user_account DROP CONSTRAINT IF EXISTS user_account_version_ck;
ALTER TABLE user_account ADD CONSTRAINT user_account_version_ck
  CHECK (version >= 0);

ALTER TABLE user_account DROP CONSTRAINT IF EXISTS user_account_status_ck;

-- Older deployments did not constrain identity status. Fail closed while
-- preserving the account row and audit linkage instead of leaving a value that
-- cannot be updated after the new constraint is installed.
UPDATE user_account
SET status = 'INACTIVE',
    version = version + 1,
    updated_at = now()
WHERE status NOT IN ('ACTIVE', 'INACTIVE');

ALTER TABLE user_account ADD CONSTRAINT user_account_status_ck
  CHECK (status IN ('ACTIVE', 'INACTIVE')) NOT VALID;

ALTER TABLE user_account VALIDATE CONSTRAINT user_account_status_ck;

CREATE INDEX IF NOT EXISTS user_account_binding_lifecycle_idx
  ON user_account (status, association_id, enterprise_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS revoked_identity_subject (
  external_subject VARCHAR(200) PRIMARY KEY,
  user_account_id UUID REFERENCES user_account(id) ON DELETE SET NULL,
  revoked_by_subject VARCHAR(200) NOT NULL,
  reason VARCHAR(100) NOT NULL DEFAULT 'UNBOUND',
  revoked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS revoked_identity_subject_time_idx
  ON revoked_identity_subject (revoked_at DESC);
