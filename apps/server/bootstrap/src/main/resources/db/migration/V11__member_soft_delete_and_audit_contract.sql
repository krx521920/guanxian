ALTER TABLE enterprise
  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS deleted_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS status_before_delete VARCHAR(32);

UPDATE enterprise
SET deleted_at = COALESCE(deleted_at, updated_at, now()),
    deleted_by_subject = COALESCE(deleted_by_subject, 'migration'),
    status_before_delete = COALESCE(status_before_delete, 'DISABLED')
WHERE status = 'DELETED';

ALTER TABLE enterprise DROP CONSTRAINT IF EXISTS enterprise_soft_delete_ck;
ALTER TABLE enterprise ADD CONSTRAINT enterprise_soft_delete_ck CHECK (
  (deleted_at IS NULL AND deleted_by_subject IS NULL AND status_before_delete IS NULL AND status <> 'DELETED')
  OR
  (deleted_at IS NOT NULL AND deleted_by_subject IS NOT NULL AND status_before_delete IS NOT NULL AND status = 'DELETED')
);

CREATE INDEX IF NOT EXISTS enterprise_association_deleted_updated_idx
  ON enterprise (association_id, deleted_at, updated_at DESC);

-- All writers use this same audit envelope. Legacy rows remain readable, while new rows
-- must carry an actor subject and request correlation id.
ALTER TABLE audit_log
  ADD COLUMN IF NOT EXISTS resource_version BIGINT,
  ADD COLUMN IF NOT EXISTS outcome VARCHAR(32) NOT NULL DEFAULT 'SUCCESS';

UPDATE audit_log SET actor_subject = COALESCE(actor_subject, 'legacy') WHERE actor_subject IS NULL;
UPDATE audit_log SET actor_username = COALESCE(actor_username, actor_subject) WHERE actor_username IS NULL;
UPDATE audit_log SET request_id = COALESCE(request_id, 'legacy-' || id::text) WHERE request_id IS NULL;

ALTER TABLE audit_log ALTER COLUMN actor_subject SET NOT NULL;
ALTER TABLE audit_log ALTER COLUMN actor_username SET NOT NULL;
ALTER TABLE audit_log ALTER COLUMN request_id SET NOT NULL;
ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS audit_log_outcome_ck;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_outcome_ck CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED'));
ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS audit_log_resource_version_ck;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_resource_version_ck CHECK (resource_version IS NULL OR resource_version >= 0);
