-- =============================================================================
-- Migración 0004: Fase 6 — Gamificación, Jerarquía de Clubes (RC->MG->Gang->MC)
-- y Reglas de Insignias (GAMIFICATION.md, RULES.md R8.6, D45, D48, D49)
-- =============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1. Jerarquía de Clubes (RC, MG, GANG, MC) y Apadrinamiento (RULES R3.4, D45)
-- ----------------------------------------------------------------------------
ALTER TABLE groups
    ADD COLUMN IF NOT EXISTS tier TEXT NOT NULL DEFAULT 'RC'
        CHECK (tier IN ('RC', 'MG', 'GANG', 'MC')),
    ADD COLUMN IF NOT EXISTS sponsor_group_id UUID REFERENCES groups(id);

ALTER TABLE groups
    DROP CONSTRAINT IF EXISTS chk_group_no_self_sponsor;

ALTER TABLE groups
    ADD CONSTRAINT chk_group_no_self_sponsor CHECK (sponsor_group_id IS NULL OR sponsor_group_id != id);

-- Trigger de validación de transición de jerarquía de clubes
CREATE OR REPLACE FUNCTION trg_groups_hierarchy_transition_fn()
RETURNS TRIGGER AS $$
DECLARE
    v_sponsor_tier TEXT;
    v_officer_count INT;
    v_age_days INT;
BEGIN
    -- Si no cambia el tier, permitir
    IF OLD.tier IS NOT NULL AND NEW.tier = OLD.tier THEN
        RETURN NEW;
    END IF;

    v_age_days := EXTRACT(DAY FROM (now() - NEW.created_at))::INT;

    -- Validaciones según tier destino
    IF NEW.tier = 'MG' THEN
        -- MG requiere al menos 90 días de antigüedad
        IF v_age_days < 90 THEN
            RAISE EXCEPTION 'RULES R3.4: La transición a Moto Group (MG) requiere al menos 90 días de antigüedad como RC (actual: % días)', v_age_days;
        END IF;
    ELSIF NEW.tier = 'GANG' THEN
        -- GANG requiere al menos 180 días de antigüedad y directiva mínima completa
        IF v_age_days < 180 THEN
            RAISE EXCEPTION 'RULES R3.4: La transición a GANG requiere al menos 180 días de antigüedad (actual: % días)', v_age_days;
        END IF;

        -- Verificar cargos de Mesa Directiva (Presidente, Vicepresidente, Sargento de Armas)
        SELECT COUNT(DISTINCT role) INTO v_officer_count
        FROM memberships
        WHERE group_id = NEW.id
          AND invite_state = 'accepted'
          AND role IN ('Presidente', 'Vicepresidente', 'Sargento de Armas');

        IF v_officer_count < 3 THEN
            RAISE EXCEPTION 'RULES R3.4: La transición a GANG requiere contar con Presidente, Vicepresidente y Sargento de Armas activos en la directiva';
        END IF;
    ELSIF NEW.tier = 'MC' THEN
        -- MC requiere al menos 365 días de antigüedad, directiva completa y apadrinamiento de un MC existente
        IF v_age_days < 365 THEN
            RAISE EXCEPTION 'RULES R3.4: La transición a Motorcycle Club (MC) tradicional requiere al menos 365 días de antigüedad (actual: % días)', v_age_days;
        END IF;

        IF NEW.sponsor_group_id IS NULL THEN
            RAISE EXCEPTION 'RULES R3.4: Un club no puede graduarse a MC sin un club padrino formal (sponsor_group_id)';
        END IF;

        SELECT tier INTO v_sponsor_tier
        FROM groups
        WHERE id = NEW.sponsor_group_id;

        IF v_sponsor_tier != 'MC' THEN
            RAISE EXCEPTION 'RULES R3.4: El club padrino debe ostentar rango MC confirmado';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_groups_hierarchy_transition ON groups;
CREATE TRIGGER trg_groups_hierarchy_transition
    BEFORE UPDATE OF tier ON groups
    FOR EACH ROW EXECUTE FUNCTION trg_groups_hierarchy_transition_fn();


-- ----------------------------------------------------------------------------
-- 2. Familias de Insignias y Validación de Destinatarios (RULES R8.6, GAMIFICATION.md)
-- ----------------------------------------------------------------------------
-- Ampliar CHECK de family en badges para admitir 'officer' (Mesa Directiva)
ALTER TABLE badges DROP CONSTRAINT IF EXISTS badges_family_check;
ALTER TABLE badges ADD CONSTRAINT badges_family_check
    CHECK (family IN ('rank', 'achievement', 'honor', 'officer'));

-- Trigger: Insignias de Mesa Directiva solo a miembros del grupo emisor
CREATE OR REPLACE FUNCTION trg_badges_officer_must_be_member_fn()
RETURNS TRIGGER AS $$
DECLARE
    v_is_member BOOLEAN;
BEGIN
    IF NEW.family = 'officer' THEN
        SELECT EXISTS (
            SELECT 1 FROM memberships
            WHERE group_id = NEW.issuer_group
              AND user_id = NEW.receiver
              AND invite_state = 'accepted'
        ) OR EXISTS (
            SELECT 1 FROM group_leaders
            WHERE group_id = NEW.issuer_group
              AND user_id = NEW.receiver
        ) INTO v_is_member;

        IF NOT v_is_member THEN
            RAISE EXCEPTION 'RULES R8.6: Las insignias de Mesa Directiva solo pueden otorgarse a miembros oficiales del club';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_badges_officer_must_be_member ON badges;
CREATE TRIGGER trg_badges_officer_must_be_member
    BEFORE INSERT ON badges
    FOR EACH ROW EXECUTE FUNCTION trg_badges_officer_must_be_member_fn();

-- Trigger: Insignias de Honor cruzables requieren invitación/participación previa en rodada (D43)
CREATE OR REPLACE FUNCTION trg_badges_honor_requires_guest_ride_fn()
RETURNS TRIGGER AS $$
DECLARE
    v_is_own_member BOOLEAN;
    v_shared_ride_exists BOOLEAN;
BEGIN
    IF NEW.family = 'honor' THEN
        -- Comprobar si pertenece al mismo club
        SELECT EXISTS (
            SELECT 1 FROM memberships
            WHERE group_id = NEW.issuer_group
              AND user_id = NEW.receiver
              AND invite_state = 'accepted'
        ) OR EXISTS (
            SELECT 1 FROM group_leaders
            WHERE group_id = NEW.issuer_group
              AND user_id = NEW.receiver
        ) INTO v_is_own_member;

        -- Si es miembro externo, DEBE haber participado en al menos una rodada del grupo emisor
        IF NOT v_is_own_member THEN
            SELECT EXISTS (
                SELECT 1
                FROM ride_participants rp
                JOIN rides r ON r.id = rp.ride_id
                WHERE rp.user_id = NEW.receiver
                  AND (r.host_group = NEW.issuer_group OR r.cohost_group = NEW.issuer_group)
                  AND (rp.has_mesh_contact = true OR rp.counted_for_rank = true)
            ) INTO v_shared_ride_exists;

            IF NOT v_shared_ride_exists THEN
                RAISE EXCEPTION 'RULES R8.6 / D43: No se puede otorgar insignia de honor a un rider externo sin haber compartido una rodada previa';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_badges_honor_requires_guest_ride ON badges;
CREATE TRIGGER trg_badges_honor_requires_guest_ride
    BEFORE INSERT ON badges
    FOR EACH ROW EXECUTE FUNCTION trg_badges_honor_requires_guest_ride_fn();

COMMIT;
