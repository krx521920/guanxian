-- Cross-association authorization rows are security boundaries. Stop before
-- adding constraints when legacy rows are ambiguous; operators must inspect
-- and correct them explicitly instead of having the migration rewrite them.
DO $migration$
DECLARE
  dirty_count BIGINT;
  dirty_sample TEXT;
BEGIN
  SELECT count(*), min(id::text)
    INTO dirty_count, dirty_sample
    FROM enterprise_share_consent
   WHERE resource_id IS NULL;

  IF dirty_count > 0 THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = format(
        'V17 cannot require enterprise share consent resources: found %s row(s) with NULL resource_id',
        dirty_count
      ),
      DETAIL = format('sample consent_id=%s', dirty_sample),
      HINT = 'Assign each enterprise_share_consent row to the exact resource it authorizes, or delete the invalid row after review, then rerun V17.';
  END IF;
END
$migration$;

DO $migration$
DECLARE
  dirty_count BIGINT;
  dirty_sample TEXT;
BEGIN
  SELECT count(*), min(format(
           'share_policy_id=%s, resource_type=%s, visible_fields=%s',
           id, resource_type, visible_fields::text
         ))
    INTO dirty_count, dirty_sample
    FROM association_share_policy
   WHERE NOT (
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
         visible_fields <@ '["demandCompany","demandTitle","scene","supplierCompany","solution","score","reasons","state"]'::jsonb
       ELSE FALSE
     END
   );

  IF dirty_count > 0 THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = format(
        'V17 cannot enforce association share-policy field authorization: found %s invalid policy row(s)',
        dirty_count
      ),
      DETAIL = dirty_sample,
      HINT = 'Use a supported resource_type and a non-empty JSON array containing only its documented visible fields; MEMBER/PRODUCT/SERVICE require name and DEMAND requires title, then rerun V17.';
  END IF;
END
$migration$;

DO $migration$
DECLARE
  dirty_count BIGINT;
  dirty_sample TEXT;
BEGIN
  SELECT count(*), min(id::text)
    INTO dirty_count, dirty_sample
    FROM enterprise_share_consent
   WHERE status NOT IN ('ACTIVE', 'REVOKED', 'EXPIRED')
      OR (status = 'ACTIVE' AND revoked_at IS NOT NULL)
      OR (status = 'REVOKED' AND revoked_at IS NULL);

  IF dirty_count > 0 THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = format(
        'V17 cannot enforce enterprise share consent lifecycle: found %s inconsistent row(s)',
        dirty_count
      ),
      DETAIL = format('sample consent_id=%s', dirty_sample),
      HINT = 'Review each consent status and revoked_at together: ACTIVE must not be revoked, REVOKED must have revoked_at, and status must be ACTIVE, REVOKED, or EXPIRED; then rerun V17.';
  END IF;
END
$migration$;

DO $migration$
DECLARE
  dirty_count BIGINT;
  dirty_sample TEXT;
BEGIN
  SELECT count(*), min(format(
           'enterprise_id=%s, target_association_id=%s, resource_type=%s, resource_id=%s, active_count=%s',
           enterprise_id, target_association_id, resource_type, resource_id, active_count
         ))
    INTO dirty_count, dirty_sample
    FROM (
      SELECT enterprise_id,
             target_association_id,
             resource_type,
             resource_id,
             count(*) AS active_count
        FROM enterprise_share_consent
       WHERE status = 'ACTIVE'
       GROUP BY enterprise_id, target_association_id, resource_type, resource_id
      HAVING count(*) > 1
    ) AS duplicates;

  IF dirty_count > 0 THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = format(
        'V17 cannot enforce unique active enterprise share consent: found %s duplicate authorization key(s)',
        dirty_count
      ),
      DETAIL = dirty_sample,
      HINT = 'Review duplicate ACTIVE enterprise_share_consent rows and revoke or expire all but the one authorization that should remain active, then rerun V17.';
  END IF;
END
$migration$;

DO $migration$
DECLARE
  dirty_count BIGINT;
  dirty_sample TEXT;
