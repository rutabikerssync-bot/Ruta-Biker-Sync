-- =============================================================================
-- Migración 0011: Notificaciones Push FCM (Fase 7.1.2)
-- Registro de Tokens FCM de dispositivos y plataformas
-- =============================================================================

CREATE TABLE IF NOT EXISTS fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token TEXT NOT NULL,
    platform VARCHAR(16) NOT NULL DEFAULT 'android',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, token)
);

CREATE INDEX IF NOT EXISTS idx_fcm_tokens_user ON fcm_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_fcm_tokens_token ON fcm_tokens (token);
