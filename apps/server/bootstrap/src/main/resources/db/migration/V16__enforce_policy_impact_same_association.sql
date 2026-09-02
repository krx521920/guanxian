-- A policy impact is meaningful only inside one association. Earlier versions
-- protected the two parent references independently, so a policy from one
-- association could be paired with an enterprise from another association.
-- Stop before changing the schema when legacy data cannot be backfilled
-- unambiguously. The message is deliberately actionable for operators.
DO $migration$
DECLARE
  mismatch_count BIGINT;
  mismatch_sample TEXT;
BEGIN
  SELECT count(*),
         min(format(
           'impact_id=%s, policy_document_id=%s (association_id=%s), enterprise_id=%s (association_id=%s)',
           impact.id,
           impact.policy_document_id,
           COALESCE(policy.association_id::text, 'NULL'),
           impact.enterprise_id,
           enterprise.association_id
         ))
    INTO mismatch_count, mismatch_sample
    FROM policy_impact_analysis AS impact
    JOIN policy_document AS policy ON policy.id = impact.policy_document_id
    JOIN enterprise ON enterprise.id = impact.enterprise_id
   WHERE policy.association_id IS DISTINCT FROM enterprise.association_id;

  IF mismatch_count > 0 THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = format(
        'V16 cannot enforce policy impact association consistency: found %s mismatched row(s)',
        mismatch_count
      ),
      DETAIL = mismatch_sample,
      HINT = 'Correct or delete the mismatched policy_impact_analysis rows so each policy_document and enterprise share one association, then rerun the migration.';
  END IF;
END
$migration$;

ALTER TABLE policy_impact_analysis
  ADD COLUMN association_id UUID;

UPDATE policy_impact_analysis AS impact
   SET association_id = enterprise.association_id
  FROM enterprise
 WHERE enterprise.id = impact.enterprise_id;

ALTER TABLE policy_impact_analysis
  ALTER COLUMN association_id SET NOT NULL;

-- PostgreSQL composite foreign keys require matching unique keys on both
-- parents. They also prevent either parent from being moved to another
-- association while an impact analysis still references it.
CREATE UNIQUE INDEX policy_document_id_association_uq
  ON policy_document (id, association_id);

CREATE UNIQUE INDEX enterprise_id_association_uq
  ON enterprise (id, association_id);

ALTER TABLE policy_impact_analysis
  ADD CONSTRAINT policy_impact_policy_association_fk
  FOREIGN KEY (policy_document_id, association_id)
  REFERENCES policy_document (id, association_id)
  NOT VALID;

ALTER TABLE policy_impact_analysis
  ADD CONSTRAINT policy_impact_enterprise_association_fk
  FOREIGN KEY (enterprise_id, association_id)
  REFERENCES enterprise (id, association_id)
  NOT VALID;

ALTER TABLE policy_impact_analysis
  VALIDATE CONSTRAINT policy_impact_policy_association_fk;

ALTER TABLE policy_impact_analysis
  VALIDATE CONSTRAINT policy_impact_enterprise_association_fk;

-- Existing application writers do not send association_id. Derive it from
-- the verified parents, while rejecting both cross-association pairs and an
-- explicitly forged association context with stable SQLSTATEs and messages.
CREATE FUNCTION enforce_policy_impact_same_association()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  policy_association UUID;
  enterprise_association UUID;
BEGIN
  SELECT association_id
    INTO policy_association
    FROM policy_document
   WHERE id = NEW.policy_document_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION USING
      ERRCODE = '23503',
      MESSAGE = 'policy impact references an unknown policy document',
      DETAIL = format('policy_document_id=%s', NEW.policy_document_id);
  END IF;

  IF policy_association IS NULL THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = 'policy impact policy document has no association context',
      DETAIL = format('policy_document_id=%s', NEW.policy_document_id),
      HINT = 'Assign the policy document to an association before creating or updating an impact analysis.';
  END IF;

  SELECT association_id
    INTO enterprise_association
    FROM enterprise
   WHERE id = NEW.enterprise_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION USING
      ERRCODE = '23503',
      MESSAGE = 'policy impact references an unknown enterprise',
      DETAIL = format('enterprise_id=%s', NEW.enterprise_id);
  END IF;

  IF policy_association IS DISTINCT FROM enterprise_association THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = 'policy impact association mismatch',
      DETAIL = format(
        'policy_document_id=%s belongs to association_id=%s, enterprise_id=%s belongs to association_id=%s',
        NEW.policy_document_id,
        policy_association,
        NEW.enterprise_id,
        enterprise_association
      ),
      HINT = 'Select a policy document and enterprise from the same association.';
  END IF;

  IF NEW.association_id IS NOT NULL
     AND NEW.association_id IS DISTINCT FROM policy_association THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = 'policy impact association context mismatch',
      DETAIL = format(
        'association_id=%s does not match parent association_id=%s',
        NEW.association_id,
        policy_association
      ),
      HINT = 'Omit association_id or use the association shared by the policy document and enterprise.';
  END IF;

  NEW.association_id := policy_association;
  RETURN NEW;
END
$function$;

CREATE TRIGGER policy_impact_same_association_trg
BEFORE INSERT OR UPDATE OF policy_document_id, enterprise_id, association_id
ON policy_impact_analysis
FOR EACH ROW
EXECUTE FUNCTION enforce_policy_impact_same_association();

CREATE INDEX policy_impact_association_updated_idx
  ON policy_impact_analysis (association_id, updated_at DESC);
