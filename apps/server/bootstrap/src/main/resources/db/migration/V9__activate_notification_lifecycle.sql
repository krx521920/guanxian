ALTER TABLE notification_subscription
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS notification_subscription_user_type_uq
  ON notification_subscription (user_id, subscription_type);

ALTER TABLE notification_subscription
  ADD CONSTRAINT notification_subscription_status_ck
  CHECK (status IN ('ACTIVE', 'INACTIVE')) NOT VALID;

ALTER TABLE notification_subscription
  VALIDATE CONSTRAINT notification_subscription_status_ck;

CREATE INDEX IF NOT EXISTS notification_message_user_time_idx
  ON notification_message (user_id, created_at DESC, id DESC);