BEGIN
  SELECT count(*), min(id::text)
    INTO dirty_count, dirty_sample
    FROM cross_association_recommendation
   WHERE source_association_id = target_association_id;

  IF dirty_count > 0 THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = format(
        'V17 cannot enforce recommendation participants: found %s self-targeting recommendation row(s)',
        dirty_count
      ),
      DETAIL = format('sample recommendation_id=%s', dirty_sample),
      HINT = 'Correct the source or target association, or delete the invalid recommendation after review, then rerun V17.';
  END IF;

  SELECT count(*), min(id::text)
    INTO dirty_count, dirty_sample
    FROM cross_association_recommendation
   WHERE demand_id IS NULL AND match_id IS NULL;

  IF dirty_count > 0 THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = format(
        'V17 cannot require recommendation resources: found %s recommendation row(s) without demand_id or match_id',
        dirty_count
      ),
      DETAIL = format('sample recommendation_id=%s', dirty_sample),
      HINT = 'Link each recommendation to a reviewed demand or match, or delete the invalid recommendation after review, then rerun V17.';
  END IF;

  SELECT count(*), min(format(
           'recommendation_id=%s, source_association_id=%s, demand_id=%s, match_id=%s',
           recommendation.id, recommendation.source_association_id,
           recommendation.demand_id, recommendation.match_id
         ))
    INTO dirty_count, dirty_sample
    FROM cross_association_recommendation recommendation
   WHERE (
          recommendation.demand_id IS NOT NULL
          AND NOT EXISTS (
            SELECT 1
              FROM cooperation_demand demand
              JOIN enterprise owner ON owner.id = demand.enterprise_id
             WHERE demand.id = recommendation.demand_id
               AND owner.association_id = recommendation.source_association_id
          )
        )
      OR (
          recommendation.match_id IS NOT NULL
          AND NOT EXISTS (
            SELECT 1
              FROM ecosystem_match match
              JOIN cooperation_demand matched_demand ON matched_demand.id = match.demand_id
              JOIN enterprise demand_owner ON demand_owner.id = matched_demand.enterprise_id
              JOIN enterprise candidate ON candidate.id = match.candidate_enterprise_id
             WHERE match.id = recommendation.match_id
               AND (
                 demand_owner.association_id = recommendation.source_association_id
                 OR candidate.association_id = recommendation.source_association_id
               )
          )
        )
      OR (
          recommendation.demand_id IS NOT NULL
          AND recommendation.match_id IS NOT NULL
          AND NOT EXISTS (
            SELECT 1
              FROM ecosystem_match match
             WHERE match.id = recommendation.match_id
               AND match.demand_id = recommendation.demand_id
          )
        );

  IF dirty_count > 0 THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = format(
        'V17 cannot enforce recommendation resource ownership: found %s recommendation row(s) outside the source association resource domain',
        dirty_count
      ),
      DETAIL = dirty_sample,
      HINT = 'Correct demand_id and match_id so the demand belongs to the source association, the match contains a source-association participant, and both ids refer to the same demand; otherwise reject and remove the invalid recommendation before rerunning V17.';
  END IF;

  SELECT count(*), min(format(
           'recommendation_id=%s, status=%s, version=%s', id, status, version
         ))
    INTO dirty_count, dirty_sample
    FROM cross_association_recommendation
   WHERE status NOT IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')
      OR version < 0
      OR (
          status = 'PENDING_REVIEW'
          AND (
            reviewed_by_subject IS NOT NULL
            OR review_comment IS NOT NULL
            OR reviewed_at IS NOT NULL
          )
        )
      OR (
          status IN ('APPROVED', 'REJECTED')
          AND (
            NULLIF(btrim(reviewed_by_subject), '') IS NULL
            OR reviewed_at IS NULL
          )
        );

  IF dirty_count > 0 THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = format(
        'V17 cannot enforce recommendation review lifecycle: found %s inconsistent recommendation row(s)',
        dirty_count
      ),
      DETAIL = dirty_sample,
      HINT = 'Normalize recommendation status and version; pending rows must have no review fields, while approved or rejected rows require a non-empty reviewer subject and reviewed_at, then rerun V17.';
  END IF;
END
$migration$;

DO $migration$
DECLARE
  dirty_count BIGINT;
  dirty_sample TEXT;
