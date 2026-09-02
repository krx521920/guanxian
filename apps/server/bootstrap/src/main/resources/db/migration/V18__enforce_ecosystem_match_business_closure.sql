-- Phase 4 closes the persisted ecosystem-match workflow.  This migration is
-- deliberately additive: V1-V17 remain immutable and ambiguous legacy rows
-- stop the upgrade instead of being assigned invented business facts.

ALTER TABLE negotiation_record
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE match_feedback
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

-- submitted_at is the only verified revision timestamp on pre-V18 feedback;
-- copying it is deterministic and does not invent a later edit event.
UPDATE match_feedback
   SET updated_at=submitted_at
 WHERE updated_at IS NULL;

ALTER TABLE match_feedback
  ALTER COLUMN updated_at SET DEFAULT now(),
  ALTER COLUMN updated_at SET NOT NULL;

DO $migration$
DECLARE
  invalid_id UUID;
  invalid_reason TEXT;
BEGIN
  SELECT m.id,
         CASE
           WHEN d.enterprise_id=m.candidate_enterprise_id
             THEN 'the demand owner and candidate enterprise are identical'
           WHEN m.state NOT IN (
             'PENDING_CONFIRMATION', 'RECOMMENDED', 'PARTIALLY_CONFIRMED',
             'CONFIRMED', 'INVITED', 'NEGOTIATING', 'OUTCOME_PENDING',
             'ARCHIVED', 'CLOSED')
             THEN 'the match state is unsupported'
           WHEN m.review_status NOT IN ('PENDING', 'APPROVED', 'CLOSED')
             THEN 'the review status is unsupported'
           WHEN (m.reviewed_by IS NULL) <> (m.reviewed_at IS NULL)
             THEN 'reviewed_by and reviewed_at are not a complete pair'
           WHEN (m.recommended_by_subject IS NULL) <> (m.recommended_at IS NULL)
             THEN 'recommended_by_subject and recommended_at are not a complete pair'
           WHEN (m.demand_confirmed_by_subject IS NULL) <> (m.demand_confirmed_at IS NULL)
             THEN 'the demand confirmation actor and timestamp are not a complete pair'
           WHEN (m.candidate_confirmed_by_subject IS NULL) <> (m.candidate_confirmed_at IS NULL)
             THEN 'the candidate confirmation actor and timestamp are not a complete pair'
           WHEN m.state='PENDING_CONFIRMATION'
                AND (m.recommended_at IS NOT NULL
                     OR m.demand_confirmed_at IS NOT NULL
                     OR m.candidate_confirmed_at IS NOT NULL
                     OR m.review_status<>'PENDING')
             THEN 'a pending match contains recommendation or confirmation facts'
           WHEN m.state='RECOMMENDED'
                AND (m.recommended_at IS NULL
                     OR m.demand_confirmed_at IS NOT NULL
                     OR m.candidate_confirmed_at IS NOT NULL
                     OR m.review_status<>'APPROVED')
             THEN 'a recommended match does not represent an unconfirmed approved recommendation'
           WHEN m.state='PARTIALLY_CONFIRMED'
                AND (m.recommended_at IS NULL
                     OR ((m.demand_confirmed_at IS NOT NULL)::integer
                       + (m.candidate_confirmed_at IS NOT NULL)::integer)<>1
                     OR m.review_status<>'APPROVED')
             THEN 'a partially confirmed match is not recommended or does not contain exactly one confirmation'
           WHEN m.state IN (
                  'CONFIRMED', 'INVITED', 'NEGOTIATING',
                  'OUTCOME_PENDING', 'ARCHIVED')
                AND (m.recommended_at IS NULL
                     OR m.demand_confirmed_at IS NULL
                     OR m.candidate_confirmed_at IS NULL
                     OR m.review_status<>'APPROVED')
             THEN 'an advanced match is missing recommendation or bilateral confirmation facts'
           WHEN m.recommended_at IS NOT NULL
                AND ((m.demand_confirmed_at IS NOT NULL
                      AND m.demand_confirmed_at<m.recommended_at)
                  OR (m.candidate_confirmed_at IS NOT NULL
                      AND m.candidate_confirmed_at<m.recommended_at))
             THEN 'a confirmation predates the association recommendation'
           WHEN m.state='CLOSED'
                AND (nullif(btrim(m.closed_reason), '') IS NULL
                     OR m.review_status<>'CLOSED')
             THEN 'a closed match is missing its close reason or CLOSED review status'
           WHEN m.state<>'CLOSED' AND m.closed_reason IS NOT NULL
             THEN 'a non-closed match contains a close reason'
           WHEN m.version<0
             THEN 'the match version is negative'
         END
    INTO invalid_id, invalid_reason
    FROM ecosystem_match m
    JOIN cooperation_demand d ON d.id=m.demand_id
   WHERE d.enterprise_id=m.candidate_enterprise_id
      OR m.state NOT IN (
           'PENDING_CONFIRMATION', 'RECOMMENDED', 'PARTIALLY_CONFIRMED',
           'CONFIRMED', 'INVITED', 'NEGOTIATING', 'OUTCOME_PENDING',
           'ARCHIVED', 'CLOSED')
      OR m.review_status NOT IN ('PENDING', 'APPROVED', 'CLOSED')
      OR (m.reviewed_by IS NULL) <> (m.reviewed_at IS NULL)
      OR (m.recommended_by_subject IS NULL) <> (m.recommended_at IS NULL)
      OR (m.demand_confirmed_by_subject IS NULL) <> (m.demand_confirmed_at IS NULL)
      OR (m.candidate_confirmed_by_subject IS NULL) <> (m.candidate_confirmed_at IS NULL)
      OR (m.state='PENDING_CONFIRMATION'
          AND (m.recommended_at IS NOT NULL
               OR m.demand_confirmed_at IS NOT NULL
               OR m.candidate_confirmed_at IS NOT NULL
               OR m.review_status<>'PENDING'))
      OR (m.state='RECOMMENDED'
          AND (m.recommended_at IS NULL
               OR m.demand_confirmed_at IS NOT NULL
               OR m.candidate_confirmed_at IS NOT NULL
               OR m.review_status<>'APPROVED'))
      OR (m.state='PARTIALLY_CONFIRMED'
          AND (m.recommended_at IS NULL
               OR ((m.demand_confirmed_at IS NOT NULL)::integer
                 + (m.candidate_confirmed_at IS NOT NULL)::integer)<>1
               OR m.review_status<>'APPROVED'))
      OR (m.state IN (
            'CONFIRMED', 'INVITED', 'NEGOTIATING',
            'OUTCOME_PENDING', 'ARCHIVED')
          AND (m.recommended_at IS NULL
               OR m.demand_confirmed_at IS NULL
               OR m.candidate_confirmed_at IS NULL
               OR m.review_status<>'APPROVED'))
      OR (m.recommended_at IS NOT NULL
          AND ((m.demand_confirmed_at IS NOT NULL
                AND m.demand_confirmed_at<m.recommended_at)
            OR (m.candidate_confirmed_at IS NOT NULL
                AND m.candidate_confirmed_at<m.recommended_at)))
      OR (m.state='CLOSED'
          AND (nullif(btrim(m.closed_reason), '') IS NULL
               OR m.review_status<>'CLOSED'))
      OR (m.state<>'CLOSED' AND m.closed_reason IS NOT NULL)
      OR m.version<0
   ORDER BY m.id
   LIMIT 1;

  IF invalid_id IS NOT NULL THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='V18 cannot enforce the ecosystem match lifecycle',
      DETAIL=format('ecosystem_match %s is invalid: %s', invalid_id, invalid_reason),
      HINT='Review the named match against its demand, recommendation, confirmations and close decision; correct the source business record before retrying V18. V18 will not invent recommendation or confirmation facts.';
  END IF;
END
$migration$;

DO $migration$
DECLARE
  invalid_id UUID;
  invalid_reason TEXT;
