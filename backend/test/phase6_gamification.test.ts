import { beforeAll, afterAll, describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, runMigrations, type DbExecutor } from '../src/db/pool.js';
import { CredentialKeyring } from '../src/lib/credentials.js';
import { issueSessionToken } from '../src/lib/auth.js';

describe('Fase 6 — Gamificación, Jerarquía de Clubes (RC->MG->Gang->MC), XP, Rachas y Logros (GAMIFICATION.md)', () => {
  let db: DbExecutor;
  let app: FastifyInstance;
  let credentialKeyring: CredentialKeyring;

  let leaderId = '';
  let leaderToken = '';
  let memberId = '';
  let memberToken = '';
  let guestId = '';
  let guestToken = '';
  let strangerId = '';
  let strangerToken = '';

  let groupId = '';
  let sponsorMcGroupId = '';
  let rideId = '';

  beforeAll(async () => {
    db = createPgPool();
    await runMigrations(db);
    await db.query(`
      TRUNCATE TABLE
        group_certifications,
        ride_guest_invitations,
        refresh_tokens,
      rate_limit_events,
      ephemeral_telemetry,
        imitation_reports,
        play_events,
        ride_mesh_metrics,
        leader_vote_ballots,
        leader_votes,
        moderation_reports,
        consents,
        emergency_access_log,
        badges,
        rank_progress,
        ride_participants,
        ride_groups,
        rides,
        group_transfers,
        invitations,
        memberships,
        group_leaders,
        groups,
        subscriptions,
        users
      CASCADE;
    `);

    credentialKeyring = new CredentialKeyring();
    credentialKeyring.registerKey('kid-gamify-2026', 'ed25519-gamify-seed-32-bytes-ok!');
    app = await buildApp({ db, credentialKeyring });
    await app.ready();

    // 1. Crear usuarios
    leaderId = randomUUID();
    memberId = randomUUID();
    guestId = randomUUID();
    strangerId = randomUUID();

    const makeUser = async (id: string, email: string, accountType: string, name: string) => {
      await db.query(
        `INSERT INTO users (id, email, password_hash, display_name, country_code, account_type)
         VALUES ($1, $2, 'scrypt$dummy$dummy', $3, 'MX', $4)`,
        [id, email, name, accountType]
      );
      return issueSessionToken(id, 'access').token;
    };

    leaderToken = await makeUser(leaderId, 'capitan.gamify@biker.com', 'leader', 'Capitán Rivas');
    memberToken = await makeUser(memberId, 'member.gamify@biker.com', 'member', 'Rider Carlos');
    guestToken = await makeUser(guestId, 'guest.gamify@biker.com', 'member', 'Invitado Pedro');
    strangerToken = await makeUser(strangerId, 'stranger@biker.com', 'member', 'Desconocido');

    // Suscripción de líder
    await db.query(
      `INSERT INTO subscriptions (id, user_id, kind, play_product_id, play_purchase_token, state, current_period_end, last_verified_at)
       VALUES ($1, $2, 'leader', 'leader_monthly', $3, 'active', now() + interval '30 days', now())`,
      [randomUUID(), leaderId, `token-${leaderId}`]
    );

    // 2. Crear Grupo
    groupId = randomUUID();
    await db.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader, tier, created_at)
       VALUES ($1, 'MX', 'Águilas del Camino RC', 'aguilas del camino rc', E'\\\\x0102030405060708', $2, 'RC', now() - interval '100 days')`,
      [groupId, leaderId]
    );
    await db.query(
      `INSERT INTO group_leaders (group_id, user_id, is_principal) VALUES ($1, $2, true)`,
      [groupId, leaderId]
    );

    // Miembro oficial
    await db.query(
      `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state)
       VALUES ($1, $2, $3, 'full', 'accepted')`,
      [randomUUID(), groupId, memberId]
    );

    // Sponsor MC para pruebas de jerarquía (con más de 400 días de antigüedad)
    sponsorMcGroupId = randomUUID();
    await db.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader, tier, created_at)
       VALUES ($1, 'MX', 'Nómadas del Norte MC', 'nomadas del norte mc', E'\\\\x0203040506070809', $2, 'MC', now() - interval '400 days')`,
      [sponsorMcGroupId, leaderId]
    );
  });

  afterAll(async () => {
    await app.close();
  });

  it('1. Cierre de rodada válida calcula XP y otorga insignias de kilometraje e iniciación', async () => {
    rideId = randomUUID();
    await db.query(
      `INSERT INTO rides (id, host_group, host_leader, title, state, starts_at)
       VALUES ($1, $2, $3, 'Rodada de Altura', 'active', now())`,
      [rideId, groupId, leaderId]
    );

    // Registrar participantes: Líder como capitán (52 km, 95% presencia) y Miembro (52 km, 90% presencia)
    await db.query(
      `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind, ride_role, has_mesh_contact, presence_pct)
       VALUES
        ($1, $2, $3, 'leader', 'capitan_rodada', true, 95),
        ($1, $4, $3, 'full', 'participante', true, 90)`,
      [rideId, leaderId, groupId, memberId]
    );

    // Cerrar rodada cumpliendo R6.6 (52 km, 75 min, 2 contactos mesh)
    const closeRes = await app.inject({
      method: 'POST',
      url: `/v1/rides/${rideId}/close`,
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: {
        distanceKm: 52,
        durationMinutes: 75,
        startedAtNight: false,
        rained: true, // Debe otorgar exp_bajo_la_lluvia
        offlineMinutes: 40, // Debe otorgar exp_sin_cobertura (>30m)
        closedAtIso: '2026-08-15T18:00:00.000Z',
        participants: [
          { userId: leaderId, hasMeshContact: true, presencePct: 95 },
          { userId: memberId, hasMeshContact: true, presencePct: 90 }
        ]
      }
    });

    expect(closeRes.statusCode).toBe(200);
    const closeBody = closeRes.json();
    expect(closeBody.state).toBe('validated');
    expect(closeBody.rideMeetsR66).toBe(true);
    expect(closeBody.countedUsers).toContain(leaderId);
    expect(closeBody.countedUsers).toContain(memberId);

    // Validar XP del Líder (Capitán de rodada):
    // Base 10 XP + Distancia 52 km (1 XP) + Rol capitán (5 XP) = 16 XP
    const leaderGamify = await app.inject({
      method: 'GET',
      url: `/v1/users/${leaderId}/gamification`
    });
    expect(leaderGamify.statusCode).toBe(200);
    const lBody = leaderGamify.json();
    expect(lBody.validRides).toBe(1);
    expect(lBody.totalKm).toBe(52);
    expect(lBody.streakMonths).toBe(1);
    expect(lBody.xp).toBe(16);
    expect(lBody.currentRank.code).toBe('novato');
    expect(lBody.currentRank.nextRankTitle).toBe('Rider');
    expect(lBody.currentRank.progressPct).toBe(16);

    // Comprobar insignias desbloqueadas
    const badgeCodes = lBody.badges.map((b: any) => b.code);
    expect(badgeCodes).toContain('exp_primera_rodada');
    expect(badgeCodes).toContain('km_10_primeros_pasos');
    expect(badgeCodes).toContain('km_50_rodador');
    expect(badgeCodes).toContain('exp_bajo_la_lluvia');
    expect(badgeCodes).toContain('exp_sin_cobertura');
  });

  it('2. Rodadas en meses consecutivos incrementan racha y otorgan bonus de racha a los 3 meses', async () => {
    // Mes 2: Septiembre 2026
    const ride2Id = randomUUID();
    await db.query(
      `INSERT INTO rides (id, host_group, host_leader, title, state, starts_at)
       VALUES ($1, $2, $3, 'Rodada Mes 2', 'active', now())`,
      [ride2Id, groupId, leaderId]
    );
    await db.query(
      `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind, ride_role, has_mesh_contact, presence_pct)
       VALUES ($1, $2, $3, 'leader', 'participante', true, 90), ($1, $4, $3, 'full', 'participante', true, 90)`,
      [ride2Id, leaderId, groupId, memberId]
    );
    await app.inject({
      method: 'POST',
      url: `/v1/rides/${ride2Id}/close`,
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: {
        distanceKm: 20,
        durationMinutes: 45,
        closedAtIso: '2026-09-10T14:00:00.000Z',
        participants: [
          { userId: leaderId, hasMeshContact: true, presencePct: 90 },
          { userId: memberId, hasMeshContact: true, presencePct: 90 }
        ]
      }
    });

    const checkMonth2 = await app.inject({
      method: 'GET',
      url: `/v1/users/${leaderId}/gamification`
    });
    expect(checkMonth2.json().streakMonths).toBe(2);

    // Mes 3: Octubre 2026 (Cumple racha de 3 meses -> +20 XP de bonus)
    const ride3Id = randomUUID();
    await db.query(
      `INSERT INTO rides (id, host_group, host_leader, title, state, starts_at)
       VALUES ($1, $2, $3, 'Rodada Mes 3', 'active', now())`,
      [ride3Id, groupId, leaderId]
    );
    await db.query(
      `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind, ride_role, has_mesh_contact, presence_pct)
       VALUES ($1, $2, $3, 'leader', 'participante', true, 90), ($1, $4, $3, 'full', 'participante', true, 90)`,
      [ride3Id, leaderId, groupId, memberId]
    );
    await app.inject({
      method: 'POST',
      url: `/v1/rides/${ride3Id}/close`,
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: {
        distanceKm: 30,
        durationMinutes: 50,
        closedAtIso: '2026-10-18T10:00:00.000Z',
        participants: [
          { userId: leaderId, hasMeshContact: true, presencePct: 90 },
          { userId: memberId, hasMeshContact: true, presencePct: 90 }
        ]
      }
    });

    const checkMonth3 = await app.inject({
      method: 'GET',
      url: `/v1/users/${leaderId}/gamification`
    });
    const m3Body = checkMonth3.json();
    expect(m3Body.streakMonths).toBe(3);
    const m3Badges = m3Body.badges.map((b: any) => b.code);
    expect(m3Badges).toContain('streak_3_meses');
    expect(m3Badges).toContain('km_100_caminante'); // 52 + 20 + 30 = 102 km
  });

  it('3. Jerarquía de Clubes: Trigger valida transición RC -> MG y exige sponsor para MC', async () => {
    // El grupo tiene 100 días de antigüedad (> 90 días), por lo que la transición a MG debe permitirse
    const toMgRes = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/transition-tier`,
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: { targetTier: 'MG' }
    });
    expect(toMgRes.statusCode).toBe(200);
    expect(toMgRes.json().newTier).toBe('MG');

    // Intentar pasar a MC sin cumplir antigüedad de 365 días o sin sponsor válido debe ser rechazado
    const toMcNoSponsor = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/transition-tier`,
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: { targetTier: 'MC' }
    });
    expect(toMcNoSponsor.statusCode).toBe(400);

    // Intentar pasar a MC con antigüedad insuficiente (<365 días) debe fallar por trigger
    const toMcUnderage = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/transition-tier`,
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: { targetTier: 'MC', sponsorGroupId: sponsorMcGroupId }
    });
    expect(toMcUnderage.statusCode).toBe(400);
    expect(toMcUnderage.json().error).toMatch(/365 días/);
  });

  it('4. Insignia de Honor: Rechaza otorgar a extraño pero permite a invitado que compartió rodada', async () => {
    // 1. Intentar otorgar insignia de honor a alguien que NUNCA ha rodado con el grupo (strangerId)
    const failHonor = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/badges/honor`,
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: {
        receiverUserId: strangerId,
        code: 'honor_ruta_lealtad'
      }
    });
    expect(failHonor.statusCode).toBe(400);
    expect(failHonor.json().error).toMatch(/sin haber compartido una rodada previa/);

    // 2. Ahora registrar una rodada donde el invitado (guestId) sí rodó con el grupo
    const guestRideId = randomUUID();
    await db.query(
      `INSERT INTO rides (id, host_group, host_leader, title, state, starts_at, closed_by_leader, closed_at, distance_km, duration_minutes)
       VALUES ($1, $2, $3, 'Rodada de Invitados', 'validated', now(), $3, now(), 35, 60)`,
      [guestRideId, groupId, leaderId]
    );
    await db.query(
      `INSERT INTO ride_guest_invitations (id, ride_id, user_id, invited_by, state, accepted_at, expires_at)
       VALUES ($1, $2, $3, $4, 'accepted', now(), now() + interval '1 day')`,
      [randomUUID(), guestRideId, guestId, leaderId]
    );
    await db.query(
      `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind, ride_role, has_mesh_contact, presence_pct, counted_for_rank)
       VALUES ($1, $2, $3, 'leader', 'capitan_rodada', true, 95, false)`,
      [guestRideId, leaderId, groupId]
    );
    await db.query(
      `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind, ride_role, has_mesh_contact, presence_pct, counted_for_rank)
       VALUES ($1, $2, $3, 'guest', 'participante', true, 90, true)`,
      [guestRideId, guestId, groupId]
    );

    // 3. Otorgar insignia de honor al invitado confirmado
    const successHonor = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/badges/honor`,
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: {
        receiverUserId: guestId,
        code: 'honor_hermandad_vial'
      }
    });
    expect(successHonor.statusCode).toBe(201);
    const honorBody = successHonor.json();
    expect(honorBody.family).toBe('honor');
    expect(honorBody.code).toBe('honor_hermandad_vial');
    expect(honorBody.xpAwarded).toBe(15);

    // Verificar en el perfil de gamificación del invitado que se reflejan los +15 XP
    const guestGamify = await app.inject({
      method: 'GET',
      url: `/v1/users/${guestId}/gamification`
    });
    expect(guestGamify.json().xp).toBe(15);
    const guestBadges = guestGamify.json().badges.map((b: any) => b.code);
    expect(guestBadges).toContain('honor_hermandad_vial');
  });
});
