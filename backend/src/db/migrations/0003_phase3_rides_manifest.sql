-- ============================================================================
-- Migración 0003: Fase 3 (Bloque 3.1) — Ciclo de vida de Rodada, Manifiesto
-- y validación en BD de RULES.md R6.2, R6.3, R6.4 y R6.6
-- Fecha: 2026-10-24
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1. Columnas adicionales en rides para rodadas conjuntas (R6.3) y cierre R6.6
-- ----------------------------------------------------------------------------
ALTER TABLE rides
    ADD COLUMN IF NOT EXISTS cohost_group UUID REFERENCES groups(id),
    ADD COLUMN IF NOT EXISTS cohost_leader UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS duration_minutes INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS closed_by_leader UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS manifest_kid TEXT,
    ADD COLUMN IF NOT EXISTS manifest_issued_at TIMESTAMPTZ;

-- ----------------------------------------------------------------------------
-- 2. Columnas adicionales en ride_participants para lista cerrada (R6.2),
--    roles de rodada (R3.3, R8.3) y métricas de validación anti-trampa (R6.6)
-- ----------------------------------------------------------------------------
ALTER TABLE ride_participants
    ADD COLUMN IF NOT EXISTS status_kind TEXT NOT NULL DEFAULT 'full'
        CHECK (status_kind IN ('leader','full','prospect','guest')),
    ADD COLUMN IF NOT EXISTS ride_role TEXT
        CHECK (ride_role IS NULL OR ride_role IN ('participante','scout','sweep','capitan_rodada','road_marshal')),
    ADD COLUMN IF NOT EXISTS has_mesh_contact BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS presence_pct REAL NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS counted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS joined_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- ----------------------------------------------------------------------------
-- 3. TRIGGER: R6.3 — Máximo 2 grupos por rodada (anfitrión y coanfitrión)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_ride_groups_max_two_fn()
RETURNS TRIGGER AS $$
DECLARE
    v_count INT;
BEGIN
    SELECT COUNT(*) INTO v_count
    FROM ride_groups
    WHERE ride_id = NEW.ride_id
      AND group_id != NEW.group_id;

    IF v_count >= 2 THEN
        RAISE EXCEPTION 'R6.3: Una rodada conjunta admite como máximo 2 grupos';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ride_groups_max_two ON ride_groups;
CREATE TRIGGER trg_ride_groups_max_two
    BEFORE INSERT OR UPDATE ON ride_groups
    FOR EACH ROW EXECUTE FUNCTION trg_ride_groups_max_two_fn();

-- ----------------------------------------------------------------------------
-- 4. TRIGGER: R6.2 y R6.4 — Lista cerrada: solo miembros/líderes de los grupos
--    de la rodada o invitados temporales con invitación aceptada (D43)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_ride_participants_authorize_entry_fn()
RETURNS TRIGGER AS $$
DECLARE
    v_host_group UUID;
    v_cohost_group UUID;
    v_is_member_or_leader BOOLEAN;
    v_has_accepted_guest_invite BOOLEAN;
BEGIN
    SELECT host_group, cohost_group
    INTO v_host_group, v_cohost_group
    FROM rides
    WHERE id = NEW.ride_id;

    IF v_host_group IS NULL THEN
        RAISE EXCEPTION 'R6.2: La rodada % no existe', NEW.ride_id;
    END IF;

    -- Verificar si es líder o miembro aceptado de alguno de los grupos de la rodada
    SELECT EXISTS (
        SELECT 1 FROM group_leaders gl
        WHERE gl.user_id = NEW.user_id
          AND gl.group_id = NEW.group_id
          AND (
              gl.group_id = v_host_group
              OR (v_cohost_group IS NOT NULL AND gl.group_id = v_cohost_group)
              OR EXISTS (SELECT 1 FROM ride_groups rg WHERE rg.ride_id = NEW.ride_id AND rg.group_id = gl.group_id)
          )
    ) OR EXISTS (
        SELECT 1 FROM memberships m
        WHERE m.user_id = NEW.user_id
          AND m.group_id = NEW.group_id
          AND m.invite_state = 'accepted'
          AND (
              m.group_id = v_host_group
              OR (v_cohost_group IS NOT NULL AND m.group_id = v_cohost_group)
              OR EXISTS (SELECT 1 FROM ride_groups rg WHERE rg.ride_id = NEW.ride_id AND rg.group_id = m.group_id)
          )
    ) INTO v_is_member_or_leader;

    IF v_is_member_or_leader THEN
        RETURN NEW;
    END IF;

    -- Si no es miembro/líder de los grupos de la rodada, debe tener invitación temporal aceptada (R6.4)
    SELECT EXISTS (
        SELECT 1 FROM ride_guest_invitations rgi
        WHERE rgi.ride_id = NEW.ride_id
          AND rgi.user_id = NEW.user_id
          AND rgi.state = 'accepted'
    ) INTO v_has_accepted_guest_invite;

    IF NOT v_has_accepted_guest_invite THEN
        RAISE EXCEPTION 'R6.4: El usuario % no pertenece a los grupos de la rodada ni tiene invitación temporal aceptada', NEW.user_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ride_participants_authorize_entry ON ride_participants;