BEGIN
  SELECT count(*), min(format(
           'source_association_id=%s, target_association_id=%s, suspended_by_association_id=%s',
           source_association_id, target_association_id, suspended_by_association_id
         ))
    INTO dirty_count, dirty_sample
    FROM association_relationship
   WHERE suspended_by_association_id IS NOT NULL
     AND suspended_by_association_id <> source_association_id
     AND suspended_by_association_id <> target_association_id;

  IF dirty_count > 0 THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = format(
        'V17 cannot enforce relationship suspension ownership: found %s relationship row(s) suspended by a non-participant',
        dirty_count
      ),
      DETAIL = dirty_sample,
      HINT = 'Correct suspended_by_association_id to one of the relationship participants, or clear the invalid suspension after review, then rerun V17.';
  END IF;

  SELECT count(*), min(format(
           'source_association_id=%s, target_association_id=%s, status=%s',
           source_association_id, target_association_id, status
         ))
    INTO dirty_count, dirty_sample
    FROM association_relationship
   WHERE status NOT IN ('ACTIVE', 'SUSPENDED', 'REVOKED', 'EXPIRED')
      OR (
          status = 'ACTIVE'
          AND (
            suspended_at IS NOT NULL OR suspended_by_association_id IS NOT NULL
            OR suspended_by_subject IS NOT NULL OR revoked_at IS NOT NULL
            OR revoked_by_subject IS NOT NULL OR revoke_reason IS NOT NULL
          )
        )
      OR (
          status = 'SUSPENDED'
          AND (
            suspended_at IS NULL OR suspended_by_association_id IS NULL
            OR NULLIF(btrim(suspended_by_subject), '') IS NULL
            OR revoked_at IS NOT NULL OR revoked_by_subject IS NOT NULL
            OR revoke_reason IS NOT NULL
          )
        )
      OR (
          status = 'REVOKED'
          AND (
            suspended_at IS NOT NULL OR suspended_by_association_id IS NOT NULL
            OR suspended_by_subject IS NOT NULL OR revoked_at IS NULL
            OR NULLIF(btrim(revoked_by_subject), '') IS NULL
            OR NULLIF(btrim(revoke_reason), '') IS NULL
          )
        )
      OR (
          status = 'EXPIRED'
          AND (
            expires_at IS NULL OR suspended_at IS NOT NULL
            OR suspended_by_association_id IS NOT NULL OR suspended_by_subject IS NOT NULL
            OR revoked_at IS NOT NULL OR revoked_by_subject IS NOT NULL
            OR revoke_reason IS NOT NULL
          )
        );

  IF dirty_count > 0 THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = format(
        'V17 cannot enforce association relationship lifecycle: found %s inconsistent relationship row(s)',
        dirty_count
      ),
      DETAIL = dirty_sample,
      HINT = 'Normalize each relationship to ACTIVE, SUSPENDED, REVOKED, or EXPIRED and keep only the timestamps, actor fields, and reason required by that state, then rerun V17.';
  END IF;
END
$migration$;

DO $migration$
DECLARE
  dirty_count BIGINT;
  dirty_sample TEXT;
BEGIN
  SELECT count(*), min(id::text)
    INTO dirty_count, dirty_sample
    FROM association_share_policy
   WHERE (expires_at IS NOT NULL AND expires_at <= valid_from)
      OR status NOT IN ('ACTIVE', 'SUSPENDED')
      OR version < 0;

  IF dirty_count > 0 THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = format(
        'V17 cannot enforce share policy lifecycle: found %s invalid policy row(s)',
        dirty_count
      ),
      DETAIL = format('sample share_policy_id=%s', dirty_sample),
      HINT = 'Review the policy status, non-negative version, and effective interval (expires_at must be later than valid_from), then rerun V17.';
  END IF;
END
$migration$;

ALTER TABLE enterprise_share_consent
  ALTER COLUMN resource_id SET NOT NULL;

ALTER TABLE enterprise_share_consent
  ADD CONSTRAINT enterprise_share_consent_status_ck
    CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')) NOT VALID,
  ADD CONSTRAINT enterprise_share_consent_revocation_ck
    CHECK (
      (status <> 'ACTIVE' OR revoked_at IS NULL)
      AND (status <> 'REVOKED' OR revoked_at IS NOT NULL)
    ) NOT VALID;

