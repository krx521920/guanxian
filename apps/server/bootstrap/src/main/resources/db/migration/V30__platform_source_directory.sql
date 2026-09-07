-- Source snapshots are separate from member-authored offers, consent and matches.
-- This migration creates structure only. Production imports require a reviewed operation.
CREATE TABLE platform_dataset_import (
    id VARCHAR(100) PRIMARY KEY,
    association_id UUID NOT NULL REFERENCES association(id),
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    source_filename VARCHAR(255) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    report JSONB NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (association_id, source_sha256),
    UNIQUE (id, association_id)
);

CREATE TABLE platform_source_record (
    id UUID PRIMARY KEY,
    import_id VARCHAR(100) NOT NULL REFERENCES platform_dataset_import(id),
    association_id UUID NOT NULL REFERENCES association(id),
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('ENTERPRISE', 'POLICY', 'ASSOCIATION', 'TENDER')),
    source_id VARCHAR(50) NOT NULL,
    title VARCHAR(300) NOT NULL,
    enterprise_id UUID REFERENCES enterprise(id),
    policy_id UUID REFERENCES policy_document(id),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (import_id, kind, source_id),
    FOREIGN KEY (import_id, association_id) REFERENCES platform_dataset_import(id, association_id),
    FOREIGN KEY (enterprise_id, association_id) REFERENCES enterprise(id, association_id),
    FOREIGN KEY (policy_id, association_id) REFERENCES policy_document(id, association_id)
);
CREATE INDEX platform_source_directory_scope_idx ON platform_source_record(association_id, kind, title, id);
