-- Corte 2B: ACL de club, rate limit 10/h y idempotencia por messageId
-- para POST /v1/notifications/send. La credencial FCM NO vive en la BD.

CREATE TABLE IF NOT EXISTS notification_send_receipts (
  sender_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  message_id     UUID NOT NULL,
  group_id       UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  type           TEXT NOT NULL,
  response       JSONB NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (sender_user_id, message_id)
);

CREATE INDEX IF NOT EXISTS idx_notification_send_receipts_rate
  ON notification_send_receipts (sender_user_id, created_at DESC);
