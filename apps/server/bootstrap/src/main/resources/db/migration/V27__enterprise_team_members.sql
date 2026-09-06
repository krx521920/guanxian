-- Team invitations never grant an owner or platform role. Existing owner flows retain their scope.
ALTER TABLE enterprise_owner_invitation
  ADD COLUMN target_role VARCHAR(64) NOT NULL DEFAULT 'ENTERPRISE_ADMIN'
    CHECK (target_role IN ('ENTERPRISE_ADMIN', 'ENTERPRISE_MEMBER')),
  ADD COLUMN issuer_account_id UUID REFERENCES user_account(id),
  ADD COLUMN issuer_binding_version BIGINT,
  ADD CONSTRAINT team_invitation_issuer_required CHECK (
    target_role <> 'ENTERPRISE_MEMBER' OR
    (issuer_account_id IS NOT NULL AND issuer_binding_version IS NOT NULL AND issuer_binding_version >= 0));

ALTER TABLE enterprise_owner_grant DROP CONSTRAINT enterprise_owner_grant_role_code_check;
ALTER TABLE enterprise_owner_grant ADD CONSTRAINT enterprise_binding_grant_role_check
  CHECK (role_code IN ('ENTERPRISE_ADMIN', 'ENTERPRISE_MEMBER'));

CREATE INDEX enterprise_team_invitation_idx
  ON enterprise_owner_invitation (enterprise_id, target_role, created_at DESC, id);
CREATE INDEX enterprise_team_grant_idx
  ON enterprise_owner_grant (enterprise_id, role_code, account_id);
