-- =============================================================================
-- Migración 0007: Bóveda Biker Avanzada E2EE & Recuperación con Passphrase
-- (Fase 5 · RULES.md §15, MEMORY.md D68)
-- =============================================================================

-- 1. Tabla de Documentos Cifrados en Reposo (E2EE)
-- CERO ON DELETE CASCADE para proteger la bóveda contra borrado accidental de usuario
CREATE TABLE IF NOT EXISTS vault_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    doc_type TEXT NOT NULL,
    ciphertext TEXT NOT NULL,
    iv TEXT NOT NULL,
    auth_tag TEXT NOT NULL,
    user_salt TEXT NOT NULL,
    title TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vault_user_doc_type UNIQUE (user_id, doc_type)
);

CREATE INDEX IF NOT EXISTS idx_vault_items_user_id ON vault_items (user_id);

-- 2. Tabla de Clave Maestra de Recuperación con Passphrase (PBKDF2-HMAC-SHA256)
-- CERO ON DELETE CASCADE para proteger la clave maestra de recuperación
CREATE TABLE IF NOT EXISTS vault_recovery_keys (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    encrypted_master_key TEXT NOT NULL,
    iv TEXT NOT NULL,
    auth_tag TEXT NOT NULL,
    pbkdf2_salt TEXT NOT NULL,
    pbkdf2_iterations INT NOT NULL DEFAULT 100000,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);

-- 3. Tokens Efímeros de Un Solo Uso para Recuperación (TTL 5 min)
CREATE TABLE IF NOT EXISTS vault_recovery_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    token TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_vault_recovery_tokens_lookup ON vault_recovery_tokens (token, user_id);

-- 4. Fichas Médicas de Emergencia Compartidas Efímeramente en Rodada
CREATE TABLE IF NOT EXISTS shared_medical_sheets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    ride_id UUID NOT NULL REFERENCES rides(id),
    group_id UUID NOT NULL REFERENCES groups(id),
    ciphertext TEXT NOT NULL,
    iv TEXT NOT NULL,
    auth_tag TEXT NOT NULL,
    shared_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_shared_medical_user_ride UNIQUE (user_id, ride_id)
);

CREATE INDEX IF NOT EXISTS idx_shared_medical_ride_user ON shared_medical_sheets (ride_id, user_id);

