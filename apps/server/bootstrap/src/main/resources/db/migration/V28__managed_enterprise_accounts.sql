-- Direct provisioning is explicit administrator authorization, not a fabricated invitation approval.
-- Never store passwords or administrator API credentials in this table.
CREATE TABLE enterprise_managed_account (
  id UUID PRIMARY KEY,
  enterprise_id UUID NOT NULL UNIQUE REFERENCES enterprise(id),
  association_id UUID NOT NULL REFERENCES association(id),
  username VARCHAR(100) NOT NULL UNIQUE,
  external_subject VARCHAR(200) UNIQUE,
  account_id UUID UNIQUE REFERENCES user_account(id),
  binding_version BIGINT,
  status VARCHAR(24) NOT NULL CHECK (status IN ('CREATING','ACTIVE','RESETTING')),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  tokens_valid_after BIGINT NOT NULL DEFAULT 0,
  created_by_subject VARCHAR(200) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (status <> 'ACTIVE' OR (external_subject IS NOT NULL AND account_id IS NOT NULL AND binding_version IS NOT NULL))
);
