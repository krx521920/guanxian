-- Legacy policy rows may predate association scoping. Keep them readable for an
-- explicit cleanup migration, but reject every new or updated orphan policy now.
ALTER TABLE policy_document
  DROP CONSTRAINT IF EXISTS policy_document_association_required_ck;

ALTER TABLE policy_document
  ADD CONSTRAINT policy_document_association_required_ck
  CHECK (association_id IS NOT NULL) NOT VALID;
