DROP INDEX IF EXISTS notification_subscription_user_type_uq;

-- Versions before V15 stored at most one subscription per user and type, and
-- allowed the association to be omitted. Preserve tenant-bound subscriptions
-- by deriving their association from the user's verified binding. Orphaned
-- global subscriptions cannot be delivered safely under the new tenant model,
-- so retain them for audit but quarantine them as inactive.
UPDATE notification_subscription AS subscription
SET association_id = account.association_id,
    updated_at = now(),
    version = subscription.version + 1
FROM user_account AS account
WHERE subscription.user_id = account.id
  AND subscription.association_id IS NULL
  AND account.association_id IS NOT NULL;

UPDATE notification_subscription
SET status = 'INACTIVE',
    updated_at = now(),
    version = version + 1
WHERE association_id IS NULL
  AND status <> 'INACTIVE';

CREATE UNIQUE INDEX IF NOT EXISTS notification_subscription_user_association_type_uq
  ON notification_subscription (user_id, association_id, subscription_type)
  NULLS NOT DISTINCT;