BEGIN
  SELECT i.id,
         CASE
           WHEN i.association_id IS NULL THEN 'association_id is null'
           WHEN i.recipient_enterprise_id IS NULL THEN 'recipient_enterprise_id is null'
           WHEN i.association_id<>de.association_id
             THEN 'association_id is not the demand owner association'
           WHEN i.recipient_enterprise_id<>m.candidate_enterprise_id
             THEN 'recipient_enterprise_id is not the match candidate'
           WHEN i.invitation_type NOT IN ('ENTERPRISE', 'ASSOCIATION_RECOMMENDATION')
             THEN 'invitation_type is unsupported'
           WHEN i.invitation_type='ENTERPRISE'
                AND i.sender_enterprise_id IS DISTINCT FROM d.enterprise_id
             THEN 'an ENTERPRISE invitation was not sent by the demand owner'
           WHEN i.invitation_type='ASSOCIATION_RECOMMENDATION'
                AND i.sender_enterprise_id IS NOT NULL
             THEN 'an ASSOCIATION_RECOMMENDATION invitation has an enterprise sender'
           WHEN i.status IN ('ACCEPTED', 'REJECTED')
                AND (i.responded_by_subject IS NULL OR i.responded_at IS NULL)
             THEN 'a recipient decision is missing its actor or timestamp'
           WHEN i.status IN ('PENDING', 'EXPIRED', 'CANCELLED')
                AND (i.responded_by_subject IS NOT NULL OR i.responded_at IS NOT NULL)
             THEN 'a non-recipient-decision status contains response metadata'
           WHEN i.status='REJECTED'
                AND nullif(btrim(i.response_comment), '') IS NULL
             THEN 'a rejected invitation has no response comment'
           WHEN i.status='CANCELLED'
                AND nullif(btrim(i.response_comment), '') IS NULL
             THEN 'a cancelled invitation has no cancellation reason'
           WHEN i.status IN ('PENDING', 'EXPIRED')
                AND i.response_comment IS NOT NULL
             THEN 'a pending or expired invitation contains response text'
           WHEN i.responded_at IS NOT NULL AND i.responded_at<i.created_at
             THEN 'responded_at predates created_at'
           WHEN i.expires_at IS NOT NULL AND i.expires_at<=i.created_at
             THEN 'expires_at does not follow created_at'
           WHEN i.responded_at IS NOT NULL AND i.expires_at IS NOT NULL
                AND i.responded_at>i.expires_at
             THEN 'the invitation was answered after expiry'
           WHEN i.version<0 THEN 'the invitation version is negative'
         END
    INTO invalid_id, invalid_reason
    FROM match_invitation i
    JOIN ecosystem_match m ON m.id=i.match_id
    JOIN cooperation_demand d ON d.id=m.demand_id
    JOIN enterprise de ON de.id=d.enterprise_id
   WHERE i.association_id IS NULL
      OR i.recipient_enterprise_id IS NULL
      OR i.association_id<>de.association_id
      OR i.recipient_enterprise_id<>m.candidate_enterprise_id
      OR i.invitation_type NOT IN ('ENTERPRISE', 'ASSOCIATION_RECOMMENDATION')
      OR (i.invitation_type='ENTERPRISE'
          AND i.sender_enterprise_id IS DISTINCT FROM d.enterprise_id)
      OR (i.invitation_type='ASSOCIATION_RECOMMENDATION'
          AND i.sender_enterprise_id IS NOT NULL)
      OR (i.status IN ('ACCEPTED', 'REJECTED')
          AND (i.responded_by_subject IS NULL OR i.responded_at IS NULL))
      OR (i.status IN ('PENDING', 'EXPIRED', 'CANCELLED')
          AND (i.responded_by_subject IS NOT NULL OR i.responded_at IS NOT NULL))
      OR (i.status='REJECTED'
          AND nullif(btrim(i.response_comment), '') IS NULL)
      OR (i.status='CANCELLED'
          AND nullif(btrim(i.response_comment), '') IS NULL)
      OR (i.status IN ('PENDING', 'EXPIRED')
          AND i.response_comment IS NOT NULL)
      OR (i.responded_at IS NOT NULL AND i.responded_at<i.created_at)
      OR (i.expires_at IS NOT NULL AND i.expires_at<=i.created_at)
      OR (i.responded_at IS NOT NULL AND i.expires_at IS NOT NULL
          AND i.responded_at>i.expires_at)
      OR i.version<0
   ORDER BY i.id
   LIMIT 1;

  IF invalid_id IS NOT NULL THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='V18 cannot enforce the match invitation lifecycle',
      DETAIL=format('match_invitation %s is invalid: %s', invalid_id, invalid_reason),
      HINT='Correct the invitation participant, association, response metadata, rejection/cancellation reason or timestamps before retrying V18; do not manufacture a recipient decision.';
  END IF;
END
$migration$;

DO $migration$
DECLARE
  invalid_id UUID;
  invalid_reason TEXT;
BEGIN
  SELECT n.id,
         CASE
           WHEN n.association_id IS NULL THEN 'association_id is null'
           WHEN n.enterprise_id IS NULL AND n.association_id<>de.association_id
             THEN 'an association follow-up is not owned by the demand owner association'
           WHEN n.enterprise_id IS NOT NULL
                AND n.enterprise_id NOT IN (d.enterprise_id, m.candidate_enterprise_id)
             THEN 'enterprise_id is not a match participant'
           WHEN n.enterprise_id IS NOT NULL AND n.association_id<>ne.association_id
             THEN 'association_id does not own the recording enterprise'
           WHEN n.stage NOT IN (
                  'INITIAL_CONTACT', 'TECHNICAL_EXCHANGE', 'COMMERCIAL_NEGOTIATION',
                  'CONTRACTING', 'CONTRACT_SIGNED', 'TERMINATED')
             THEN 'the negotiation stage is unsupported'
           WHEN n.version<0 THEN 'the negotiation version is negative'
         END
    INTO invalid_id, invalid_reason
    FROM negotiation_record n
    JOIN ecosystem_match m ON m.id=n.match_id
    JOIN cooperation_demand d ON d.id=m.demand_id
    JOIN enterprise de ON de.id=d.enterprise_id
    LEFT JOIN enterprise ne ON ne.id=n.enterprise_id
   WHERE n.association_id IS NULL
      OR (n.enterprise_id IS NULL AND n.association_id<>de.association_id)
      OR (n.enterprise_id IS NOT NULL
          AND n.enterprise_id NOT IN (d.enterprise_id, m.candidate_enterprise_id))
      OR (n.enterprise_id IS NOT NULL AND n.association_id<>ne.association_id)
      OR n.stage NOT IN (
           'INITIAL_CONTACT', 'TECHNICAL_EXCHANGE', 'COMMERCIAL_NEGOTIATION',
           'CONTRACTING', 'CONTRACT_SIGNED', 'TERMINATED')
      OR n.version<0
   ORDER BY n.id
   LIMIT 1;

  IF invalid_id IS NOT NULL THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='V18 cannot enforce negotiation participant ownership',
      DETAIL=format('negotiation_record %s is invalid: %s', invalid_id, invalid_reason),
      HINT='Associate the record with a participating enterprise and its association, or with the demand owner association as an association follow-up, before retrying V18.';
  END IF;

  WITH ordered AS (
    SELECT n.id,
           n.match_id,
           n.stage,
           row_number() OVER (
             PARTITION BY n.match_id ORDER BY n.created_at, n.id) AS position,
           lag(n.stage) OVER (
             PARTITION BY n.match_id ORDER BY n.created_at, n.id) AS previous_stage
      FROM negotiation_record n
  ), invalid AS (
    SELECT id,
           CASE
             WHEN position=1 AND stage NOT IN ('INITIAL_CONTACT', 'TERMINATED')
               THEN 'the first stage is neither INITIAL_CONTACT nor TERMINATED'
             WHEN previous_stage IN ('CONTRACT_SIGNED', 'TERMINATED')
               THEN 'a record follows a terminal negotiation stage'
             WHEN stage='TERMINATED' THEN NULL
             WHEN previous_stage IS NULL THEN NULL
             WHEN (CASE stage
                     WHEN 'INITIAL_CONTACT' THEN 1
                     WHEN 'TECHNICAL_EXCHANGE' THEN 2
                     WHEN 'COMMERCIAL_NEGOTIATION' THEN 3
                     WHEN 'CONTRACTING' THEN 4
                     WHEN 'CONTRACT_SIGNED' THEN 5 END)
                  NOT BETWEEN
                    (CASE previous_stage
                       WHEN 'INITIAL_CONTACT' THEN 1
                       WHEN 'TECHNICAL_EXCHANGE' THEN 2
                       WHEN 'COMMERCIAL_NEGOTIATION' THEN 3
                       WHEN 'CONTRACTING' THEN 4 END)
                    AND
                    (CASE previous_stage
                       WHEN 'INITIAL_CONTACT' THEN 1
                       WHEN 'TECHNICAL_EXCHANGE' THEN 2
                       WHEN 'COMMERCIAL_NEGOTIATION' THEN 3
                       WHEN 'CONTRACTING' THEN 4 END)+1
               THEN 'the stage regresses or skips a stage'
           END AS reason
      FROM ordered
  )
  SELECT id, reason
    INTO invalid_id, invalid_reason
    FROM invalid
   WHERE reason IS NOT NULL
   ORDER BY id
   LIMIT 1;

  IF invalid_id IS NOT NULL THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='V18 cannot enforce negotiation stage ordering',
      DETAIL=format('negotiation_record %s is invalid: %s', invalid_id, invalid_reason),
      HINT='Start ordinary negotiation at INITIAL_CONTACT; an accepted invitation may instead terminate immediately. Otherwise remain at the current stage or advance by one, and remove records after CONTRACT_SIGNED or TERMINATED before retrying V18.';
  END IF;
