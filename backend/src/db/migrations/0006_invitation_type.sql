-- =============================================================================
-- Migración 0006: Soporte de Tipos de Invitación Canónicos (Hermandad vs Rodada)
-- y Regla R2.2 / R2.3 (RULES.md §2, INVITATION_DOMAIN.md)
-- =============================================================================

ALTER TABLE invitations
    ADD COLUMN IF NOT EXISTS invitation_type TEXT NOT NULL DEFAULT 'hermandad'
        CHECK (invitation_type IN ('hermandad', 'rodada'));

