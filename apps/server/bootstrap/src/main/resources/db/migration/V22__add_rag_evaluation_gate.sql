CREATE TABLE rag_evaluation_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    association_id UUID NOT NULL REFERENCES association(id),
    dataset_name VARCHAR(200) NOT NULL,
    dataset_hash CHAR(64) NOT NULL,
    total_cases INTEGER NOT NULL CHECK (total_cases > 0 AND total_cases <= 200),
    evidence_recall NUMERIC(8,6) NOT NULL CHECK (evidence_recall BETWEEN 0 AND 1),
    citation_precision NUMERIC(8,6) NOT NULL CHECK (citation_precision BETWEEN 0 AND 1),
    refusal_accuracy NUMERIC(8,6) NOT NULL CHECK (refusal_accuracy BETWEEN 0 AND 1),
    estimated_cost NUMERIC(18,8) NOT NULL DEFAULT 0 CHECK (estimated_cost >= 0),
    passed BOOLEAN NOT NULL,
    thresholds JSONB NOT NULL,
    case_results JSONB NOT NULL,
    executed_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_rag_evaluation_association_created
    ON rag_evaluation_run(association_id, created_at DESC);