END
$migration$;

DO $migration$
DECLARE
  invalid_id UUID;
  invalid_reason TEXT;
BEGIN
  SELECT f.id,
         CASE
           WHEN f.enterprise_id NOT IN (d.enterprise_id, m.candidate_enterprise_id)
             THEN 'enterprise_id is not a match participant'
           WHEN f.outcome NOT IN ('SUCCESS', 'NO_DEAL', 'WITHDRAWN')
             THEN 'the feedback outcome is unsupported'
           WHEN f.outcome='SUCCESS' AND f.close_reason IS NOT NULL
             THEN 'a successful outcome contains a close reason'
           WHEN f.outcome<>'SUCCESS' AND nullif(btrim(f.close_reason), '') IS NULL
             THEN 'an unsuccessful outcome has no close reason'
           WHEN f.version<0 THEN 'the feedback version is negative'
         END
    INTO invalid_id, invalid_reason
    FROM match_feedback f
    JOIN ecosystem_match m ON m.id=f.match_id
    JOIN cooperation_demand d ON d.id=m.demand_id
   WHERE f.enterprise_id NOT IN (d.enterprise_id, m.candidate_enterprise_id)
      OR f.outcome NOT IN ('SUCCESS', 'NO_DEAL', 'WITHDRAWN')
      OR (f.outcome='SUCCESS' AND f.close_reason IS NOT NULL)
      OR (f.outcome<>'SUCCESS' AND nullif(btrim(f.close_reason), '') IS NULL)
      OR f.version<0
   ORDER BY f.id
   LIMIT 1;

  IF invalid_id IS NOT NULL THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='V18 cannot enforce match feedback ownership',
      DETAIL=format('match_feedback %s is invalid: %s', invalid_id, invalid_reason),
      HINT='Keep feedback on one of the two participating enterprises, leave close_reason null for SUCCESS, and provide a verified non-empty close reason for NO_DEAL or WITHDRAWN before retrying V18.';
  END IF;

  SELECT o.id,
         CASE
           WHEN o.association_id IS NULL THEN 'association_id is null'
           WHEN o.association_id<>de.association_id
             THEN 'association_id is not the demand owner association'
           WHEN o.result_type NOT IN ('COOPERATION', 'CONTRACT', 'PILOT', 'TECHNICAL_RESULT')
             THEN 'the outcome result_type is unsupported'
           WHEN o.visibility NOT IN ('PRIVATE', 'ENTERPRISES', 'ASSOCIATION', 'PARTNERS', 'PUBLIC')
             THEN 'the outcome visibility is unsupported'
           WHEN o.contract_amount IS NOT NULL AND o.contract_amount<0
             THEN 'the outcome contract amount is negative'
           WHEN o.version<0 THEN 'the outcome version is negative'
         END
    INTO invalid_id, invalid_reason
    FROM outcome_archive o
    JOIN ecosystem_match m ON m.id=o.match_id
    JOIN cooperation_demand d ON d.id=m.demand_id
    JOIN enterprise de ON de.id=d.enterprise_id
   WHERE o.association_id IS NULL
      OR o.association_id<>de.association_id
      OR o.result_type NOT IN ('COOPERATION', 'CONTRACT', 'PILOT', 'TECHNICAL_RESULT')
      OR o.visibility NOT IN ('PRIVATE', 'ENTERPRISES', 'ASSOCIATION', 'PARTNERS', 'PUBLIC')
      OR (o.contract_amount IS NOT NULL AND o.contract_amount<0)
      OR o.version<0
   ORDER BY o.id
   LIMIT 1;

  IF invalid_id IS NOT NULL THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='V18 cannot enforce outcome archive ownership',
      DETAIL=format('outcome_archive %s is invalid: %s', invalid_id, invalid_reason),
      HINT='Assign the archive to the demand owner association after verifying the business result; V18 will not infer ownership.';
  END IF;
END
$migration$;

ALTER TABLE match_invitation
  ALTER COLUMN association_id SET NOT NULL,
  ALTER COLUMN recipient_enterprise_id SET NOT NULL;

ALTER TABLE negotiation_record
  ALTER COLUMN association_id SET NOT NULL;

ALTER TABLE outcome_archive
  ALTER COLUMN association_id SET NOT NULL;

ALTER TABLE ecosystem_match
  ADD CONSTRAINT ecosystem_match_version_v18_ck
    CHECK (version>=0) NOT VALID,
  ADD CONSTRAINT ecosystem_match_review_status_v18_ck
    CHECK (review_status IN ('PENDING', 'APPROVED', 'CLOSED')) NOT VALID,
  ADD CONSTRAINT ecosystem_match_review_pair_v18_ck
    CHECK ((reviewed_by IS NULL)=(reviewed_at IS NULL)) NOT VALID,
  ADD CONSTRAINT ecosystem_match_recommendation_pair_v18_ck
    CHECK ((recommended_by_subject IS NULL)=(recommended_at IS NULL)) NOT VALID,
  ADD CONSTRAINT ecosystem_match_demand_confirmation_pair_v18_ck
    CHECK ((demand_confirmed_by_subject IS NULL)=(demand_confirmed_at IS NULL)) NOT VALID,
  ADD CONSTRAINT ecosystem_match_candidate_confirmation_pair_v18_ck
    CHECK ((candidate_confirmed_by_subject IS NULL)=(candidate_confirmed_at IS NULL)) NOT VALID,
  ADD CONSTRAINT ecosystem_match_state_facts_v18_ck CHECK (
    CASE state
      WHEN 'PENDING_CONFIRMATION' THEN
        recommended_at IS NULL
        AND demand_confirmed_at IS NULL
        AND candidate_confirmed_at IS NULL
        AND review_status='PENDING'
      WHEN 'RECOMMENDED' THEN
        recommended_at IS NOT NULL
        AND demand_confirmed_at IS NULL
        AND candidate_confirmed_at IS NULL
        AND review_status='APPROVED'
      WHEN 'PARTIALLY_CONFIRMED' THEN
        recommended_at IS NOT NULL
        AND ((demand_confirmed_at IS NOT NULL)::integer
           + (candidate_confirmed_at IS NOT NULL)::integer)=1
        AND review_status='APPROVED'
      WHEN 'CLOSED' THEN
        nullif(btrim(closed_reason), '') IS NOT NULL
        AND review_status='CLOSED'
      ELSE
        recommended_at IS NOT NULL
        AND demand_confirmed_at IS NOT NULL
        AND candidate_confirmed_at IS NOT NULL
        AND review_status='APPROVED'
    END
  ) NOT VALID,
  ADD CONSTRAINT ecosystem_match_confirmation_after_recommendation_v18_ck CHECK (
    recommended_at IS NULL
    OR ((demand_confirmed_at IS NULL OR demand_confirmed_at>=recommended_at)
      AND (candidate_confirmed_at IS NULL OR candidate_confirmed_at>=recommended_at))
  ) NOT VALID,
  ADD CONSTRAINT ecosystem_match_close_reason_v18_ck CHECK (
    (state='CLOSED')=(nullif(btrim(closed_reason), '') IS NOT NULL)
  ) NOT VALID;

