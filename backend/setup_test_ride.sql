DO $$
DECLARE
    v_group_id UUID := '87167f44-8148-4488-bdeb-39e172bf6a7c';
    v_user_id UUID := '54ba61a2-1095-4114-8604-974f25babdb0';
    v_ride_id UUID := 'a1b2c3d4-e5f6-47a8-b9c0-112233445566';
BEGIN
    -- Asegurar pertenencia al grupo como sargento_armas
    DELETE FROM memberships WHERE user_id = v_user_id;
    INSERT INTO memberships (id, group_id, user_id, status_kind, club_role, invite_state, accepted_at)
    VALUES (gen_random_uuid(), v_group_id, v_user_id, 'full', 'sargento_armas', 'accepted', NOW())
    ON CONFLICT (group_id, user_id) DO UPDATE SET club_role = 'sargento_armas', invite_state = 'accepted', status_kind = 'full';

    INSERT INTO group_leaders (group_id, user_id)
    VALUES (v_group_id, v_user_id)
    ON CONFLICT DO NOTHING;

    -- Crear o actualizar rodada activa
    DELETE FROM shared_medical_sheets WHERE ride_id = v_ride_id;
    DELETE FROM ride_participants WHERE ride_id = v_ride_id;
    DELETE FROM ride_groups WHERE ride_id = v_ride_id;
    DELETE FROM rides WHERE id = v_ride_id;

    INSERT INTO rides (id, host_group, host_leader, title, state, starts_at, duration_minutes)
    VALUES (v_ride_id, v_group_id, v_user_id, 'Rodada Oficial E2EE', 'active', NOW(), 120);

    INSERT INTO ride_groups (ride_id, group_id)
    VALUES (v_ride_id, v_group_id)
    ON CONFLICT DO NOTHING;

    INSERT INTO ride_participants (ride_id, user_id, group_id, joined_at)
    VALUES (v_ride_id, v_user_id, v_group_id, NOW())
    ON CONFLICT DO NOTHING;

    RAISE NOTICE 'Test ride and membership setup complete: ride_id=%, group_id=%', v_ride_id, v_group_id;
END $$;
