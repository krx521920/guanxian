-- Invitations bind existing enterprise records; no enterprise or password is created here.
CREATE TABLE enterprise_owner_invitation (
  id UUID PRIMARY KEY,
  association_id UUID NOT NULL REFERENCES association(id),
  enterprise_id UUID NOT NULL REFERENCES enterprise(id),
  invited_username VARCHAR(100) NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  status VARCHAR(32) NOT NULL CHECK (status IN ('ISSUED', 'CLAIMED', 'APPROVED', 'REJECTED', 'REVOKED')),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_by_subject VARCHAR(200) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  claim_subject VARCHAR(200),
  claim_username VARCHAR(100),
  claim_display_name VARCHAR(100),
  claimed_at TIMESTAMPTZ,
  reviewed_by_subject VARCHAR(200),
  reviewed_at TIMESTAMPTZ,
  review_note VARCHAR(1000),
  account_id UUID REFERENCES user_account(id),
  CHECK (expires_at > created_at),
  CHECK (status NOT IN ('CLAIMED', 'APPROVED', 'REJECTED') OR
         (claim_subject IS NOT NULL AND claim_username IS NOT NULL AND claimed_at IS NOT NULL)),
  CHECK (status <> 'APPROVED' OR (account_id IS NOT NULL AND reviewed_by_subject IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE INDEX enterprise_owner_invitation_scope_idx ON enterprise_owner_invitation (association_id, created_at DESC, id);
CREATE INDEX enterprise_owner_invitation_claim_idx ON enterprise_owner_invitation (claim_subject, created_at DESC, id);

-- Only the narrow enterprise-owner role may be granted by the approved workflow.
-- Exact subject, scope and binding version prevent reusing a grant after an account is moved or restored.
CREATE TABLE enterprise_owner_grant (
  account_id UUID PRIMARY KEY REFERENCES user_account(id) ON DELETE CASCADE,
  invitation_id UUID NOT NULL UNIQUE REFERENCES enterprise_owner_invitation(id),
  external_subject VARCHAR(200) NOT NULL,
  association_id UUID NOT NULL REFERENCES association(id),
  enterprise_id UUID NOT NULL REFERENCES enterprise(id),
  binding_version BIGINT NOT NULL CHECK (binding_version >= 0),
  role_code VARCHAR(64) NOT NULL CHECK (role_code = 'ENTERPRISE_ADMIN'),
  granted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