ALTER TABLE match_invitation
  ADD CONSTRAINT match_invitation_version_v18_ck
    CHECK (version>=0) NOT VALID,
  ADD CONSTRAINT match_invitation_response_lifecycle_v18_ck CHECK (
    (status IN ('ACCEPTED', 'REJECTED')
      AND responded_by_subject IS NOT NULL AND responded_at IS NOT NULL)
    OR
    (status IN ('PENDING', 'EXPIRED', 'CANCELLED')
      AND responded_by_subject IS NULL AND responded_at IS NULL)
  ) NOT VALID,
  ADD CONSTRAINT match_invitation_time_v18_ck CHECK (
    (expires_at IS NULL OR expires_at>created_at)
    AND (responded_at IS NULL OR responded_at>=created_at)
    AND (responded_at IS NULL OR expires_at IS NULL OR responded_at<=expires_at)
  ) NOT VALID,
  ADD CONSTRAINT match_invitation_reason_v18_ck CHECK (
    (status IN ('PENDING', 'EXPIRED') AND response_comment IS NULL)
    OR status='ACCEPTED'
    OR (status IN ('REJECTED', 'CANCELLED')
        AND nullif(btrim(response_comment), '') IS NOT NULL)
  ) NOT VALID;

ALTER TABLE negotiation_record
  ADD CONSTRAINT negotiation_record_version_v18_ck
    CHECK (version>=0) NOT VALID;

ALTER TABLE match_feedback
  ADD CONSTRAINT match_feedback_version_v18_ck
    CHECK (version>=0) NOT VALID,
  ADD CONSTRAINT match_feedback_close_reason_v18_ck CHECK (
    (outcome='SUCCESS' AND close_reason IS NULL)
    OR
    (outcome IN ('NO_DEAL', 'WITHDRAWN')
      AND nullif(btrim(close_reason), '') IS NOT NULL)
  ) NOT VALID;

ALTER TABLE outcome_archive
  ADD CONSTRAINT outcome_archive_version_v18_ck
    CHECK (version>=0) NOT VALID,
  ADD CONSTRAINT outcome_archive_visibility_v18_ck CHECK (
    visibility IN ('PRIVATE', 'ENTERPRISES', 'ASSOCIATION', 'PARTNERS', 'PUBLIC')
  ) NOT VALID,
  ADD CONSTRAINT outcome_archive_amount_v18_ck CHECK (
    contract_amount IS NULL OR contract_amount>=0
  ) NOT VALID;

ALTER TABLE ecosystem_match
  VALIDATE CONSTRAINT ecosystem_match_version_v18_ck,
  VALIDATE CONSTRAINT ecosystem_match_review_status_v18_ck,
  VALIDATE CONSTRAINT ecosystem_match_review_pair_v18_ck,
  VALIDATE CONSTRAINT ecosystem_match_recommendation_pair_v18_ck,
  VALIDATE CONSTRAINT ecosystem_match_demand_confirmation_pair_v18_ck,
  VALIDATE CONSTRAINT ecosystem_match_candidate_confirmation_pair_v18_ck,
  VALIDATE CONSTRAINT ecosystem_match_state_facts_v18_ck,
  VALIDATE CONSTRAINT ecosystem_match_confirmation_after_recommendation_v18_ck,
  VALIDATE CONSTRAINT ecosystem_match_close_reason_v18_ck;

ALTER TABLE match_invitation
  VALIDATE CONSTRAINT match_invitation_version_v18_ck,
  VALIDATE CONSTRAINT match_invitation_response_lifecycle_v18_ck,
  VALIDATE CONSTRAINT match_invitation_time_v18_ck,
  VALIDATE CONSTRAINT match_invitation_reason_v18_ck;

ALTER TABLE negotiation_record
  VALIDATE CONSTRAINT negotiation_record_version_v18_ck;

ALTER TABLE match_feedback
  VALIDATE CONSTRAINT match_feedback_version_v18_ck,
  VALIDATE CONSTRAINT match_feedback_close_reason_v18_ck;

ALTER TABLE outcome_archive
  VALIDATE CONSTRAINT outcome_archive_version_v18_ck,
  VALIDATE CONSTRAINT outcome_archive_visibility_v18_ck,
  VALIDATE CONSTRAINT outcome_archive_amount_v18_ck;

-- V10 added these checks as NOT VALID to protect new writes while permitting a
-- staged cleanup.  The actionable preflight above completes that cleanup gate.
ALTER TABLE ecosystem_match
  VALIDATE CONSTRAINT ecosystem_match_state_v10_ck,
  VALIDATE CONSTRAINT ecosystem_match_bilateral_confirmation_v10_ck;

ALTER TABLE match_invitation
  VALIDATE CONSTRAINT match_invitation_type_v10_ck;

ALTER TABLE negotiation_record
  VALIDATE CONSTRAINT negotiation_stage_v10_ck;

ALTER TABLE match_feedback
  VALIDATE CONSTRAINT match_feedback_outcome_v10_ck,
  VALIDATE CONSTRAINT match_feedback_close_reason_v10_ck;

ALTER TABLE outcome_archive
  VALIDATE CONSTRAINT outcome_archive_result_type_v10_ck;

-- PARTNERS sharing still uses the MATCH resource boundary.  V18 extends its
-- explicit allow-list with the archived outcome projection; every V17-valid
-- policy remains valid because this change only widens that allow-list.
ALTER TABLE association_share_policy
  DROP CONSTRAINT association_share_policy_visible_fields_ck;

ALTER TABLE association_share_policy
  ADD CONSTRAINT association_share_policy_visible_fields_ck
    CHECK (
      CASE
        WHEN jsonb_typeof(visible_fields) <> 'array' THEN FALSE
        WHEN jsonb_array_length(visible_fields) = 0 THEN FALSE
        WHEN resource_type = 'MEMBER' THEN
          visible_fields <@ '["name","category","address","introduction","capabilities","products","cooperationNeeds"]'::jsonb
          AND visible_fields @> '["name"]'::jsonb
        WHEN resource_type IN ('PRODUCT', 'SERVICE') THEN
          visible_fields <@ '["enterpriseName","name","description","scenarios","qualifications"]'::jsonb
          AND visible_fields @> '["name"]'::jsonb
        WHEN resource_type = 'DEMAND' THEN
          visible_fields <@ '["enterpriseName","title","description","scenarios","requiredCapabilities","budgetMin","budgetMax","responseDeadline"]'::jsonb
          AND visible_fields @> '["title"]'::jsonb
        WHEN resource_type = 'MATCH' THEN
          visible_fields <@ '["demandCompany","demandTitle","scene","supplierCompany","solution","score","reasons","state","outcomes"]'::jsonb
        ELSE FALSE
      END
    ) NOT VALID;

ALTER TABLE association_share_policy
  VALIDATE CONSTRAINT association_share_policy_visible_fields_ck;