ALTER TABLE enterprise_share_consent
  VALIDATE CONSTRAINT enterprise_share_consent_status_ck;
ALTER TABLE enterprise_share_consent
  VALIDATE CONSTRAINT enterprise_share_consent_revocation_ck;

ALTER TABLE cross_association_recommendation
  ADD CONSTRAINT cross_association_recommendation_distinct_participants_ck
    CHECK (source_association_id <> target_association_id) NOT VALID,
  ADD CONSTRAINT cross_association_recommendation_resource_ck
    CHECK (demand_id IS NOT NULL OR match_id IS NOT NULL) NOT VALID,
  ADD CONSTRAINT cross_association_recommendation_status_ck
    CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')) NOT VALID,
  ADD CONSTRAINT cross_association_recommendation_version_ck
    CHECK (version >= 0) NOT VALID,
  ADD CONSTRAINT cross_association_recommendation_review_lifecycle_ck
    CHECK (
      (status = 'PENDING_REVIEW'
       AND reviewed_by_subject IS NULL
       AND review_comment IS NULL
       AND reviewed_at IS NULL)
      OR
      (status IN ('APPROVED', 'REJECTED')
       AND NULLIF(btrim(reviewed_by_subject), '') IS NOT NULL
       AND reviewed_at IS NOT NULL)
    ) NOT VALID;

ALTER TABLE cross_association_recommendation
  VALIDATE CONSTRAINT cross_association_recommendation_distinct_participants_ck;
ALTER TABLE cross_association_recommendation
  VALIDATE CONSTRAINT cross_association_recommendation_resource_ck;
ALTER TABLE cross_association_recommendation
  VALIDATE CONSTRAINT cross_association_recommendation_status_ck;
ALTER TABLE cross_association_recommendation
  VALIDATE CONSTRAINT cross_association_recommendation_version_ck;
ALTER TABLE cross_association_recommendation
  VALIDATE CONSTRAINT cross_association_recommendation_review_lifecycle_ck;

ALTER TABLE association_relationship
  ADD CONSTRAINT association_relationship_suspender_participant_ck
    CHECK (
      suspended_by_association_id IS NULL
      OR suspended_by_association_id = source_association_id
      OR suspended_by_association_id = target_association_id
    ) NOT VALID,
  ADD CONSTRAINT association_relationship_status_ck
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED', 'EXPIRED')) NOT VALID,
  ADD CONSTRAINT association_relationship_lifecycle_ck
    CHECK (
      (status = 'ACTIVE'
       AND suspended_at IS NULL AND suspended_by_association_id IS NULL
       AND suspended_by_subject IS NULL AND revoked_at IS NULL
       AND revoked_by_subject IS NULL AND revoke_reason IS NULL)
      OR
      (status = 'SUSPENDED'
       AND suspended_at IS NOT NULL AND suspended_by_association_id IS NOT NULL
       AND NULLIF(btrim(suspended_by_subject), '') IS NOT NULL
       AND revoked_at IS NULL AND revoked_by_subject IS NULL AND revoke_reason IS NULL)
      OR
      (status = 'REVOKED'
       AND suspended_at IS NULL AND suspended_by_association_id IS NULL
       AND suspended_by_subject IS NULL AND revoked_at IS NOT NULL
       AND NULLIF(btrim(revoked_by_subject), '') IS NOT NULL
       AND NULLIF(btrim(revoke_reason), '') IS NOT NULL)
      OR
      (status = 'EXPIRED'
       AND expires_at IS NOT NULL
       AND suspended_at IS NULL AND suspended_by_association_id IS NULL
       AND suspended_by_subject IS NULL AND revoked_at IS NULL
       AND revoked_by_subject IS NULL AND revoke_reason IS NULL)
    ) NOT VALID;

ALTER TABLE association_relationship
  VALIDATE CONSTRAINT association_relationship_suspender_participant_ck;
ALTER TABLE association_relationship
  VALIDATE CONSTRAINT association_relationship_status_ck;
