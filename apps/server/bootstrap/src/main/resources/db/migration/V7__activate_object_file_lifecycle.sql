ALTER TABLE object_file
  ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(32),
  ADD COLUMN IF NOT EXISTS version BIGINT,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS deleted_by_subject VARCHAR(200);

UPDATE object_file
SET lifecycle_status = CASE WHEN deleted_at IS NULL THEN 'ACTIVE' ELSE 'DELETED' END
WHERE lifecycle_status IS NULL;

UPDATE object_file
SET version = 0
WHERE version IS NULL;

UPDATE object_file
SET updated_at = COALESCE(deleted_at, uploaded_at, now())
WHERE updated_at IS NULL;

ALTER TABLE object_file
  ALTER COLUMN lifecycle_status SET DEFAULT 'ACTIVE',
  ALTER COLUMN lifecycle_status SET NOT NULL,
  ALTER COLUMN version SET DEFAULT 0,
  ALTER COLUMN version SET NOT NULL,
  ALTER COLUMN updated_at SET DEFAULT now(),
  ALTER COLUMN updated_at SET NOT NULL;

DO $migration$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'object_file_lifecycle_status_check'
      AND conrelid = 'object_file'::regclass
  ) THEN
    ALTER TABLE object_file
      ADD CONSTRAINT object_file_lifecycle_status_check
      CHECK (lifecycle_status IN ('ACTIVE', 'DELETED'));
  END IF;
END
$migration$;

CREATE INDEX IF NOT EXISTS object_file_scope_lifecycle_time_idx
  ON object_file (association_id, enterprise_id, lifecycle_status, uploaded_at DESC);