CREATE OR REPLACE FUNCTION guanxian_match_version_transition_v18()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.demand_id IS DISTINCT FROM OLD.demand_id
     OR NEW.candidate_enterprise_id IS DISTINCT FROM OLD.candidate_enterprise_id
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='match participants are immutable after creation',
      DETAIL=format('ecosystem_match %s attempted to change its demand or candidate', OLD.id),
      HINT='Create a new match after verifying the replacement participants; do not rewrite workflow history.';
  END IF;

  IF (OLD.reviewed_by IS NOT NULL
        AND (NEW.reviewed_by IS DISTINCT FROM OLD.reviewed_by
          OR NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at))
     OR (OLD.recommended_by_subject IS NOT NULL
        AND (NEW.recommended_by_subject IS DISTINCT FROM OLD.recommended_by_subject
          OR NEW.recommended_at IS DISTINCT FROM OLD.recommended_at))
     OR (OLD.demand_confirmed_by_subject IS NOT NULL
        AND (NEW.demand_confirmed_by_subject IS DISTINCT FROM OLD.demand_confirmed_by_subject
          OR NEW.demand_confirmed_at IS DISTINCT FROM OLD.demand_confirmed_at))
     OR (OLD.candidate_confirmed_by_subject IS NOT NULL
        AND (NEW.candidate_confirmed_by_subject IS DISTINCT FROM OLD.candidate_confirmed_by_subject
          OR NEW.candidate_confirmed_at IS DISTINCT FROM OLD.candidate_confirmed_at))
     OR (OLD.closed_reason IS NOT NULL
        AND NEW.closed_reason IS DISTINCT FROM OLD.closed_reason) THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='established match review facts are immutable',
      DETAIL=format('ecosystem_match %s attempted to rewrite an existing review, recommendation, confirmation or closure fact', OLD.id),
      HINT='Append a separately audited correction; do not replace the actor, timestamp or reason already recorded by the workflow.';
  END IF;

  IF NEW.version<>OLD.version+1 THEN
    RAISE EXCEPTION USING
      ERRCODE='40001',
      MESSAGE='ecosystem match version must advance by exactly one',
      DETAIL=format('ecosystem_match %s expected version %s but received %s',
                    OLD.id, OLD.version+1, NEW.version),
      HINT='Reload the current ETag and retry the update with compare-and-set semantics.';
  END IF;

  IF NEW.updated_at<OLD.updated_at THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='ecosystem match updated_at cannot move backwards',
      DETAIL=format('ecosystem_match %s has an invalid update timestamp', OLD.id),
      HINT='Use the database transaction timestamp for the new workflow revision.';
  END IF;

  IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
       (OLD.state='PENDING_CONFIRMATION' AND NEW.state IN ('RECOMMENDED', 'CLOSED'))
    OR (OLD.state='RECOMMENDED' AND NEW.state IN ('PARTIALLY_CONFIRMED', 'CLOSED'))
    OR (OLD.state='PARTIALLY_CONFIRMED' AND NEW.state IN ('CONFIRMED', 'CLOSED'))
    OR (OLD.state='CONFIRMED' AND NEW.state IN ('INVITED', 'CLOSED'))
    OR (OLD.state='INVITED' AND NEW.state IN ('CONFIRMED', 'NEGOTIATING', 'CLOSED'))
    OR (OLD.state='NEGOTIATING' AND NEW.state IN ('OUTCOME_PENDING', 'CLOSED'))
    OR (OLD.state='OUTCOME_PENDING' AND NEW.state IN ('ARCHIVED', 'CLOSED'))
  ) THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='illegal ecosystem match state transition',
      DETAIL=format('ecosystem_match %s cannot transition from %s to %s',
                    OLD.id, OLD.state, NEW.state),
      HINT='Follow recommendation, bilateral confirmation, invitation, negotiation, feedback and outcome stages in order.';
  END IF;

  RETURN NEW;
END
$function$;

CREATE TRIGGER ecosystem_match_version_transition_v18_trg
BEFORE UPDATE ON ecosystem_match
FOR EACH ROW
EXECUTE FUNCTION guanxian_match_version_transition_v18();

CREATE OR REPLACE FUNCTION guanxian_invitation_transition_v18()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW.status<>'PENDING' OR NEW.version<>0 THEN
      RAISE EXCEPTION USING
        ERRCODE='23514',
        MESSAGE='a match invitation must start at PENDING version zero',
        DETAIL=format('match_invitation %s starts at status %s version %s',
                      NEW.id, NEW.status, NEW.version),
        HINT='Create a pending invitation, then resolve it through the recipient response or expiry workflow.';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.match_id IS DISTINCT FROM OLD.match_id
     OR NEW.association_id IS DISTINCT FROM OLD.association_id
     OR NEW.sender_enterprise_id IS DISTINCT FROM OLD.sender_enterprise_id
     OR NEW.recipient_enterprise_id IS DISTINCT FROM OLD.recipient_enterprise_id
     OR NEW.invitation_type IS DISTINCT FROM OLD.invitation_type
     OR NEW.message IS DISTINCT FROM OLD.message
     OR NEW.sent_by_subject IS DISTINCT FROM OLD.sent_by_subject
     OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='match invitation request facts are immutable',
      DETAIL=format('match_invitation %s attempted to change its identity, request message, sender or validity window', OLD.id),
      HINT='Resolve the pending invitation and create a new invitation when its participants or request facts must change.';
  END IF;

  IF NEW.version<>OLD.version+1 THEN
    RAISE EXCEPTION USING
      ERRCODE='40001',
      MESSAGE='match invitation version must advance by exactly one',
      DETAIL=format('match_invitation %s expected version %s but received %s',
                    OLD.id, OLD.version+1, NEW.version),
      HINT='Reload the invitation ETag and retry with compare-and-set semantics.';
  END IF;

  IF OLD.status<>'PENDING'
     OR NEW.status NOT IN ('ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED') THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='illegal match invitation status transition',
      DETAIL=format('match_invitation %s cannot transition from %s to %s',
                    OLD.id, OLD.status, NEW.status),
      HINT='Only a pending invitation can be accepted, rejected, expired or cancelled; terminal decisions are immutable.';
  END IF;

  IF NEW.status='EXPIRED'
     AND NEW.response_comment IS DISTINCT FROM OLD.response_comment THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='an expired invitation cannot acquire a response comment',
      DETAIL=format('match_invitation %s attempted to store recipient or cancellation text while expiring', OLD.id),
      HINT='Expiry only advances status, version and updated_at; use REJECTED or CANCELLED for a verified reason.';
  END IF;

  IF NEW.updated_at<OLD.updated_at THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='match invitation updated_at cannot move backwards',
      DETAIL=format('match_invitation %s moved updated_at from %s to %s',
                    OLD.id, OLD.updated_at, NEW.updated_at),
      HINT='Use the current transaction timestamp when resolving a pending invitation.';
  END IF;

  RETURN NEW;
END
$function$;

CREATE TRIGGER match_invitation_transition_v18_trg
BEFORE INSERT OR UPDATE ON match_invitation
FOR EACH ROW
EXECUTE FUNCTION guanxian_invitation_transition_v18();

CREATE OR REPLACE FUNCTION guanxian_negotiation_insert_v18()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  match_state TEXT;
  demand_enterprise UUID;
  candidate_enterprise UUID;
  demand_association UUID;
  record_association UUID;
  previous_stage TEXT;
  previous_rank INTEGER;
  requested_rank INTEGER;
