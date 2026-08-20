CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE association (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(200) NOT NULL UNIQUE,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE enterprise (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  association_id UUID NOT NULL REFERENCES association(id),
  unified_social_credit_code VARCHAR(32),
  name VARCHAR(200) NOT NULL,
  short_name VARCHAR(100),
  description TEXT,
  enterprise_roles JSONB NOT NULL DEFAULT '[]'::jsonb,
  service_scenarios JSONB NOT NULL DEFAULT '[]'::jsonb,
  visibility VARCHAR(32) NOT NULL DEFAULT 'MEMBERS',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (association_id, name)
);

CREATE UNIQUE INDEX enterprise_credit_code_uq
  ON enterprise(unified_social_credit_code)
  WHERE unified_social_credit_code IS NOT NULL;

CREATE TABLE user_account (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  enterprise_id UUID REFERENCES enterprise(id),
  username VARCHAR(100) NOT NULL UNIQUE,
  display_name VARCHAR(100) NOT NULL,
  password_hash VARCHAR(255),
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_role (
  user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
  role_code VARCHAR(64) NOT NULL,
  PRIMARY KEY (user_id, role_code)
);

CREATE TABLE product_service (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  enterprise_id UUID NOT NULL REFERENCES enterprise(id) ON DELETE CASCADE,
  name VARCHAR(200) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  description TEXT,
  scenarios JSONB NOT NULL DEFAULT '[]'::jsonb,
  qualifications JSONB NOT NULL DEFAULT '[]'::jsonb,
  visibility VARCHAR(32) NOT NULL DEFAULT 'MEMBERS',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE cooperation_demand (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  enterprise_id UUID NOT NULL REFERENCES enterprise(id) ON DELETE CASCADE,
  title VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  scenarios JSONB NOT NULL DEFAULT '[]'::jsonb,
  required_capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
  visibility VARCHAR(32) NOT NULL DEFAULT 'DIRECTED',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE policy_document (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  title VARCHAR(300) NOT NULL,
  issuing_authority VARCHAR(200),
  document_number VARCHAR(100),
  published_on DATE,
  effective_on DATE,
  source_url TEXT,
  summary TEXT,
  affected_scenarios JSONB NOT NULL DEFAULT '[]'::jsonb,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ecosystem_match (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  demand_id UUID NOT NULL REFERENCES cooperation_demand(id),
  candidate_enterprise_id UUID NOT NULL REFERENCES enterprise(id),
  score NUMERIC(5,2) NOT NULL CHECK (score >= 0 AND score <= 100),
  explanation JSONB NOT NULL DEFAULT '{}'::jsonb,
  review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  reviewed_by UUID REFERENCES user_account(id),
  reviewed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE collaboration_task (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  match_id UUID REFERENCES ecosystem_match(id),
  owner_user_id UUID REFERENCES user_account(id),
  title VARCHAR(200) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
  due_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
  id BIGSERIAL PRIMARY KEY,
  actor_user_id UUID REFERENCES user_account(id),
  action VARCHAR(100) NOT NULL,
  resource_type VARCHAR(100) NOT NULL,
  resource_id VARCHAR(100),
  details JSONB NOT NULL DEFAULT '{}'::jsonb,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX enterprise_roles_gin ON enterprise USING gin (enterprise_roles);
CREATE INDEX enterprise_scenarios_gin ON enterprise USING gin (service_scenarios);
CREATE INDEX product_service_scenarios_gin ON product_service USING gin (scenarios);
CREATE INDEX cooperation_demand_scenarios_gin ON cooperation_demand USING gin (scenarios);
CREATE INDEX policy_published_on_idx ON policy_document (published_on DESC);
CREATE INDEX ecosystem_match_demand_score_idx ON ecosystem_match (demand_id, score DESC);
