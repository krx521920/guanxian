-- No existing enterprise is automatically approved, consented or published.
CREATE TABLE enterprise_profile_workflow (
    enterprise_id UUID PRIMARY KEY REFERENCES enterprise(id),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    publication_epoch BIGINT NOT NULL DEFAULT 0,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    public_name VARCHAR(200) NOT NULL DEFAULT '',
    public_category VARCHAR(100) NOT NULL DEFAULT '',
    draft_status VARCHAR(20),
    state_json TEXT NOT NULL
);
CREATE INDEX enterprise_publication_directory_idx ON enterprise_profile_workflow(published,public_name,enterprise_id);
CREATE INDEX enterprise_profile_review_queue_idx ON enterprise_profile_workflow(draft_status,enterprise_id);

-- Invalidate public access at the same transaction boundary as lifecycle changes.
-- Recovery never republishes, and previous consent cannot be reused after invalidation.
CREATE FUNCTION invalidate_enterprise_publication() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
       OR NEW.association_id IS DISTINCT FROM OLD.association_id THEN
        UPDATE enterprise_profile_workflow SET published = FALSE,
            publication_epoch = publication_epoch + 1, version = version + 1
        WHERE enterprise_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER enterprise_publication_lifecycle AFTER UPDATE ON enterprise
    FOR EACH ROW EXECUTE FUNCTION invalidate_enterprise_publication();

CREATE FUNCTION invalidate_association_publications() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        UPDATE enterprise_profile_workflow SET published = FALSE,
            publication_epoch = publication_epoch + 1, version = version + 1
        WHERE enterprise_id IN (SELECT id FROM enterprise WHERE association_id = NEW.id);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER association_publication_lifecycle AFTER UPDATE ON association
    FOR EACH ROW EXECUTE FUNCTION invalidate_association_publications();
