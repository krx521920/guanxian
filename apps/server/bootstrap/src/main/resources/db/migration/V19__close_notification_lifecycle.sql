-- Only all-policy, in-app subscriptions are implemented. Keep legacy rows for
-- audit, but prevent them from being delivered or restored through the API.
UPDATE notification_subscription
   SET status = 'INACTIVE', updated_at = now(), version = version + 1
 WHERE status = 'ACTIVE'
   AND (association_id IS NULL
        OR subscription_type <> 'POLICY'
        OR filters <> '{}'::jsonb
        OR channels <> '["IN_APP"]'::jsonb);

ALTER TABLE notification_subscription
  ADD CONSTRAINT notification_subscription_supported_active_ck
  CHECK (status <> 'ACTIVE'
         OR (association_id IS NOT NULL
             AND subscription_type = 'POLICY'
             AND filters = '{}'::jsonb
             AND channels = '["IN_APP"]'::jsonb)) NOT VALID;

ALTER TABLE notification_subscription
  VALIDATE CONSTRAINT notification_subscription_supported_active_ck;

-- Normalize unknown historical delivery states before enforcing the lifecycle
-- used by the notification inbox.
UPDATE notification_message
   SET status = 'FAILED'
 WHERE status NOT IN ('PENDING', 'DELIVERED', 'READ', 'FAILED', 'ARCHIVED');

ALTER TABLE notification_message
  ADD CONSTRAINT notification_message_status_ck
  CHECK (status IN ('PENDING', 'DELIVERED', 'READ', 'FAILED', 'ARCHIVED')) NOT VALID;

ALTER TABLE notification_message
  VALIDATE CONSTRAINT notification_message_status_ck;

CREATE INDEX IF NOT EXISTS notification_message_user_archive_time_idx
  ON notification_message (user_id, association_id, status, created_at DESC, id DESC);
