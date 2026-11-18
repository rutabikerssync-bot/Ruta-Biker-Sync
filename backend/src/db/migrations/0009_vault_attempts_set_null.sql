-- =============================================================================
-- Migración 0009: Blindaje de Auditoría Forense de Recuperación E2EE
-- (Fase 5.5 · Hito 3 · RULES.md §15.8)
-- Cambiar ON DELETE CASCADE a ON DELETE SET NULL en vault_recovery_attempts(user_id)
-- =============================================================================

ALTER TABLE vault_recovery_attempts
    DROP CONSTRAINT IF EXISTS vault_recovery_attempts_user_id_fkey;

ALTER TABLE vault_recovery_attempts
    ADD CONSTRAINT vault_recovery_attempts_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

