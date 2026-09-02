ALTER TABLE association_access_request
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE association_access_request
  ADD CONSTRAINT association_access_request_version_ck CHECK (version >= 0);

ALTER TABLE enterprise_share_consent
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE enterprise_share_consent
  ADD CONSTRAINT enterprise_share_consent_version_ck CHECK (version >= 0);

-- V17 materializes an expired authorization before a replacement grant. That
-- lifecycle transition is a write too, so advance its optimistic-lock version.
CREATE OR REPLACE FUNCTION materialize_expired_enterprise_share_consents()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF NEW.status <> 'ACTIVE' THEN
    RETURN NEW;
  END IF;

  IF NEW.expires_at IS NOT NULL AND NEW.expires_at <= statement_timestamp() THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = 'an ACTIVE enterprise share consent must expire in the future',
      DETAIL = format('consent_id=%s, expires_at=%s', NEW.id, NEW.expires_at),
      HINT = 'Use a future expires_at, or persist the historical row with status EXPIRED.';
  END IF;

  UPDATE enterprise_share_consent
     SET status = 'EXPIRED', version = version + 1
   WHERE id <> NEW.id
     AND enterprise_id = NEW.enterprise_id
     AND target_association_id = NEW.target_association_id
     AND resource_type = NEW.resource_type
     AND resource_id = NEW.resource_id
     AND status = 'ACTIVE'
     AND expires_at IS NOT NULL
     AND expires_at <= statement_timestamp();

  RETURN NEW;
END
$function$;