BEGIN
  IF NEW.version<>0 THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='a negotiation record must start at version zero',
      DETAIL=format('negotiation_record %s starts at version %s', NEW.id, NEW.version),
      HINT='Negotiation history is append-only; create a new version-zero record.';
  END IF;

  SELECT m.state, d.enterprise_id, m.candidate_enterprise_id, de.association_id
    INTO match_state, demand_enterprise, candidate_enterprise, demand_association
    FROM ecosystem_match m
    JOIN cooperation_demand d ON d.id=m.demand_id
    JOIN enterprise de ON de.id=d.enterprise_id
   WHERE m.id=NEW.match_id
   FOR UPDATE OF m;

  IF match_state IS NULL OR match_state<>'NEGOTIATING' THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='negotiation can only be recorded while the match is NEGOTIATING',
      DETAIL=format('negotiation_record %s targets match %s in state %s',
                    NEW.id, NEW.match_id, coalesce(match_state, '<missing>')),
      HINT='Accept the live invitation and transition the match to NEGOTIATING before recording follow-up.';
  END IF;

  IF NEW.enterprise_id IS NULL THEN
    IF NEW.association_id<>demand_association THEN
      RAISE EXCEPTION USING
        ERRCODE='23514',
        MESSAGE='association follow-up belongs to the demand owner association',
        DETAIL=format('negotiation_record %s uses association %s instead of %s',
                      NEW.id, NEW.association_id, demand_association),
        HINT='Use the demand owner association context for headquarters follow-up.';
    END IF;
  ELSE
    SELECT association_id INTO record_association
      FROM enterprise WHERE id=NEW.enterprise_id;
    IF NEW.enterprise_id NOT IN (demand_enterprise, candidate_enterprise)
       OR NEW.association_id IS DISTINCT FROM record_association THEN
      RAISE EXCEPTION USING
        ERRCODE='23514',
        MESSAGE='negotiation enterprise must be a participant in its own association',
        DETAIL=format('negotiation_record %s has enterprise %s and association %s',
                      NEW.id, NEW.enterprise_id, NEW.association_id),
        HINT='Use the demand enterprise, candidate enterprise, or a demand-association headquarters record.';
    END IF;
  END IF;

  SELECT stage
    INTO previous_stage
    FROM negotiation_record
   WHERE match_id=NEW.match_id
   ORDER BY created_at DESC, id DESC
   LIMIT 1;

  IF previous_stage IS NULL
     AND NEW.stage NOT IN ('INITIAL_CONTACT', 'TERMINATED') THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='negotiation must start at INITIAL_CONTACT or terminate immediately',
      DETAIL=format('negotiation_record %s attempted to start at %s', NEW.id, NEW.stage),
      HINT='Record INITIAL_CONTACT before later negotiation stages, or use TERMINATED when an accepted invitation ends before contact begins.';
  END IF;

  IF previous_stage IN ('CONTRACT_SIGNED', 'TERMINATED') THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='negotiation history is already terminal',
      DETAIL=format('negotiation_record %s follows terminal stage %s', NEW.id, previous_stage),
      HINT='Do not append negotiation events after contract signing or termination.';
  END IF;

  IF previous_stage IS NOT NULL AND NEW.stage<>'TERMINATED' THEN
    previous_rank=CASE previous_stage
      WHEN 'INITIAL_CONTACT' THEN 1
      WHEN 'TECHNICAL_EXCHANGE' THEN 2
      WHEN 'COMMERCIAL_NEGOTIATION' THEN 3
      WHEN 'CONTRACTING' THEN 4
    END;
    requested_rank=CASE NEW.stage
      WHEN 'INITIAL_CONTACT' THEN 1
      WHEN 'TECHNICAL_EXCHANGE' THEN 2
      WHEN 'COMMERCIAL_NEGOTIATION' THEN 3
      WHEN 'CONTRACTING' THEN 4
      WHEN 'CONTRACT_SIGNED' THEN 5
    END;
    IF requested_rank IS NULL
       OR requested_rank<previous_rank
       OR requested_rank>previous_rank+1 THEN
      RAISE EXCEPTION USING
        ERRCODE='23514',
        MESSAGE='negotiation stage cannot regress or skip a stage',
        DETAIL=format('negotiation_record %s attempted %s after %s',
                      NEW.id, NEW.stage, previous_stage),
        HINT='Remain at the current stage or advance by exactly one stage.';
    END IF;
  END IF;

  RETURN NEW;
END
$function$;

CREATE TRIGGER negotiation_record_insert_v18_trg
BEFORE INSERT ON negotiation_record
FOR EACH ROW
EXECUTE FUNCTION guanxian_negotiation_insert_v18();

CREATE OR REPLACE FUNCTION guanxian_negotiation_append_only_v18()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  RAISE EXCEPTION USING
    ERRCODE='23514',
    MESSAGE='negotiation history is append-only',
    DETAIL=format('negotiation_record %s cannot be %s', OLD.id, lower(TG_OP)),
    HINT='Append a new negotiation record; retain the prior event for audit history.';
END
$function$;

CREATE TRIGGER negotiation_record_append_only_v18_trg
BEFORE UPDATE OR DELETE ON negotiation_record
FOR EACH ROW
EXECUTE FUNCTION guanxian_negotiation_append_only_v18();

CREATE OR REPLACE FUNCTION guanxian_feedback_write_v18()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  match_state TEXT;
  demand_enterprise UUID;
  candidate_enterprise UUID;
BEGIN
  SELECT m.state, d.enterprise_id, m.candidate_enterprise_id
    INTO match_state, demand_enterprise, candidate_enterprise
    FROM ecosystem_match m
    JOIN cooperation_demand d ON d.id=m.demand_id
   WHERE m.id=NEW.match_id
   FOR UPDATE OF m;

  IF NEW.enterprise_id NOT IN (demand_enterprise, candidate_enterprise) THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='feedback enterprise is not a match participant',
      DETAIL=format('match_feedback %s uses enterprise %s', NEW.id, NEW.enterprise_id),
      HINT='Submit feedback as the demand or candidate enterprise.';
  END IF;

  IF (NEW.outcome='SUCCESS' AND match_state<>'OUTCOME_PENDING')
     OR (NEW.outcome<>'SUCCESS' AND match_state<>'CLOSED') THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='feedback outcome does not match the workflow stage',
      DETAIL=format('match_feedback %s outcome %s targets match state %s',
                    NEW.id, NEW.outcome, coalesce(match_state, '<missing>')),
      HINT='Submit SUCCESS while the signed outcome is pending, or an unsuccessful result after the match is closed.';
  END IF;

  IF TG_OP='INSERT' AND NEW.version<>0 THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='match feedback must start at version zero',
      DETAIL=format('match_feedback %s starts at version %s', NEW.id, NEW.version),
      HINT='Create the first feedback revision at version zero.';
  ELSIF TG_OP='UPDATE' THEN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.match_id IS DISTINCT FROM OLD.match_id
       OR NEW.enterprise_id IS DISTINCT FROM OLD.enterprise_id THEN
      RAISE EXCEPTION USING
        ERRCODE='23514',
        MESSAGE='match feedback identity is immutable',
        DETAIL=format('match_feedback %s attempted to change match or enterprise', OLD.id),
        HINT='Update the participant feedback in place with its ETag.';
    END IF;
    IF NEW.version<>OLD.version+1 THEN
      RAISE EXCEPTION USING
        ERRCODE='40001',
        MESSAGE='match feedback version must advance by exactly one',
        DETAIL=format('match_feedback %s expected version %s but received %s',
                      OLD.id, OLD.version+1, NEW.version),
        HINT='Reload the feedback ETag and retry with compare-and-set semantics.';
    END IF;
    IF NEW.updated_at<OLD.updated_at THEN
      RAISE EXCEPTION USING
        ERRCODE='23514',
        MESSAGE='match feedback updated_at cannot move backwards',
        DETAIL=format('match_feedback %s has an invalid update timestamp', OLD.id),
        HINT='Use the database transaction timestamp for the new feedback revision.';
    END IF;
  END IF;

  RETURN NEW;
END
$function$;

CREATE TRIGGER match_feedback_write_v18_trg
BEFORE INSERT OR UPDATE ON match_feedback
FOR EACH ROW
EXECUTE FUNCTION guanxian_feedback_write_v18();

CREATE OR REPLACE FUNCTION guanxian_outcome_write_v18()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  match_state TEXT;
  demand_enterprise UUID;
  candidate_enterprise UUID;
  demand_association UUID;
  successful_participants INTEGER;
