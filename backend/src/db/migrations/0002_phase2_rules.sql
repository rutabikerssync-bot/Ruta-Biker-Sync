-- ============================================================================
-- Migración 0002: Reglas de negocio D42-D46 + Jerarquía RC/MG/Gang/MC
-- Fecha: 2026-10-23
-- Autor: Ruta Biker Sync
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 0. Columnas base requeridas por los triggers D44 y D46
-- ----------------------------------------------------------------------------
ALTER TABLE group_leaders
    ADD COLUMN IF NOT EXISTS club_role TEXT;

ALTER TABLE badges
    ADD COLUMN IF NOT EXISTS ride_id UUID REFERENCES rides(id);

ALTER TABLE badges
    DROP CONSTRAINT IF EXISTS badges_family_check;

ALTER TABLE badges
    ADD CONSTRAINT badges_family_check CHECK (family IN ('rank','achievement','honor','officer'));

-- ----------------------------------------------------------------------------
-- 1. TABLA: group_certifications (Padrinos Fundadores y Certificación Estándar)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS group_certifications (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id              UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    certified_by_group_id UUID REFERENCES groups(id) ON DELETE SET NULL,
    certification_type    TEXT NOT NULL CHECK (certification_type IN (
        'mutual_founder',   -- Los primeros 7 clubes de la región se avalaron mutuamente
        'standard_padrino', -- Certificado por un MC Padrino ya existente
        'platform_support'  -- Certificación manual por soporte de la plataforma (arranque)
    )),
    certified_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at            TIMESTAMPTZ,
    revocation_reason     TEXT,
    UNIQUE (group_id) -- Un club solo puede tener una certificación vigente
);

CREATE INDEX IF NOT EXISTS idx_group_certifications_group ON group_certifications(group_id) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_group_certifications_country ON group_certifications(group_id) WHERE revoked_at IS NULL;

