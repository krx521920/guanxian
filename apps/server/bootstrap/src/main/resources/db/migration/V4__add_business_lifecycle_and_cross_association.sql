ALTER TABLE product_service
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS created_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS updated_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS approved_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ;

ALTER TABLE cooperation_demand
  ADD COLUMN IF NOT EXISTS budget_min NUMERIC(18,2),
  ADD COLUMN IF NOT EXISTS budget_max NUMERIC(18,2),
  ADD COLUMN IF NOT EXISTS response_deadline TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS close_reason VARCHAR(1000),
  ADD COLUMN IF NOT EXISTS created_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS updated_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS approved_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ;

ALTER TABLE policy_document
  ADD COLUMN IF NOT EXISTS association_id UUID REFERENCES association(id),
  ADD COLUMN IF NOT EXISTS policy_level VARCHAR(64),
  ADD COLUMN IF NOT EXISTS category VARCHAR(100),
  ADD COLUMN IF NOT EXISTS tags JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS visibility VARCHAR(32) NOT NULL DEFAULT 'MEMBERS',
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS created_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS updated_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS approved_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ;

ALTER TABLE ecosystem_match
  ADD COLUMN IF NOT EXISTS demand_company_snapshot VARCHAR(200),
  ADD COLUMN IF NOT EXISTS demand_title_snapshot VARCHAR(300),
  ADD COLUMN IF NOT EXISTS scene_snapshot VARCHAR(100),
  ADD COLUMN IF NOT EXISTS supplier_company_snapshot VARCHAR(200),
  ADD COLUMN IF NOT EXISTS solution TEXT,
  ADD COLUMN IF NOT EXISTS reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS state VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
  ADD COLUMN IF NOT EXISTS confirmed_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS recommended_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS recommended_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS closed_reason VARCHAR(1000),
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE collaboration_task
  ADD COLUMN IF NOT EXISTS association_id UUID REFERENCES association(id),
  ADD COLUMN IF NOT EXISTS enterprise_id UUID REFERENCES enterprise(id),
  ADD COLUMN IF NOT EXISTS owner_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS participants JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
  ADD COLUMN IF NOT EXISTS next_action VARCHAR(500),
  ADD COLUMN IF NOT EXISTS progress INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS business_entity_history (
  id BIGSERIAL PRIMARY KEY,
  association_id UUID,
  enterprise_id UUID,
  resource_type VARCHAR(64) NOT NULL,
  resource_id UUID NOT NULL,
  resource_version BIGINT NOT NULL CHECK (resource_version >= 0),
  action VARCHAR(64) NOT NULL,
  actor_subject VARCHAR(200) NOT NULL,
  snapshot JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS business_history_resource_idx
  ON business_entity_history (resource_type, resource_id, resource_version DESC);
CREATE INDEX IF NOT EXISTS business_history_scope_time_idx
  ON business_entity_history (association_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS match_invitation (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  match_id UUID NOT NULL REFERENCES ecosystem_match(id) ON DELETE CASCADE,
  association_id UUID REFERENCES association(id),
  sender_enterprise_id UUID REFERENCES enterprise(id),
  recipient_enterprise_id UUID REFERENCES enterprise(id),
  invitation_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  message VARCHAR(2000),
  response_comment VARCHAR(2000),
  sent_by_subject VARCHAR(200) NOT NULL,
  responded_by_subject VARCHAR(200),
  expires_at TIMESTAMPTZ,
  responded_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS match_invitation_recipient_idx
  ON match_invitation (recipient_enterprise_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS negotiation_record (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  match_id UUID NOT NULL REFERENCES ecosystem_match(id) ON DELETE CASCADE,
  association_id UUID REFERENCES association(id),
  enterprise_id UUID REFERENCES enterprise(id),
  stage VARCHAR(32) NOT NULL,
  summary TEXT NOT NULL,
  next_action VARCHAR(1000),
  next_action_at TIMESTAMPTZ,
  recorded_by_subject VARCHAR(200) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS negotiation_match_time_idx
  ON negotiation_record (match_id, created_at DESC);

CREATE TABLE IF NOT EXISTS match_feedback (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  match_id UUID NOT NULL REFERENCES ecosystem_match(id) ON DELETE CASCADE,
  enterprise_id UUID NOT NULL REFERENCES enterprise(id),
  rating INTEGER CHECK (rating BETWEEN 1 AND 5),
  outcome VARCHAR(32) NOT NULL,
  close_reason VARCHAR(1000),
  comment VARCHAR(3000),
  submitted_by_subject VARCHAR(200) NOT NULL,
  submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (match_id, enterprise_id)
);

CREATE TABLE IF NOT EXISTS outcome_archive (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  match_id UUID NOT NULL REFERENCES ecosystem_match(id),
  association_id UUID REFERENCES association(id),
  title VARCHAR(300) NOT NULL,
  summary TEXT NOT NULL,
  contract_amount NUMERIC(18,2),
  result_type VARCHAR(32) NOT NULL,
  visibility VARCHAR(32) NOT NULL DEFAULT 'ASSOCIATION',
  archived_by_subject VARCHAR(200) NOT NULL,
  archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS collaboration_activity (
  id BIGSERIAL PRIMARY KEY,
  collaboration_id UUID NOT NULL REFERENCES collaboration_task(id) ON DELETE CASCADE,
  activity_type VARCHAR(32) NOT NULL,
  detail TEXT NOT NULL,
  actor_subject VARCHAR(200) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS collaboration_activity_time_idx
  ON collaboration_activity (collaboration_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS association_access_request (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  applicant_association_id UUID NOT NULL REFERENCES association(id),
  target_association_id UUID NOT NULL REFERENCES association(id),
  reason VARCHAR(2000),
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  requested_by_subject VARCHAR(200) NOT NULL,
  reviewed_by_subject VARCHAR(200),
  review_comment VARCHAR(2000),
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_at TIMESTAMPTZ,
  CHECK (applicant_association_id <> target_association_id),
  CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

ALTER TABLE association_relationship
  ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS revoked_by_subject VARCHAR(200),
  ADD COLUMN IF NOT EXISTS revoke_reason VARCHAR(1000),
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS association_share_policy (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_association_id UUID NOT NULL REFERENCES association(id) ON DELETE CASCADE,
  target_association_id UUID NOT NULL REFERENCES association(id) ON DELETE CASCADE,
  resource_type VARCHAR(64) NOT NULL,
  visible_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  valid_from TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ,
  created_by_subject VARCHAR(200) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (source_association_id, target_association_id, resource_type),
  CHECK (source_association_id <> target_association_id)
);

CREATE TABLE IF NOT EXISTS enterprise_share_consent (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  enterprise_id UUID NOT NULL REFERENCES enterprise(id) ON DELETE CASCADE,
  target_association_id UUID NOT NULL REFERENCES association(id) ON DELETE CASCADE,
  resource_type VARCHAR(64) NOT NULL,
  resource_id UUID,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  granted_by_subject VARCHAR(200) NOT NULL,
  expires_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS enterprise_share_consent_lookup_idx
  ON enterprise_share_consent (enterprise_id, target_association_id, resource_type, status);

CREATE TABLE IF NOT EXISTS cross_association_recommendation (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_association_id UUID NOT NULL REFERENCES association(id),
  target_association_id UUID NOT NULL REFERENCES association(id),
  demand_id UUID REFERENCES cooperation_demand(id),
  match_id UUID REFERENCES ecosystem_match(id),
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
  summary VARCHAR(2000) NOT NULL,
  created_by_subject VARCHAR(200) NOT NULL,
  reviewed_by_subject VARCHAR(200),
  review_comment VARCHAR(2000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS policy_scope_status_idx
  ON policy_document (association_id, status, published_on DESC)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS offering_enterprise_status_idx
  ON product_service (enterprise_id, status, updated_at DESC)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS demand_enterprise_status_idx
  ON cooperation_demand (enterprise_id, status, updated_at DESC)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS match_candidate_state_idx
  ON ecosystem_match (candidate_enterprise_id, state, updated_at DESC)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS collaboration_scope_status_idx
  ON collaboration_task (association_id, enterprise_id, status, updated_at DESC)
  WHERE deleted_at IS NULL;