BEGIN
  IF TG_OP='UPDATE' THEN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.match_id IS DISTINCT FROM OLD.match_id
       OR NEW.association_id IS DISTINCT FROM OLD.association_id
       OR NEW.archived_by_subject IS DISTINCT FROM OLD.archived_by_subject
       OR NEW.archived_at IS DISTINCT FROM OLD.archived_at THEN
      RAISE EXCEPTION USING
        ERRCODE='23514',
        MESSAGE='outcome archive ownership and archival facts are immutable',
        DETAIL=format('outcome_archive %s attempted to change match, association, archival actor or archival timestamp', OLD.id),
        HINT='Keep the archived result attached to its original match, association and archival event; edit only versioned business fields.';
    END IF;
    IF NEW.version<>OLD.version+1 THEN
      RAISE EXCEPTION USING
        ERRCODE='40001',
        MESSAGE='outcome archive version must advance by exactly one',
        DETAIL=format('outcome_archive %s expected version %s but received %s',
                      OLD.id, OLD.version+1, NEW.version),
        HINT='Reload the outcome ETag and retry with compare-and-set semantics.';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.version<>0 THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='outcome archive must start at version zero',
      DETAIL=format('outcome_archive %s starts at version %s', NEW.id, NEW.version),
      HINT='Create the first archived outcome at version zero.';
  END IF;

  SELECT m.state, d.enterprise_id, m.candidate_enterprise_id, de.association_id
    INTO match_state, demand_enterprise, candidate_enterprise, demand_association
    FROM ecosystem_match m
    JOIN cooperation_demand d ON d.id=m.demand_id
    JOIN enterprise de ON de.id=d.enterprise_id
   WHERE m.id=NEW.match_id
   FOR UPDATE OF m;

  SELECT count(DISTINCT enterprise_id)
    INTO successful_participants
    FROM match_feedback
   WHERE match_id=NEW.match_id
     AND outcome='SUCCESS'
     AND enterprise_id IN (demand_enterprise, candidate_enterprise);

  IF match_state<>'OUTCOME_PENDING'
     OR NEW.association_id<>demand_association
     OR successful_participants<>2 THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='outcome archive prerequisites are incomplete',
      DETAIL=format('outcome_archive %s sees match state %s, association %s and %s successful participants',
                    NEW.id, coalesce(match_state, '<missing>'), NEW.association_id,
                    successful_participants),
      HINT='Reach CONTRACT_SIGNED, collect SUCCESS feedback from both distinct enterprises, and archive under the demand owner association.';
  END IF;

  RETURN NEW;
END
$function$;

CREATE TRIGGER outcome_archive_write_v18_trg
BEFORE INSERT OR UPDATE ON outcome_archive
FOR EACH ROW
EXECUTE FUNCTION guanxian_outcome_write_v18();

CREATE OR REPLACE FUNCTION guanxian_validate_match_workflow_v18(match_uuid UUID)
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE
  match_state TEXT;
  demand_enterprise UUID;
  candidate_enterprise UUID;
  demand_association UUID;
  match_close_reason TEXT;
  pending_invitations INTEGER;
  accepted_invitations INTEGER;
  rejected_invitations INTEGER;
  negotiation_count INTEGER;
  signed_count INTEGER;
  terminated_count INTEGER;
  successful_participants INTEGER;
  unsuccessful_feedback INTEGER;
  active_outcomes INTEGER;
BEGIN
  SELECT m.state, d.enterprise_id, m.candidate_enterprise_id, de.association_id,
         m.closed_reason
    INTO match_state, demand_enterprise, candidate_enterprise, demand_association,
         match_close_reason
    FROM ecosystem_match m
    JOIN cooperation_demand d ON d.id=m.demand_id
    JOIN enterprise de ON de.id=d.enterprise_id
   WHERE m.id=match_uuid;

  IF NOT FOUND THEN
    RETURN;
  END IF;

  IF demand_enterprise=candidate_enterprise THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='a match requires two distinct enterprises',
      DETAIL=format('ecosystem_match %s uses enterprise %s on both sides',
                    match_uuid, demand_enterprise),
      HINT='Create a new match with a different candidate enterprise.';
  END IF;

  IF EXISTS (
    SELECT 1
      FROM match_invitation i
      LEFT JOIN enterprise sender ON sender.id=i.sender_enterprise_id
     WHERE i.match_id=match_uuid
       AND (i.association_id<>demand_association
         OR i.recipient_enterprise_id<>candidate_enterprise
         OR (i.invitation_type='ENTERPRISE'
             AND i.sender_enterprise_id IS DISTINCT FROM demand_enterprise)
         OR (i.invitation_type='ASSOCIATION_RECOMMENDATION'
             AND i.sender_enterprise_id IS NOT NULL))) THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='match invitation participants do not match the parent workflow',
      DETAIL=format('ecosystem_match %s has an invitation with invalid ownership', match_uuid),
      HINT='Use the demand owner or its association as sender and the candidate enterprise as recipient.';
  END IF;

  IF EXISTS (
    SELECT 1
      FROM negotiation_record n
      LEFT JOIN enterprise ne ON ne.id=n.enterprise_id
     WHERE n.match_id=match_uuid
       AND ((n.enterprise_id IS NULL AND n.association_id<>demand_association)
         OR (n.enterprise_id IS NOT NULL
             AND (n.enterprise_id NOT IN (demand_enterprise, candidate_enterprise)
               OR n.association_id IS DISTINCT FROM ne.association_id)))) THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='negotiation ownership does not match the parent workflow',
      DETAIL=format('ecosystem_match %s has a negotiation record outside its participants', match_uuid),
      HINT='Use a participating enterprise or demand-association headquarters context.';
  END IF;

  IF EXISTS (
    SELECT 1 FROM match_feedback f
     WHERE f.match_id=match_uuid
       AND f.enterprise_id NOT IN (demand_enterprise, candidate_enterprise)) THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='feedback ownership does not match the parent workflow',
      DETAIL=format('ecosystem_match %s has feedback from a non-participant', match_uuid),
      HINT='Retain feedback only from the demand and candidate enterprises.';
  END IF;

  IF EXISTS (
    SELECT 1 FROM outcome_archive o
     WHERE o.match_id=match_uuid
       AND o.association_id<>demand_association) THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='outcome archive ownership does not match the parent workflow',
      DETAIL=format('ecosystem_match %s has an outcome outside the demand association', match_uuid),
      HINT='Archive the verified result under the demand owner association.';
  END IF;

  SELECT count(*) FILTER (WHERE status='PENDING'),
         count(*) FILTER (WHERE status='ACCEPTED'),
         count(*) FILTER (WHERE status='REJECTED')
    INTO pending_invitations, accepted_invitations, rejected_invitations
    FROM match_invitation
   WHERE match_id=match_uuid;

  IF (match_state='INVITED' AND pending_invitations<>1)
     OR (match_state<>'INVITED' AND pending_invitations<>0) THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='pending invitation does not match the parent state',
      DETAIL=format('ecosystem_match %s state %s has %s pending invitations',
                    match_uuid, match_state, pending_invitations),
      HINT='INVITED requires exactly one PENDING invitation; expire or cancel pending invitations before leaving INVITED.';
  END IF;

  IF match_state IN ('NEGOTIATING', 'OUTCOME_PENDING', 'ARCHIVED')
     AND accepted_invitations<1 THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='advanced workflow has no accepted invitation',
      DETAIL=format('ecosystem_match %s is %s without an ACCEPTED invitation',
                    match_uuid, match_state),
      HINT='The candidate must accept the live invitation before negotiation starts.';
  END IF;

  IF rejected_invitations>0 AND match_state<>'CLOSED' THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='a rejected invitation must close the match',
      DETAIL=format('ecosystem_match %s is %s despite a REJECTED invitation',
                    match_uuid, match_state),
      HINT='Transition the match to CLOSED with the verified rejection reason in the same transaction.';
  END IF;

  IF EXISTS (
    SELECT 1 FROM match_invitation i
     WHERE i.match_id=match_uuid
       AND i.status IN ('REJECTED', 'CANCELLED')
       AND (match_state<>'CLOSED'
         OR btrim(i.response_comment) IS DISTINCT FROM btrim(match_close_reason))) THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='invitation close reason conflicts with the parent match',
      DETAIL=format('ecosystem_match %s has a rejected or cancelled invitation with a different reason',
                    match_uuid),
      HINT='Close the match atomically with the same verified trimmed reason recorded on the rejected or cancelled invitation.';
  END IF;

  SELECT count(*),
         count(*) FILTER (WHERE stage='CONTRACT_SIGNED'),
         count(*) FILTER (WHERE stage='TERMINATED')
    INTO negotiation_count, signed_count, terminated_count
    FROM negotiation_record
   WHERE match_id=match_uuid;

  IF negotiation_count>0
     AND (accepted_invitations<1
          OR match_state NOT IN ('NEGOTIATING', 'OUTCOME_PENDING', 'ARCHIVED', 'CLOSED')) THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='negotiation records bypass the invitation stage',
      DETAIL=format('ecosystem_match %s is %s with %s negotiation records and %s accepted invitations',
                    match_uuid, match_state, negotiation_count, accepted_invitations),
      HINT='Accept an invitation before appending negotiation history.';
  END IF;

  IF match_state IN ('OUTCOME_PENDING', 'ARCHIVED') AND signed_count<>1 THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='outcome stage requires one CONTRACT_SIGNED record',
      DETAIL=format('ecosystem_match %s state %s has %s contract-signing records',
                    match_uuid, match_state, signed_count),
      HINT='Advance the ordered negotiation to CONTRACT_SIGNED exactly once before recording an outcome.';
  END IF;

  IF terminated_count>0 AND match_state<>'CLOSED' THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='terminated negotiation must close the match',
      DETAIL=format('ecosystem_match %s is %s despite a TERMINATED record',
                    match_uuid, match_state),
      HINT='Transition the match to CLOSED with the termination summary in the same transaction.';
  END IF;

  IF EXISTS (
    SELECT 1 FROM negotiation_record n
     WHERE n.match_id=match_uuid
       AND n.stage='TERMINATED'
       AND (match_state<>'CLOSED'
         OR btrim(n.summary) IS DISTINCT FROM btrim(match_close_reason))) THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='termination summary conflicts with the parent close reason',
      DETAIL=format('ecosystem_match %s has a TERMINATED negotiation with a different reason',
                    match_uuid),
      HINT='Close the match atomically with the same verified trimmed reason used in the TERMINATED negotiation summary.';
  END IF;

  SELECT count(DISTINCT enterprise_id) FILTER (WHERE outcome='SUCCESS'),
         count(*) FILTER (WHERE outcome<>'SUCCESS')
    INTO successful_participants, unsuccessful_feedback
    FROM match_feedback
   WHERE match_id=match_uuid
     AND enterprise_id IN (demand_enterprise, candidate_enterprise);

  IF unsuccessful_feedback>0 AND match_state<>'CLOSED' THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='unsuccessful feedback belongs to a closed match',
      DETAIL=format('ecosystem_match %s is %s with unsuccessful feedback',
                    match_uuid, match_state),
      HINT='Close the match with its verified reason before storing NO_DEAL or WITHDRAWN feedback.';
  END IF;

  SELECT count(*) INTO active_outcomes
    FROM outcome_archive
   WHERE match_id=match_uuid AND deleted_at IS NULL;

  IF active_outcomes>0 AND match_state<>'ARCHIVED' THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='an active outcome requires ARCHIVED match state',
      DETAIL=format('ecosystem_match %s is %s with %s active outcomes',
                    match_uuid, match_state, active_outcomes),
      HINT='Archive the match and its verified outcome atomically.';
  END IF;

  IF match_state='ARCHIVED'
     AND (active_outcomes<>1 OR successful_participants<>2) THEN
    RAISE EXCEPTION USING
      ERRCODE='23514',
      MESSAGE='archived match is missing bilateral success or its active outcome',
      DETAIL=format('ecosystem_match %s has %s successful participants and %s active outcomes',
                    match_uuid, successful_participants, active_outcomes),
      HINT='Collect SUCCESS feedback from both distinct enterprises and create exactly one active outcome before ARCHIVED.';
  END IF;