-- ----------------------------------------------------------------------------
-- 2. TABLA: ride_guest_invitations (Invitados temporales a rodada, D43)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ride_guest_invitations (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id      UUID NOT NULL REFERENCES rides(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invited_by   UUID NOT NULL REFERENCES users(id),
    state        TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','accepted','declined','expired')),
    accepted_at  TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (ride_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_ride_guest_invitations_ride ON ride_guest_invitations(ride_id);
CREATE INDEX IF NOT EXISTS idx_ride_guest_invitations_user ON ride_guest_invitations(user_id);

-- ----------------------------------------------------------------------------
-- 3. Columnas nuevas en groups (jerarquía)
-- ----------------------------------------------------------------------------
ALTER TABLE groups
    ADD COLUMN IF NOT EXISTS tier TEXT NOT NULL DEFAULT 'RC' CHECK (tier IN ('RC','MG','Gang','MC')),
    ADD COLUMN IF NOT EXISTS legacy_since DATE,
    ADD COLUMN IF NOT EXISTS is_certified BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS sponsor_group_id UUID REFERENCES groups(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_groups_tier ON groups(tier);
CREATE INDEX IF NOT EXISTS idx_groups_is_certified ON groups(is_certified) WHERE is_certified = true;

-- ----------------------------------------------------------------------------
-- 4. TRIGGER: D42 - Un líder = un solo grupo
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_group_leaders_single_group_per_user_fn()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM group_leaders
        WHERE user_id = NEW.user_id
          AND group_id != NEW.group_id
    ) THEN
        RAISE EXCEPTION 'D42: El usuario % ya es líder de otro grupo', NEW.user_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_group_leaders_single_group_per_user ON group_leaders;
CREATE TRIGGER trg_group_leaders_single_group_per_user
    BEFORE INSERT OR UPDATE ON group_leaders
    FOR EACH ROW EXECUTE FUNCTION trg_group_leaders_single_group_per_user_fn();

-- ----------------------------------------------------------------------------
-- 5. TRIGGER: D43 - Sin membresías cruzadas
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_memberships_no_cross_membership_fn()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.invite_state = 'accepted'
       AND NEW.status_kind IN ('full', 'prospect') THEN
        IF EXISTS (
            SELECT 1 FROM memberships
            WHERE user_id = NEW.user_id
              AND group_id != NEW.group_id
              AND invite_state = 'accepted'
              AND status_kind IN ('full', 'prospect')
        ) THEN
            RAISE EXCEPTION 'D43: El usuario % ya tiene una membresía activa (full/prospect) en otro grupo', NEW.user_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_memberships_no_cross_membership ON memberships;
CREATE TRIGGER trg_memberships_no_cross_membership
    BEFORE INSERT OR UPDATE ON memberships
    FOR EACH ROW EXECUTE FUNCTION trg_memberships_no_cross_membership_fn();

-- ----------------------------------------------------------------------------
-- 6. TRIGGER: D46 - Directiva mínima para crear/activar grupo
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_groups_minimum_directiva_fn()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM group_leaders
        WHERE group_id = NEW.id
          AND club_role IN ('Presidente', 'Vicepresidente', 'Sargento de Armas')
    ) THEN
        RAISE EXCEPTION 'D46: El grupo % no puede estar activo sin una directiva mínima (Presidente, Vicepresidente, Sargento de Armas)', NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Nota: Este trigger se ejecuta al hacer UPDATE de groups.status a 'active'
CREATE OR REPLACE FUNCTION trg_groups_minimum_directiva_on_activate_fn()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'active' AND OLD.status != 'active' THEN
        IF NOT EXISTS (
            SELECT 1 FROM group_leaders
            WHERE group_id = NEW.id
              AND club_role IN ('Presidente', 'Vicepresidente', 'Sargento de Armas')
        ) THEN
            RAISE EXCEPTION 'D46: El grupo % no puede activarse sin directiva mínima (Presidente, Vicepresidente, Sargento de Armas)', NEW.id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_groups_minimum_directiva_on_activate ON groups;
CREATE TRIGGER trg_groups_minimum_directiva_on_activate
    BEFORE UPDATE ON groups
    FOR EACH ROW EXECUTE FUNCTION trg_groups_minimum_directiva_on_activate_fn();

-- ----------------------------------------------------------------------------
-- 7. TRIGGER: D44 - Insignias de Directiva solo a miembros
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_badges_officer_must_be_member_fn()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.family = 'officer' THEN
        IF NOT EXISTS (
            SELECT 1 FROM memberships
            WHERE user_id = NEW.receiver
              AND group_id = NEW.issuer_group
              AND invite_state = 'accepted'
        ) THEN
            RAISE EXCEPTION 'D44: La insignia de directiva solo puede otorgarse a miembros del grupo emisor';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_badges_officer_must_be_member ON badges;
CREATE TRIGGER trg_badges_officer_must_be_member
    BEFORE INSERT OR UPDATE ON badges
    FOR EACH ROW EXECUTE FUNCTION trg_badges_officer_must_be_member_fn();

-- ----------------------------------------------------------------------------
-- 8. TRIGGER: D44 - Insignias de Honor requieren participación en rodada
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_badges_honor_requires_guest_ride_fn()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.family = 'honor' AND NEW.code != 'founder' THEN
        IF NOT EXISTS (
            SELECT 1 FROM ride_guest_invitations rgi
            JOIN ride_participants rp ON rp.ride_id = rgi.ride_id AND rp.user_id = rgi.user_id
            WHERE rgi.user_id = NEW.receiver
              AND rgi.state = 'accepted'
              AND rp.ride_id = NEW.ride_id
        ) THEN
            RAISE EXCEPTION 'D44: La insignia de honor requiere que el receptor haya participado como invitado en una rodada del grupo emisor';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_badges_honor_requires_guest_ride ON badges;
CREATE TRIGGER trg_badges_honor_requires_guest_ride
    BEFORE INSERT OR UPDATE ON badges
    FOR EACH ROW EXECUTE FUNCTION trg_badges_honor_requires_guest_ride_fn();

-- ----------------------------------------------------------------------------
-- 9. TRIGGER: Padrino debe ser MC certificado
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_groups_sponsor_must_be_certified_mc_fn()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.sponsor_group_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM groups
            WHERE id = NEW.sponsor_group_id
              AND tier = 'MC'
              AND is_certified = true
        ) THEN
            RAISE EXCEPTION 'El padrino debe ser un MC certificado';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_groups_sponsor_must_be_certified_mc ON groups;
CREATE TRIGGER trg_groups_sponsor_must_be_certified_mc
    BEFORE INSERT OR UPDATE ON groups
    FOR EACH ROW EXECUTE FUNCTION trg_groups_sponsor_must_be_certified_mc_fn();

-- ----------------------------------------------------------------------------
-- 10. TRIGGER: Regla de los 7 clubes (núcleo fundador)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_group_certifications_founder_min_seven_fn()
RETURNS TRIGGER AS $$
DECLARE
    v_country CHAR(2);
    v_founder_count INT;
BEGIN
    IF NEW.certification_type = 'mutual_founder' THEN
        SELECT country_code INTO v_country
        FROM groups WHERE id = NEW.group_id;
        
        SELECT COUNT(*) INTO v_founder_count
        FROM group_certifications gc
        JOIN groups g ON g.id = gc.group_id
        WHERE g.country_code = v_country
          AND gc.certification_type = 'mutual_founder'
          AND gc.revoked_at IS NULL;
        
        IF v_founder_count < 7 THEN
            RAISE NOTICE 'Núcleo fundador para %: % de 7 clubes certificados', v_country, v_founder_count;
        ELSE
            RAISE NOTICE 'Núcleo fundador para % completo: 7 clubes certificados', v_country;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_group_certifications_founder_min_seven ON group_certifications;
CREATE TRIGGER trg_group_certifications_founder_min_seven
    AFTER INSERT ON group_certifications
    FOR EACH ROW EXECUTE FUNCTION trg_group_certifications_founder_min_seven_fn();

-- ----------------------------------------------------------------------------
-- 11. Columnas de gamificación (preparación Fase 6)
-- ----------------------------------------------------------------------------
ALTER TABLE rides
    ADD COLUMN IF NOT EXISTS distance_km REAL,
    ADD COLUMN IF NOT EXISTS elevation_gain_m REAL,
    ADD COLUMN IF NOT EXISTS started_at_night BOOLEAN DEFAULT false,
    ADD COLUMN IF NOT EXISTS rained BOOLEAN DEFAULT false,
    ADD COLUMN IF NOT EXISTS offline_minutes INT DEFAULT 0;

ALTER TABLE rank_progress
    ADD COLUMN IF NOT EXISTS total_km REAL NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS streak_months INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_ride_month CHAR(7),
    ADD COLUMN IF NOT EXISTS xp INT NOT NULL DEFAULT 0;

COMMIT;