ALTER TABLE association_relationship
  VALIDATE CONSTRAINT association_relationship_lifecycle_ck;

ALTER TABLE association_share_policy
  ADD CONSTRAINT association_share_policy_interval_ck
    CHECK (expires_at IS NULL OR expires_at > valid_from) NOT VALID,
  ADD CONSTRAINT association_share_policy_status_ck
    CHECK (status IN ('ACTIVE', 'SUSPENDED')) NOT VALID,
  ADD CONSTRAINT association_share_policy_version_ck
    CHECK (version >= 0) NOT VALID,
  ADD CONSTRAINT association_share_policy_resource_type_ck
    CHECK (resource_type IN ('MEMBER', 'PRODUCT', 'SERVICE', 'DEMAND', 'MATCH')) NOT VALID,
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
          visible_fields <@ '["demandCompany","demandTitle","scene","supplierCompany","solution","score","reasons","state"]'::jsonb
        ELSE FALSE
      END
    ) NOT VALID;

ALTER TABLE association_share_policy
  VALIDATE CONSTRAINT association_share_policy_interval_ck;
ALTER TABLE association_share_policy
  VALIDATE CONSTRAINT association_share_policy_status_ck;
ALTER TABLE association_share_policy
  VALIDATE CONSTRAINT association_share_policy_version_ck;
ALTER TABLE association_share_policy
  VALIDATE CONSTRAINT association_share_policy_resource_type_ck;
ALTER TABLE association_share_policy
  VALIDATE CONSTRAINT association_share_policy_visible_fields_ck;

-- PostgreSQL cannot use now() in a partial-index predicate. Materialize an
-- expired authorization for the exact key inside the same transaction as a
-- replacement grant, then let the partial unique index serialize concurrent
-- grants. Existing expired rows are intentionally not rewritten by migration.
CREATE FUNCTION materialize_expired_enterprise_share_consents()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF NEW.status <> 'ACTIVE' THEN
    RETURN NEW;
  END IF;

  IF NEW.expires_at IS NOT NULL AND NEW.expires_at <= statement_timestamp() THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = 'an ACTIVE enterprise share consent must expire in the future',
      DETAIL = format('consent_id=%s, expires_at=%s', NEW.id, NEW.expires_at),
      HINT = 'Use a future expires_at, or persist the historical row with status EXPIRED.';
  END IF;

  UPDATE enterprise_share_consent
     SET status = 'EXPIRED'
   WHERE id <> NEW.id
     AND enterprise_id = NEW.enterprise_id
     AND target_association_id = NEW.target_association_id
     AND resource_type = NEW.resource_type
     AND resource_id = NEW.resource_id
     AND status = 'ACTIVE'
     AND expires_at IS NOT NULL
     AND expires_at <= statement_timestamp();

  RETURN NEW;
END
$function$;

CREATE TRIGGER enterprise_share_consent_materialize_expiry_trg
BEFORE INSERT OR UPDATE OF enterprise_id, target_association_id, resource_type,
  resource_id, status, expires_at
ON enterprise_share_consent
FOR EACH ROW
EXECUTE FUNCTION materialize_expired_enterprise_share_consents();

CREATE UNIQUE INDEX enterprise_share_consent_active_resource_uq
  ON enterprise_share_consent (
    enterprise_id, target_association_id, resource_type, resource_id
  )
  WHERE status = 'ACTIVE';

CREATE INDEX association_relationship_source_status_idx
  ON association_relationship (source_association_id, status, updated_at DESC);
CREATE INDEX association_relationship_target_status_idx
  ON association_relationship (target_association_id, status, updated_at DESC);
CREATE INDEX association_share_policy_target_status_idx
  ON association_share_policy (target_association_id, status, resource_type);
CREATE INDEX cross_association_recommendation_source_status_idx
  ON cross_association_recommendation (source_association_id, status, created_at DESC);
CREATE INDEX cross_association_recommendation_target_status_idx
  ON cross_association_recommendation (target_association_id, status, created_at DESC);
CREATE INDEX enterprise_share_consent_target_status_idx
  ON enterprise_share_consent (target_association_id, status, created_at DESC);
