ALTER TABLE ecosystem_match
  ADD COLUMN IF NOT EXISTS demand_confirmed_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS demand_confirmed_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS candidate_confirmed_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS candidate_confirmed_at TIMESTAMPTZ;

-- V4 stored only one undifferentiated confirmation. It cannot prove which
-- enterprise confirmed, so legacy rows are deliberately re-qualified instead
-- of being treated as bilateral confirmation.
UPDATE ecosystem_match
   SET state=CASE
           WHEN recommended_at IS NOT NULL THEN 'RECOMMENDED'
           ELSE 'PENDING_CONFIRMATION' END,
       version=version+1,
       updated_at=now()
 WHERE state='CONFIRMED'
   AND (demand_confirmed_at IS NULL OR candidate_confirmed_at IS NULL);

ALTER TABLE ecosystem_match
  ADD CONSTRAINT ecosystem_match_state_v10_ck CHECK (state IN (
    'PENDING_CONFIRMATION', 'RECOMMENDED', 'PARTIALLY_CONFIRMED',
    'CONFIRMED', 'INVITED', 'NEGOTIATING', 'OUTCOME_PENDING',
    'ARCHIVED', 'CLOSED')) NOT VALID,
  ADD CONSTRAINT ecosystem_match_bilateral_confirmation_v10_ck CHECK (
    state NOT IN ('CONFIRMED', 'INVITED', 'NEGOTIATING', 'OUTCOME_PENDING', 'ARCHIVED')
    OR (demand_confirmed_at IS NOT NULL AND candidate_confirmed_at IS NOT NULL)
  ) NOT VALID;

UPDATE match_invitation
   SET status='EXPIRED',
       updated_at=now(),
       version=version+1
 WHERE status='PENDING'
   AND expires_at IS NOT NULL
   AND expires_at <= now();

DO $migration$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM match_invitation
     WHERE status='PENDING'
       AND (expires_at IS NULL OR expires_at > now())
     GROUP BY match_id
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION
      'multiple active pending invitations exist for one match; review before V10';
  END IF;
END
$migration$;

CREATE UNIQUE INDEX IF NOT EXISTS match_invitation_one_pending_uq
  ON match_invitation (match_id)
  WHERE status='PENDING';

DO $migration$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM outcome_archive
     WHERE deleted_at IS NULL
     GROUP BY match_id
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION
      'multiple active outcomes exist for one match; review before V10';
  END IF;
END
$migration$;

CREATE UNIQUE INDEX IF NOT EXISTS outcome_archive_one_active_uq
  ON outcome_archive (match_id)
  WHERE deleted_at IS NULL;

ALTER TABLE match_invitation
  ADD CONSTRAINT match_invitation_type_v10_ck CHECK (
    invitation_type IN ('ENTERPRISE', 'ASSOCIATION_RECOMMENDATION')) NOT VALID;

ALTER TABLE negotiation_record
  ADD CONSTRAINT negotiation_stage_v10_ck CHECK (stage IN (
    'INITIAL_CONTACT', 'TECHNICAL_EXCHANGE', 'COMMERCIAL_NEGOTIATION',
    'CONTRACTING', 'CONTRACT_SIGNED', 'TERMINATED')) NOT VALID;

ALTER TABLE match_feedback
  ADD CONSTRAINT match_feedback_outcome_v10_ck CHECK (
    outcome IN ('SUCCESS', 'NO_DEAL', 'WITHDRAWN')) NOT VALID,
  ADD CONSTRAINT match_feedback_close_reason_v10_ck CHECK (
    outcome='SUCCESS' OR nullif(btrim(close_reason), '') IS NOT NULL) NOT VALID;

ALTER TABLE outcome_archive
  ADD CONSTRAINT outcome_archive_result_type_v10_ck CHECK (
    result_type IN ('COOPERATION', 'CONTRACT', 'PILOT', 'TECHNICAL_RESULT')) NOT VALID;
