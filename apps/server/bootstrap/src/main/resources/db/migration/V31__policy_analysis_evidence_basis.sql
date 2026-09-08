-- Preserve legacy analyses as legacy; never manufacture evidence for them.
ALTER TABLE policy_impact_analysis ADD COLUMN evidence_details JSONB;
ALTER TABLE policy_impact_analysis ADD CONSTRAINT policy_analysis_evidence_object
    CHECK (evidence_details IS NULL OR (jsonb_typeof(evidence_details)='object'
        AND evidence_details ? 'basis' AND evidence_details->>'basis' IS NOT NULL
        AND evidence_details->>'basis' IN ('SUMMARY_REFERENCE','SOURCE_CHUNKS')));
ALTER TABLE policy_impact_analysis ADD CONSTRAINT summary_analysis_not_approved
    CHECK (evidence_details IS NULL OR evidence_details->>'basis' <> 'SUMMARY_REFERENCE' OR status <> 'APPROVED');

CREATE INDEX platform_policy_source_lookup_idx
    ON platform_source_record(policy_id, created_at DESC, id) WHERE kind='POLICY';
