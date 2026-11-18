import { beforeAll, afterAll, describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, runMigrations, type DbExecutor } from '../src/db/pool.js';
import {
  CredentialKeyring,
  issueOfflineCredential,
  verifyOfflineCredential,
  verifyRideManifest
} from '../src/lib/credentials.js';

describe('Fase 3 (Bloque 3.1) — Ciclo de vida de Rodada, Manifiesto firmado Ed25519 (1 o 2 grupos) y Validación Anti-trampa R6.6 en PostgreSQL 16', () => {
  let db: DbExecutor;
  let app: FastifyInstance;
  let credentialKeyring: CredentialKeyring;

  let hostLeaderId = '';
  let hostLeaderToken = '';
  let hostMemberId = '';
  let hostMemberToken = '';
  let cohostLeaderId = '';
  let cohostLeaderToken = '';
  let cohostMemberId = '';
  let cohostMemberToken = '';
  let outsiderGuestId = '';
  let outsiderGuestToken = '';

  let hostGroupId = '';
  let cohostGroupId = '';
  let thirdGroupId = '';
  let jointRideId = '';

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
      RESTART IDENTITY CASCADE
    `);

    credentialKeyring = new CredentialKeyring('ed25519-f3-k1');
    app = await buildApp({
      db,
      useFakeBilling: true,
      credentialKeyring
    });

    const registerUser = async (
      email: string,
      displayName: string,
      activateLeaderDemo: boolean
    ) => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/auth/register',
        payload: {
          email,
          password: 'RiderPassword2026!',
          displayName,
          countryCode: 'MX',
          acceptedTermsAndConvivencia: true,
          activateLeaderDemo
        }
      });
      expect(res.statusCode).toBe(200);
      return {
        id: res.json().user.id as string,
        token: res.json().accessToken as string
      };
    };

    const hl = await registerUser('host-leader@f3.mx', 'Capitán Águila', true);
    hostLeaderId = hl.id;
    hostLeaderToken = hl.token;

    const hm = await registerUser('host-member@f3.mx', 'Halcón Uno', false);
    hostMemberId = hm.id;
    hostMemberToken = hm.token;

    const cl = await registerUser('cohost-leader@f3.mx', 'Comandante Lobo', true);
    cohostLeaderId = cl.id;
    cohostLeaderToken = cl.token;

    const cm = await registerUser('cohost-member@f3.mx', 'Lobo Dos', false);
    cohostMemberId = cm.id;
    cohostMemberToken = cm.token;

    const og = await registerUser('outsider-guest@f3.mx', 'Nómada Invitado', false);
    outsiderGuestId = og.id;
    outsiderGuestToken = og.token;

    const tl = await registerUser('third-leader@f3.mx', 'Líder Tercero', true);

    // Crear Grupo Anfitrión, Grupo Coanfitrión y un 3.er Grupo ajeno
    const g1 = await app.inject({
      method: 'POST',
      url: '/v1/groups',
      headers: { authorization: `Bearer ${hostLeaderToken}` },
      payload: {
        countryCode: 'MX',
        name: 'Águilas del Desierto F3',
        logoPhashHex: '1111222233334444'
      }
    });
    expect(g1.statusCode).toBe(201);
    hostGroupId = g1.json().id;

    const g2 = await app.inject({
      method: 'POST',
      url: '/v1/groups',
      headers: { authorization: `Bearer ${cohostLeaderToken}` },
      payload: {
        countryCode: 'MX',
        name: 'Lobos de la Sierra F3',
        logoPhashHex: '9999888877776666'
      }
    });
    expect(g2.statusCode).toBe(201);
    cohostGroupId = g2.json().id;

    const g3 = await app.inject({
      method: 'POST',
      url: '/v1/groups',
      headers: { authorization: `Bearer ${tl.token}` },
      payload: {
        countryCode: 'MX',
        name: 'Cuervos del Pacífico F3',
        logoPhashHex: 'aaaa5555aaaa5555'
      }
    });
    expect(g3.statusCode).toBe(201);
    thirdGroupId = g3.json().id;

    // Incorporar hostMember a hostGroup y cohostMember a cohostGroup
    for (const [gid, lToken, mId, mToken] of [
      [hostGroupId, hostLeaderToken, hostMemberId, hostMemberToken],
      [cohostGroupId, cohostLeaderToken, cohostMemberId, cohostMemberToken]
    ] as const) {
      const inv = await app.inject({
        method: 'POST',
        url: `/v1/groups/${gid}/invitations`,
        headers: { authorization: `Bearer ${lToken}` },
        payload: { invitedUserId: mId, statusKind: 'full' }
      });
      expect(inv.statusCode).toBe(201);
      const acc = await app.inject({
        method: 'POST',
        url: `/v1/invitations/${inv.json().id}/accept`,
        headers: { authorization: `Bearer ${mToken}` },
        payload: {
          consents: {
            membership: true,
            location: true,
            emergencyPackage: true,
            notifications: true
          }
        }
      });
      expect(acc.statusCode).toBe(200);
    }
  });

  afterAll(async () => {
    await app.close();
    await db.end();
  });

  it('R6.2, R6.3, R14.7: crea rodada conjunta de 2 grupos, rechaza un 3.er grupo en SQL y cada líder controla solo a su propio grupo', async () => {
    const createRes = await app.inject({
      method: 'POST',
      url: '/v1/rides',
      headers: { authorization: `Bearer ${hostLeaderToken}` },
      payload: {
        hostGroupId,
        cohostGroupId,
        cohostLeaderId,
        title: 'Rodada Conjunta Sierra Madre',
        routeHash: 'sha256:7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069',
        participants: [
          { userId: hostMemberId, groupId: hostGroupId, statusKind: 'full', rideRole: 'scout' }
        ]
      }
    });
    expect(createRes.statusCode).toBe(201);
    jointRideId = createRes.json().id;
    expect(createRes.json().hostGroupId).toBe(hostGroupId);
    expect(createRes.json().cohostGroupId).toBe(cohostGroupId);

    // Trigger SQL trg_ride_groups_max_two rechaza un 3.er grupo en la misma rodada (R6.3)
    await expect(
      db.query(`INSERT INTO ride_groups (ride_id, group_id) VALUES ($1, $2)`, [
        jointRideId,
        thirdGroupId
      ])
    ).rejects.toThrow(/R6.3: Una rodada conjunta admite como máximo 2 grupos/);

    // R6.3 y R14.7: El líder anfitrión NO puede inscribir/controlar directamente a un miembro del grupo coanfitrión
    const crossControlRes = await app.inject({
      method: 'POST',
      url: `/v1/rides/${jointRideId}/participants`,
      headers: { authorization: `Bearer ${hostLeaderToken}` },
      payload: {
        userId: cohostMemberId,
        groupId: cohostGroupId,
        statusKind: 'full',
        rideRole: 'sweep'
      }
    });
    expect(crossControlRes.statusCode).toBe(403);
    expect(crossControlRes.json().error).toBe('CANNOT_CONTROL_OTHER_GROUP_IN_JOINT_RIDE');

    // En cambio, el líder coanfitrión sí controla e inscribe a su propia gente (cohostLeader y cohostMember)
    const addCohostLeader = await app.inject({
      method: 'POST',
      url: `/v1/rides/${jointRideId}/participants`,
      headers: { authorization: `Bearer ${cohostLeaderToken}` },
      payload: {
        userId: cohostLeaderId,
        groupId: cohostGroupId,
        statusKind: 'leader',
        rideRole: 'road_marshal'
      }
    });
    expect(addCohostLeader.statusCode).toBe(201);

    const addCohostMember = await app.inject({
      method: 'POST',
      url: `/v1/rides/${jointRideId}/participants`,
      headers: { authorization: `Bearer ${cohostLeaderToken}` },
      payload: {
        userId: cohostMemberId,
        groupId: cohostGroupId,
        statusKind: 'full',
        rideRole: 'sweep'
      }
    });
    expect(addCohostMember.statusCode).toBe(201);
  });

  it('R6.4 y D43: un externo sin invitación temporal es bloqueado en SQL, y al aceptar su invitación entra como guest a la rodada', async () => {
    // 1. Intento directo en SQL de meter a un externo sin invitación aceptada -> rechazado por trg_ride_participants_authorize_entry
    await expect(
      db.query(
        `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind)
         VALUES ($1, $2, $3, 'guest')`,
        [jointRideId, outsiderGuestId, hostGroupId]
      )
    ).rejects.toThrow(/R6.4: El usuario .* no pertenece a los grupos de la rodada ni tiene invitación temporal aceptada/);

    // 2. El líder anfitrión emite invitación temporal por rodada (POST /v1/rides/:rideId/guest-invitations)
    const invRes = await app.inject({
      method: 'POST',
      url: `/v1/rides/${jointRideId}/guest-invitations`,
      headers: { authorization: `Bearer ${hostLeaderToken}` },
      payload: { userId: outsiderGuestId, expiresInHours: 48 }
    });
    expect(invRes.statusCode).toBe(201);
    const invitationId = invRes.json().id;

    // 3. El invitado acepta con consentimiento explícito para esa rodada (R2.1, R6.4, R15.4)
    const accRes = await app.inject({
      method: 'POST',
      url: `/v1/rides/${jointRideId}/guest-invitations/${invitationId}/accept`,
      headers: { authorization: `Bearer ${outsiderGuestToken}` },
      payload: { consentLocation: true }
    });
    expect(accRes.statusCode).toBe(200);
    expect(accRes.json().state).toBe('accepted');
  });

  it('BACKEND_LICENSING.md §7 y §8: POST /v1/rides/:id/manifest emite manifiesto firmado con Ed25519 con los 2 grupos y valida credenciales cruzadas en la mesh', async () => {
    const manifestRes = await app.inject({
      method: 'POST',
      url: `/v1/rides/${jointRideId}/manifest`,
      headers: { authorization: `Bearer ${hostLeaderToken}` }
    });
    expect(manifestRes.statusCode).toBe(200);

    const body = manifestRes.json();
    expect(body.payload.type).toBe('ride_manifest');
    expect(body.payload.rideId).toBe(jointRideId);
    expect(body.payload.groupIds).toEqual([hostGroupId, cohostGroupId]);
    expect(body.payload.participants.length).toBe(5); // hostLeader, hostMember, cohostLeader, cohostMember, outsiderGuest

    // Verificar firma Ed25519 del manifiesto sin internet y autorización de participantes
    const verification = verifyRideManifest(body.manifest, {
      requireParticipantUid: cohostMemberId,
      keyring: credentialKeyring
    });
    expect(verification.valid).toBe(true);
    expect(verification.payload?.groupIds).toContain(hostGroupId);
    expect(verification.payload?.groupIds).toContain(cohostGroupId);

    // Un usuario que no está en la lista cerrada del manifiesto es rechazado
    const unauthorizedCheck = verifyRideManifest(body.manifest, {
      requireParticipantUid: randomUUID(),
      keyring: credentialKeyring
    });
    expect(unauthorizedCheck.valid).toBe(false);
    expect(unauthorizedCheck.reason).toBe('PARTICIPANT_NOT_AUTHORIZED');

    // Con el manifiesto de 2 grupos, los teléfonos aceptan credenciales Ed25519 tanto de hostGroupId como de cohostGroupId,
    // pero rechazan credenciales de thirdGroupId
    const nowSec = Math.floor(Date.now() / 1000);
    const credCohost = issueOfflineCredential(
      {
        uid: cohostMemberId,
        gid: cohostGroupId,
        kind: 'full',
        devicePublicKeyBase64: 'pk-cohost',
        effectiveAccessUntilSec: nowSec + 86400
      },
      credentialKeyring
    );
    const credThirdGroup = issueOfflineCredential(
      {
        uid: randomUUID(),
        gid: thirdGroupId,
        kind: 'full',
        devicePublicKeyBase64: 'pk-third',
        effectiveAccessUntilSec: nowSec + 86400
      },
      credentialKeyring
    );

    expect(
      verifyOfflineCredential(credCohost.credential, {
        expectedGroupIds: body.payload.groupIds,
        keyring: credentialKeyring
      }).valid
    ).toBe(true);

    const rejectedThird = verifyOfflineCredential(credThirdGroup.credential, {
      expectedGroupIds: body.payload.groupIds,
      keyring: credentialKeyring
    });
    expect(rejectedThird.valid).toBe(false);
    expect(rejectedThird.reason).toBe('GROUP_MISMATCH');
  });

  it('R6.6: trigger SQL trg_ride_participants_enforce_r66 y POST /v1/rides/:id/close exigen >=2 nodos con contacto mesh, >15 km, >30 min, >=70% presencia, cierre por líder y máx. 3 rodadas/día', async () => {
    // 1. Intento directo en SQL de marcar counted_for_rank = true antes de cerrar la rodada -> rechazado por trigger R6.6
    await expect(
      db.query(
        `UPDATE ride_participants SET counted_for_rank = true WHERE ride_id = $1 AND user_id = $2`,
        [jointRideId, hostLeaderId]
      )
    ).rejects.toThrow(/R6.6: La rodada debe tener cierre confirmado por un líder/);

    // 2. Un miembro que NO es líder intenta cerrar la rodada -> 403 LEADER_REQUIRED_TO_CLOSE_RIDE
    const memberCloseTry = await app.inject({
      method: 'POST',
      url: `/v1/rides/${jointRideId}/close`,
      headers: { authorization: `Bearer ${hostMemberToken}` },
      payload: { distanceKm: 45, durationMinutes: 60 }
    });
    expect(memberCloseTry.statusCode).toBe(403);
    expect(memberCloseTry.json().error).toBe('LEADER_REQUIRED_TO_CLOSE_RIDE');

    // 3. El líder anfitrión cierra la rodada conjunta con 48 km y 75 min:
    // - hostLeader (95%), hostMember (88%), cohostLeader (90%), outsiderGuest (82%) tienen contacto y >=70% presencia -> cuentan para el rango
    // - cohostMember solo tuvo 55% de presencia (<70%) -> NO cuenta para el rango (PRESENCE_BELOW_70_PCT)
    const closeRes = await app.inject({
      method: 'POST',
      url: `/v1/rides/${jointRideId}/close`,
      headers: { authorization: `Bearer ${hostLeaderToken}` },
      payload: {
        distanceKm: 48.5,
        durationMinutes: 75,
        elevationGainM: 620,
        startedAtNight: false,
        rained: false,
        offlineMinutes: 20,
        closedAtIso: '2026-10-24T14:00:00.000Z',
        participants: [
          { userId: hostLeaderId, hasMeshContact: true, presencePct: 95 },
          { userId: hostMemberId, hasMeshContact: true, presencePct: 88 },
          { userId: cohostLeaderId, hasMeshContact: true, presencePct: 90 },
          { userId: cohostMemberId, hasMeshContact: true, presencePct: 55 },
          { userId: outsiderGuestId, hasMeshContact: true, presencePct: 82 }
        ]
      }
    });
    expect(closeRes.statusCode).toBe(200);
    const closeBody = closeRes.json();
    expect(closeBody.state).toBe('validated');
    expect(closeBody.rideMeetsR66).toBe(true);
    expect(closeBody.countedUsers).toContain(hostLeaderId);
    expect(closeBody.countedUsers).toContain(hostMemberId);
    expect(closeBody.countedUsers).toContain(cohostLeaderId);
    expect(closeBody.countedUsers).toContain(outsiderGuestId);
    expect(closeBody.countedUsers).not.toContain(cohostMemberId);
    expect(
      closeBody.skippedUsers.find((s: { userId: string }) => s.userId === cohostMemberId)?.reason
    ).toBe('PRESENCE_BELOW_70_PCT');

    // Intento directo en SQL de forzar counted_for_rank = true a cohostMemberId (presencia 55%) -> rechazado por trigger R6.6
    await expect(
      db.query(
        `UPDATE ride_participants SET counted_for_rank = true WHERE ride_id = $1 AND user_id = $2`,
        [jointRideId, cohostMemberId]
      )
    ).rejects.toThrow(/R6.6: El participante .* requiere presencia >= 70%/);

    // 4. D44 fin a fin: como outsiderGuestId ya participó como invitado aceptado en jointRideId,
    // el líder anfitrión ahora SÍ puede otorgarle una insignia de honor cruzada en PostgreSQL 16
    const honorBadgeRes = await db.query(
      `INSERT INTO badges (id, family, code, issuer_group, issuer_user, receiver, ride_id)
       VALUES ($1, 'honor', 'hermandad_ruta', $2, $3, $4, $5)
       RETURNING id, family, code`,
      [randomUUID(), hostGroupId, hostLeaderId, outsiderGuestId, jointRideId]
    );
    expect(honorBadgeRes.rows[0].code).toBe('hermandad_ruta');

    // 5. Tope diario R6.6: máximo 3 rodadas válidas por día para el mismo usuario
    // hostLeaderId y hostMemberId ya tienen 1 rodada válida hoy (2026-10-24).
    // Completan la 2.ª y 3.ª rodada válida del día -> suben a 3.
    // Al cerrar una 4.ª rodada el mismo día (2026-10-24), el trigger R6.6 rechaza contar la 4.ª rodada.
    for (let idx = 2; idx <= 4; idx++) {
      const rCreate = await app.inject({
        method: 'POST',
        url: '/v1/rides',
        headers: { authorization: `Bearer ${hostLeaderToken}` },
        payload: {
          hostGroupId,
          title: `Rodada Diaria #${idx}`,
          participants: [{ userId: hostMemberId, groupId: hostGroupId, statusKind: 'full' }]
        }
      });
      const rId = rCreate.json().id;

      const rClose = await app.inject({
        method: 'POST',
        url: `/v1/rides/${rId}/close`,
        headers: { authorization: `Bearer ${hostLeaderToken}` },
        payload: {
          distanceKm: 25,
          durationMinutes: 45,
          closedAtIso: `2026-10-24T1${idx + 3}:00:00.000Z`,
          participants: [
            { userId: hostLeaderId, hasMeshContact: true, presencePct: 92 },
            { userId: hostMemberId, hasMeshContact: true, presencePct: 90 }
          ]
        }
      });
      expect(rClose.statusCode).toBe(200);
      if (idx <= 3) {
        expect(rClose.json().state).toBe('validated');
        expect(rClose.json().countedUsers.length).toBe(2);
      } else {
        // La 4.ª rodada del mismo día NO suma para el rango (tope R6.6 de 3/día)
        expect(rClose.json().countedUsers.length).toBe(0);
        expect(rClose.json().skippedUsers[0].reason).toMatch(
          /R6.6: El usuario .* ya alcanzó el máximo de 3 rodadas válidas en el día/
        );
      }
    }

    const progressRes = await db.query(
      `SELECT valid_rides, total_km FROM rank_progress WHERE user_id = $1`,
      [hostLeaderId]
    );
    expect(progressRes.rows[0].valid_rides).toBe(3);
  });
});