END
$function$;

DO $migration$
DECLARE
  match_id_to_check UUID;
BEGIN
  FOR match_id_to_check IN SELECT id FROM ecosystem_match ORDER BY id LOOP
    PERFORM guanxian_validate_match_workflow_v18(match_id_to_check);
  END LOOP;
EXCEPTION
  WHEN OTHERS THEN
    RAISE EXCEPTION USING
      ERRCODE=SQLSTATE,
      MESSAGE='V18 cannot enforce the cross-table ecosystem match workflow',
      DETAIL=SQLERRM,
      HINT='Review invitation, negotiation, feedback and outcome rows for the named match. Correct the source business records before retrying V18; the migration will not synthesize missing stages.';
END
$migration$;

CREATE OR REPLACE FUNCTION guanxian_match_workflow_constraint_v18()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  workflow_match_id UUID;
BEGIN
  workflow_match_id=CASE WHEN TG_OP='DELETE' THEN OLD.match_id ELSE NEW.match_id END;
  PERFORM guanxian_validate_match_workflow_v18(workflow_match_id);
  RETURN NULL;
END
$function$;

CREATE OR REPLACE FUNCTION guanxian_match_parent_constraint_v18()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF TG_OP<>'DELETE' THEN
    PERFORM guanxian_validate_match_workflow_v18(NEW.id);
  END IF;
  RETURN NULL;
END
$function$;

CREATE OR REPLACE FUNCTION guanxian_match_dependency_constraint_v18()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  workflow_match_id UUID;
BEGIN
  IF TG_TABLE_NAME='cooperation_demand' THEN
    FOR workflow_match_id IN
      SELECT id FROM ecosystem_match
       WHERE demand_id IN (OLD.id, NEW.id)
    LOOP
      PERFORM guanxian_validate_match_workflow_v18(workflow_match_id);
    END LOOP;
  ELSIF TG_TABLE_NAME='enterprise' THEN
    FOR workflow_match_id IN
      SELECT m.id
        FROM ecosystem_match m
        JOIN cooperation_demand d ON d.id=m.demand_id
       WHERE m.candidate_enterprise_id IN (OLD.id, NEW.id)
          OR d.enterprise_id IN (OLD.id, NEW.id)
    LOOP
      PERFORM guanxian_validate_match_workflow_v18(workflow_match_id);
    END LOOP;
  END IF;
  RETURN NULL;
END
$function$;

CREATE CONSTRAINT TRIGGER ecosystem_match_workflow_v18_ct
AFTER INSERT OR UPDATE ON ecosystem_match
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION guanxian_match_parent_constraint_v18();

CREATE CONSTRAINT TRIGGER match_invitation_workflow_v18_ct
AFTER INSERT OR UPDATE OR DELETE ON match_invitation
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION guanxian_match_workflow_constraint_v18();

CREATE CONSTRAINT TRIGGER negotiation_record_workflow_v18_ct
AFTER INSERT ON negotiation_record
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION guanxian_match_workflow_constraint_v18();

CREATE CONSTRAINT TRIGGER match_feedback_workflow_v18_ct
AFTER INSERT OR UPDATE OR DELETE ON match_feedback
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION guanxian_match_workflow_constraint_v18();

CREATE CONSTRAINT TRIGGER outcome_archive_workflow_v18_ct
AFTER INSERT OR UPDATE OR DELETE ON outcome_archive
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION guanxian_match_workflow_constraint_v18();

CREATE CONSTRAINT TRIGGER cooperation_demand_match_workflow_v18_ct
AFTER UPDATE ON cooperation_demand
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION guanxian_match_dependency_constraint_v18();

CREATE CONSTRAINT TRIGGER enterprise_match_workflow_v18_ct
AFTER UPDATE ON enterprise
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION guanxian_match_dependency_constraint_v18();

CREATE UNIQUE INDEX negotiation_one_terminal_v18_uq
  ON negotiation_record (match_id)
  WHERE stage IN ('CONTRACT_SIGNED', 'TERMINATED');

CREATE INDEX negotiation_match_stage_time_v18_idx
  ON negotiation_record (match_id, stage, created_at DESC, id);

CREATE INDEX match_feedback_match_outcome_v18_idx
  ON match_feedback (match_id, outcome, submitted_at DESC);

CREATE INDEX match_invitation_match_status_v18_idx
  ON match_invitation (match_id, status, updated_at DESC);
