-- Structure only. Reviewed public evidence is imported by a separate, backed-up operation.
-- A V30 patch migration avoids consuming the separate in-progress V31 policy feature.
-- Activities are neither member-authored demands nor confirmed platform collaborations.
ALTER TABLE platform_source_record DROP CONSTRAINT platform_source_record_kind_check;
ALTER TABLE platform_source_record ADD CONSTRAINT platform_source_record_kind_check
    CHECK (kind IN ('ENTERPRISE', 'POLICY', 'ASSOCIATION', 'TENDER', 'ACTIVITY'));
