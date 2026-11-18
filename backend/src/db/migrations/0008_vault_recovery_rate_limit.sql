-- =============================================================================
-- Migración 0008: Rate Limiting & Auditoría de Recuperación de Bóveda E2EE
-- (Fase 5.5 · Hito 2 · RULES.md §15.8)
-- =============================================================================

CREATE TABLE IF NOT EXISTS vault_recovery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ip VARCHAR(64) NOT NULL,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    endpoint VARCHAR(64) NOT NULL,
    success BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_recovery_attempts_lookup ON vault_recovery_attempts (ip, user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_recovery_attempts_time ON vault_recovery_attempts (created_at DESC);
