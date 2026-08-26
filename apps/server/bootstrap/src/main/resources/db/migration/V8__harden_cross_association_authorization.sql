ALTER TABLE association_relationship
  ADD COLUMN IF NOT EXISTS suspended_by_association_id UUID,
  ADD COLUMN IF NOT EXISTS suspended_by_subject VARCHAR(200);

DO $migration$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'association_relationship_suspended_by_fk'
      AND conrelid = 'association_relationship'::regclass
  ) THEN
    ALTER TABLE association_relationship
      ADD CONSTRAINT association_relationship_suspended_by_fk
      FOREIGN KEY (suspended_by_association_id) REFERENCES association(id);
  END IF;
END
$migration$;

-- A relationship is logically undirected. Never delete or rewrite existing
-- records automatically: fail closed so an operator can inspect duplicates.
DO $migration$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM association_relationship
    GROUP BY LEAST(source_association_id, target_association_id),
             GREATEST(source_association_id, target_association_id)
    HAVING COUNT(*) > 1
  ) THEN
    RAISE EXCEPTION
      'duplicate canonical association relationships must be reviewed before V8 can continue';
  END IF;
END
$migration$;

CREATE UNIQUE INDEX IF NOT EXISTS association_relationship_canonical_pair_uq
  ON association_relationship (
    LEAST(source_association_id, target_association_id),
    GREATEST(source_association_id, target_association_id)
  );

DO $migration$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM association_access_request
    WHERE status = 'PENDING'
    GROUP BY LEAST(applicant_association_id, target_association_id),
             GREATEST(applicant_association_id, target_association_id)
    HAVING COUNT(*) > 1
  ) THEN
    RAISE EXCEPTION
      'duplicate pending association access requests must be reviewed before V8 can continue';
  END IF;
END
$migration$;

CREATE UNIQUE INDEX IF NOT EXISTS association_access_request_pending_canonical_pair_uq
  ON association_access_request (
    LEAST(applicant_association_id, target_association_id),
    GREATEST(applicant_association_id, target_association_id)
  )
  WHERE status = 'PENDING';