CREATE TRIGGER trg_ride_participants_authorize_entry
    BEFORE INSERT ON ride_participants
    FOR EACH ROW EXECUTE FUNCTION trg_ride_participants_authorize_entry_fn();

-- ----------------------------------------------------------------------------
-- 5. TRIGGER: R6.6 — Validación anti-trampa para que una rodada cuente para el rango
--    Requisitos R6.6:
--    a) >= 2 participantes con contacto real en la mesh (has_mesh_contact = true)
--    b) > 15 km de recorrido (rides.distance_km > 15)
--    c) > 30 min en movimiento (rides.duration_minutes > 30)
--    d) Presencia del participante >= 70% del recorrido (NEW.presence_pct >= 70)
--       y contacto en la mesh (NEW.has_mesh_contact = true)
--    e) Cierre confirmado por un líder (rides.closed_by_leader IS NOT NULL)
--    f) Máximo 3 rodadas contadas por día para el mismo usuario
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_ride_participants_enforce_r66_fn()
RETURNS TRIGGER AS $$
DECLARE
    v_distance_km REAL;
    v_duration_min INT;
    v_closed_by UUID;
    v_closed_at TIMESTAMPTZ;
    v_mesh_contact_count INT;
    v_daily_counted INT;
    v_ride_day DATE;
BEGIN
    IF NEW.counted_for_rank = true AND (TG_OP = 'INSERT' OR OLD.counted_for_rank = false) THEN
        SELECT distance_km, duration_minutes, closed_by_leader, COALESCE(closed_at, starts_at, now())
        INTO v_distance_km, v_duration_min, v_closed_by, v_closed_at
        FROM rides
        WHERE id = NEW.ride_id;

        IF v_closed_by IS NULL THEN
            RAISE EXCEPTION 'R6.6: La rodada debe tener cierre confirmado por un líder';
        END IF;

        IF COALESCE(v_distance_km, 0) <= 15 THEN
            RAISE EXCEPTION 'R6.6: La rodada debe superar 15 km (actual: % km)', COALESCE(v_distance_km, 0);
        END IF;

        IF COALESCE(v_duration_min, 0) <= 30 THEN
            RAISE EXCEPTION 'R6.6: La rodada debe superar 30 minutos en movimiento (actual: % min)', COALESCE(v_duration_min, 0);
        END IF;

        IF NEW.has_mesh_contact = false THEN
            RAISE EXCEPTION 'R6.6: El participante % no tuvo contacto real en la mesh', NEW.user_id;
        END IF;

        IF COALESCE(NEW.presence_pct, 0) < 70 THEN
            RAISE EXCEPTION 'R6.6: El participante % requiere presencia >= 70%% (actual: %)', NEW.user_id, COALESCE(NEW.presence_pct, 0);
        END IF;

        -- Contar participantes con contacto real en la mesh (incluyendo la fila actual)
        SELECT COUNT(*) INTO v_mesh_contact_count
        FROM ride_participants
        WHERE ride_id = NEW.ride_id
          AND user_id != NEW.user_id
          AND has_mesh_contact = true;

        v_mesh_contact_count := v_mesh_contact_count + 1;

        IF v_mesh_contact_count < 2 THEN
            RAISE EXCEPTION 'R6.6: Se requieren al menos 2 participantes con contacto real en la mesh (actual: %)', v_mesh_contact_count;
        END IF;

        -- Verificar límite máximo de 3 rodadas contadas por día para este usuario
        v_ride_day := (v_closed_at AT TIME ZONE 'UTC')::date;

        SELECT COUNT(*) INTO v_daily_counted
        FROM ride_participants rp
        JOIN rides r ON r.id = rp.ride_id
        WHERE rp.user_id = NEW.user_id
          AND rp.ride_id != NEW.ride_id
          AND rp.counted_for_rank = true
          AND (COALESCE(r.closed_at, r.starts_at, now()) AT TIME ZONE 'UTC')::date = v_ride_day;

        IF v_daily_counted >= 3 THEN
            RAISE EXCEPTION 'R6.6: El usuario % ya alcanzó el máximo de 3 rodadas válidas en el día %', NEW.user_id, v_ride_day;
        END IF;

        NEW.counted_at := COALESCE(NEW.counted_at, now());
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ride_participants_enforce_r66 ON ride_participants;
CREATE TRIGGER trg_ride_participants_enforce_r66
    BEFORE INSERT OR UPDATE OF counted_for_rank ON ride_participants
    FOR EACH ROW EXECUTE FUNCTION trg_ride_participants_enforce_r66_fn();

COMMIT;
