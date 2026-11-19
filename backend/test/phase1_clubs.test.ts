import { beforeAll, afterAll, describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import { checkGroupNameCollision, normalizeGroupName, toLeetSkeleton } from '../src/lib/normalizer.js';
import { computePhashHammingDistance, evaluateLogoUniqueness } from '../src/lib/phash.js';
import { computePaymentSemaphore } from '../src/lib/roles.js';
import type { FastifyInstance } from 'fastify';

function resolveTestDatabaseUrl(): string | undefined {
  if (process.env.PG_TEST_URL || process.env.DATABASE_URL) {
    return process.env.PG_TEST_URL ?? process.env.DATABASE_URL;
  }
  for (const filename of ['.env', '.env.example']) {
    const filePath = join(process.cwd(), filename);
    if (existsSync(filePath)) {
      const content = readFileSync(filePath, 'utf8');
      const pgTestMatch = content.match(/^PG_TEST_URL=(.+)$/m);
      if (pgTestMatch?.[1]) {
        return pgTestMatch[1].trim();
      }
      const dbUrlMatch = content.match(/^DATABASE_URL=(.+)$/m);
      if (dbUrlMatch?.[1]) {
        return dbUrlMatch[1].trim();
      }
    }
  }
  return undefined;
}

async function createTestDb(): Promise<{ db: DbExecutor; usingRealPostgres: boolean }> {
  const connectionString = resolveTestDatabaseUrl();
  const pool = createPgPool(connectionString);
  await pool.query('SELECT 1');
  const sql = readInitMigrationSql();
  await pool.query(sql);
  await pool.query(`
    TRUNCATE TABLE
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
  console.info('[createTestDb] Conectado a PostgreSQL 16 REAL (usingRealPostgres = true)');
  return { db: pool, usingRealPostgres: true };
}

describe('Fase 1 — Clubes, Cuentas, Grupos, Líderes, Cargos, Invitaciones y Reglas (RULES.md)', () => {
  let app: FastifyInstance;
  let db: DbExecutor;

  let founderLeaderId = '';
  let secondLeaderId = '';
  let thirdLeaderId = '';
  let fourthLeaderId = '';
  let fullMemberId = '';
  let guestMemberId = '';
  let mainGroupId = '';
  let secondGroupId = '';

  beforeAll(async () => {
    const setup = await createTestDb();
    db = setup.db;
    app = await buildApp({ db, logger: false });
  });

  afterAll(async () => {
    await app.close();
    await db.end();
  });

  it('R1.1, R1.2, R1.3, R13.6: rechaza registro sin aceptar términos, registra líderes y miembros y actualiza perfil con avatar 64x64 WebP', async () => {
    const noTerms = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        email: 'founder@rbs.mx',
        displayName: 'Comandante Águila',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: false
      }
    });
    expect(noTerms.statusCode).toBe(400);
    expect(noTerms.json().error).toBe('TERMS_REQUIRED');

    const registerLeader = async (email: string, name: string) => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/auth/login',
        payload: {
          email,
          displayName: name,
          countryCode: 'MX',
          acceptedTermsAndConvivencia: true,
          activateLeaderDemo: true
        }
      });
      expect(res.statusCode).toBe(200);
      expect(res.json().user.accountType).toBe('leader');
      return res.json().user.id as string;
    };

    founderLeaderId = await registerLeader('founder@rbs.mx', 'Comandante Águila');
    secondLeaderId = await registerLeader('leader2@rbs.mx', 'Sombra');
    thirdLeaderId = await registerLeader('leader3@rbs.mx', 'Trueno');
    fourthLeaderId = await registerLeader('leader4@rbs.mx', 'Centauro');

    const regFull = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        email: 'rider1@rbs.mx',
        displayName: 'Coyote',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true
      }
    });
    expect(regFull.statusCode).toBe(200);
    expect(regFull.json().user.accountType).toBe('member');
    fullMemberId = regFull.json().user.id;

    const regGuest = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        email: 'guest1@rbs.mx',
        displayName: 'Nómada',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true
      }
    });
    guestMemberId = regGuest.json().user.id;

    // Actualizar perfil con apodo, moto, color en el mapa y avatar 64x64 WebP (R1.3)
    const fakeWebp = Buffer.from('RIFF_WEBP_64x64_DEMO').toString('base64');
    const patchRes = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me',
      headers: { 'x-user-id': founderLeaderId },
      payload: {
        displayName: 'Águila Mayor',
        bikeModel: 'BMW R1250GS Adventure',
        mapColor: '#FF6D00',
        avatarWebpBase64: fakeWebp
      }
    });
    expect(patchRes.statusCode).toBe(200);

    const meRes = await app.inject({
      method: 'GET',
      url: '/v1/users/me',
      headers: { 'x-user-id': founderLeaderId }
    });
    expect(meRes.statusCode).toBe(200);
    expect(meRes.json().displayName).toBe('Águila Mayor');
    expect(meRes.json().bikeModel).toBe('BMW R1250GS Adventure');
    expect(meRes.json().mapColor).toBe('#FF6D00');
    expect(meRes.json().avatarWebpBase64).toBe(fakeWebp);
  });

  it('R5.1, R5.2, R8.2: crea grupo validando nombre normalizado (leet) y logo (distancia Hamming pHash) únicos por país, y otorga marca Fundador', async () => {
    // Un miembro sin plan líder no puede crear grupo (R1.2)
    const memberTryCreate = await app.inject({
      method: 'POST',
      url: '/v1/groups',
      headers: { 'x-user-id': fullMemberId },
      payload: {
        countryCode: 'MX',
        name: 'Lobos del Sur',
        logoPhashHex: 'a1b2c3d4e5f60718'
      }
    });
    expect(memberTryCreate.statusCode).toBe(403);

    // El líder fundador crea "Lobos del Sur" en MX
    const createRes = await app.inject({
      method: 'POST',
      url: '/v1/groups',
      headers: { 'x-user-id': founderLeaderId },
      payload: {
        countryCode: 'MX',
        name: '¡Lóbos del Sur!',
        logoPhashHex: 'a1b2c3d4e5f60718'
      }
    });
    expect(createRes.statusCode).toBe(201);
    mainGroupId = createRes.json().id;
    expect(createRes.json().nameNormalized).toBe('lobosdelsur');

    // Verificar marca permanente "founder" (R8.2)
    const founderProfile = await app.inject({
      method: 'GET',
      url: '/v1/users/me',
      headers: { 'x-user-id': founderLeaderId }
    });
    expect(
      founderProfile.json().badges.some((b: { code: string }) => b.code === 'founder')
    ).toBe(true);

    // Intento de clonar el nombre con variaciones leet ("10B05 D3L $UR") en el mismo país MX -> 409
    const leetClone = await app.inject({
      method: 'POST',
      url: '/v1/groups',
      headers: { 'x-user-id': secondLeaderId },
      payload: {
        countryCode: 'MX',
        name: '10B05 D3L $UR',
        logoPhashHex: 'ffffffff00000000'
      }
    });
    expect(leetClone.statusCode).toBe(409);
    expect(leetClone.json().error).toBe('GROUP_NAME_TAKEN_IN_COUNTRY');

    // Intento de usar otro nombre pero el mismo logo con 1 bit de cambio (Hamming = 1 <= 6) en MX -> 409
    const logoClone = await app.inject({
      method: 'POST',
      url: '/v1/groups',
      headers: { 'x-user-id': secondLeaderId },
      payload: {
        countryCode: 'MX',
        name: 'Halcones del Norte',
        logoPhashHex: 'a1b2c3d4e5f60719' // solo cambia el último bit (8 -> 9)
      }
    });
    expect(logoClone.statusCode).toBe(409);
    expect(logoClone.json().error).toBe('GROUP_LOGO_TAKEN_IN_COUNTRY');

    // Mismo nombre y logo en OTRO país (CO) sí se permite (R5.1, R5.2) con su propio líder (D42: 1 líder = 1 grupo)
    const coLeaderReg = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        email: 'leader-co@rbs.co',
        displayName: 'Líder Colombia',
        countryCode: 'CO',
        acceptedTermsAndConvivencia: true,
        activateLeaderDemo: true
      }
    });
    const coLeaderId = coLeaderReg.json().user.id as string;

    const otherCountry = await app.inject({
      method: 'POST',
      url: '/v1/groups',
      headers: { 'x-user-id': coLeaderId },
      payload: {
        countryCode: 'CO',
        name: 'Lobos del Sur',
        logoPhashHex: 'a1b2c3d4e5f60718'
      }
    });
    expect(otherCountry.statusCode).toBe(201);

    // Crear un segundo grupo distinto en MX para probar reglas multi-grupo (R2)
    const secondGrp = await app.inject({
      method: 'POST',
      url: '/v1/groups',
      headers: { 'x-user-id': fourthLeaderId },
      payload: {
        countryCode: 'MX',
        name: 'Riders de Acero',
        logoPhashHex: '00ff00ff00ff00ff'
      }
    });
    expect(secondGrp.statusCode).toBe(201);
    secondGroupId = secondGrp.json().id;
  });

  it('R4.1, R4.3: solo el líder principal nombra otros líderes y el grupo admite máximo 3 líderes', async () => {
    // Añadir líder #2
    const add2 = await app.inject({
      method: 'POST',
      url: `/v1/groups/${mainGroupId}/leaders`,
      headers: { 'x-user-id': founderLeaderId },
      payload: { userId: secondLeaderId }
    });
    expect(add2.statusCode).toBe(201);

    // El líder #2 (no principal) intenta nombrar al líder #3 -> 403 (R4.3)
    const nonPrincipalTry = await app.inject({
      method: 'POST',
      url: `/v1/groups/${mainGroupId}/leaders`,
      headers: { 'x-user-id': secondLeaderId },
      payload: { userId: thirdLeaderId }
    });
    expect(nonPrincipalTry.statusCode).toBe(403);
    expect(nonPrincipalTry.json().error).toBe('ONLY_PRINCIPAL_LEADER');

    // El líder principal añade al líder #3 -> 201 (total = 3 líderes)
    const add3 = await app.inject({
      method: 'POST',
      url: `/v1/groups/${mainGroupId}/leaders`,
      headers: { 'x-user-id': founderLeaderId },
      payload: { userId: thirdLeaderId }
    });
    expect(add3.statusCode).toBe(201);

    // Intentar añadir un 4º líder -> 409 MAX_LEADERS_REACHED (R4.1)
    const add4 = await app.inject({
      method: 'POST',
      url: `/v1/groups/${mainGroupId}/leaders`,
      headers: { 'x-user-id': founderLeaderId },
      payload: { userId: fourthLeaderId }
    });
    expect(add4.statusCode).toBe(409);
    expect(add4.json().error).toBe('MAX_LEADERS_REACHED');
  });

  it('R2.1, R2.2, R15.1: invitaciones exigen consentimiento explícito; Full member solo en 1 grupo e Invitado en varios', async () => {
    // Invitar a fullMemberId como 'full' al grupo principal
    const inv1 = await app.inject({
      method: 'POST',
      url: `/v1/groups/${mainGroupId}/invitations`,
      headers: { 'x-user-id': founderLeaderId },
      payload: { invitedUserId: fullMemberId, statusKind: 'full' }
    });
    expect(inv1.statusCode).toBe(201);
    expect(inv1.json().monthlyCostUsd).toBe(2.5);
    const inv1Id = inv1.json().id;

    // Intentar aceptar sin marcar consentimiento de pertenencia/cuota -> 400
    const noConsentAccept = await app.inject({
      method: 'POST',
      url: `/v1/invitations/${inv1Id}/accept`,
      headers: { 'x-user-id': fullMemberId },
      payload: {
        consents: {
          membership: false,
          location: true,
          emergencyPackage: true,
          notifications: true
        }
      }
    });
    expect(noConsentAccept.statusCode).toBe(400);

    // Aceptar con consentimientos separados por grupo (R15.1)
    const accept1 = await app.inject({
      method: 'POST',
      url: `/v1/invitations/${inv1Id}/accept`,
      headers: { 'x-user-id': fullMemberId },
      payload: {
        consents: {
          membership: true,
          location: true,
          emergencyPackage: true,
          notifications: false
        }
      }
    });
    expect(accept1.statusCode).toBe(200);

    // R2: Como ya es Full member en mainGroupId, no puede ser invitado como Full ni Prospecto en secondGroupId
    const illegalSecondPrimary = await app.inject({
      method: 'POST',
      url: `/v1/groups/${secondGroupId}/invitations`,
      headers: { 'x-user-id': fourthLeaderId },
      payload: { invitedUserId: fullMemberId, statusKind: 'prospect' }
    });
    expect(illegalSecondPrimary.statusCode).toBe(409);
    expect(['ALREADY_IN_PRIMARY_GROUP', 'ERR_DUAL_MEMBERSHIP_PROHIBITED']).toContain(illegalSecondPrimary.json().error);

    // En cambio, guestMemberId puede entrar como 'guest' tanto a mainGroupId como a secondGroupId (R2.2)
    for (const [gid, leader] of [
      [mainGroupId, founderLeaderId],
      [secondGroupId, fourthLeaderId]
    ] as const) {
      const invGuest = await app.inject({
        method: 'POST',
        url: `/v1/groups/${gid}/invitations`,
        headers: { 'x-user-id': leader },
        payload: { invitedUserId: guestMemberId, statusKind: 'guest' }
      });
      expect(invGuest.statusCode).toBe(201);

      const accGuest = await app.inject({
        method: 'POST',
        url: `/v1/invitations/${invGuest.json().id}/accept`,
        headers: { 'x-user-id': guestMemberId },
        payload: {
          consents: {
            membership: true,
            location: true,
            emergencyPackage: false,
            notifications: true
          }
        }
      });
      expect(accGuest.statusCode).toBe(200);
    }

    // Verificar que los consentimientos se guardaron separados por grupo (R15.1)
    const consentsRes = await app.inject({
      method: 'GET',
      url: '/v1/users/me/consents',
      headers: { 'x-user-id': guestMemberId }
    });
    expect(consentsRes.statusCode).toBe(200);
    // 1 'terms' global + 4 por mainGroupId + 4 por secondGroupId = 9 registros
    expect(consentsRes.json().consents.length).toBe(9);
  });

  it('R3.1, R3.2, R3.3, R7.2: solo líderes asignan cargos y el Tesorero ve el semáforo de pagos en solo lectura', async () => {
    // Un miembro normal no puede cambiarse el cargo a sí mismo -> 403
    const selfPromote = await app.inject({
      method: 'PATCH',
      url: `/v1/groups/${mainGroupId}/members/${fullMemberId}`,
      headers: { 'x-user-id': fullMemberId },
      payload: { clubRole: 'presidente' }
    });
    expect(selfPromote.statusCode).toBe(403);

    // Un líder asigna el cargo 'tesorero' a fullMemberId
    const assignTreasurer = await app.inject({
      method: 'PATCH',
      url: `/v1/groups/${mainGroupId}/members/${fullMemberId}`,
      headers: { 'x-user-id': secondLeaderId },
      payload: { clubRole: 'tesorero' }
    });
    expect(assignTreasurer.statusCode).toBe(200);
    expect(assignTreasurer.json().clubRole).toBe('tesorero');

    // El Tesorero consulta /v1/groups/:id/members y ve el semáforo ('green') sin datos de pago (R3.3, R7.2)
    const membersAsTreasurer = await app.inject({
      method: 'GET',
      url: `/v1/groups/${mainGroupId}/members`,
      headers: { 'x-user-id': fullMemberId }
    });
    expect(membersAsTreasurer.statusCode).toBe(200);
    expect(membersAsTreasurer.json().members[0].paymentSemaphore).toBe('green');
  });

  it('R5.3: transferencia de grupo solo a miembro del grupo con cuenta líder activa, espera de 7 días y cancelación', async () => {
    // No se puede transferir a un miembro que no tiene cuenta de líder activa (fullMemberId es 'member')
    const failNotLeader = await app.inject({
      method: 'POST',
      url: `/v1/groups/${mainGroupId}/transfer`,
      headers: { 'x-user-id': founderLeaderId },
      payload: { toUserId: fullMemberId }
    });
    expect(failNotLeader.statusCode).toBe(400);
    expect(failNotLeader.json().error).toBe('RECIPIENT_NOT_ACTIVE_LEADER');

    // No se puede transferir a un líder que no pertenece al grupo (fourthLeaderId)
    const failNotInGroup = await app.inject({
      method: 'POST',
      url: `/v1/groups/${mainGroupId}/transfer`,
      headers: { 'x-user-id': founderLeaderId },
      payload: { toUserId: fourthLeaderId }
    });
    expect(failNotInGroup.statusCode).toBe(400);
    expect(failNotInGroup.json().error).toBe('RECIPIENT_NOT_IN_GROUP');

    // Sí se puede iniciar transferencia hacia secondLeaderId (miembro/líder del grupo con cuenta líder activa)
    const startTransfer = await app.inject({
      method: 'POST',
      url: `/v1/groups/${mainGroupId}/transfer`,
      headers: { 'x-user-id': founderLeaderId },
      payload: { toUserId: secondLeaderId }
    });
    expect(startTransfer.statusCode).toBe(201);
    const transferId = startTransfer.json().id;
    const executesAtMs = new Date(startTransfer.json().executesAt).getTime();
    // Espera de ~7 días
    expect(executesAtMs - Date.now()).toBeGreaterThan(6.9 * 24 * 60 * 60 * 1000);

    // Cancelar transferencia dentro de los 7 días (R5.3)
    const cancelRes = await app.inject({
      method: 'POST',
      url: `/v1/transfers/${transferId}/cancel`,
      headers: { 'x-user-id': founderLeaderId }
    });
    expect(cancelRes.statusCode).toBe(200);
    expect(cancelRes.json().state).toBe('canceled');
  });

  it('R14.2, R14.4: decisiones sensibles requieren mayoría de líderes (2 de 3) y un líder no vota su propia sanción', async () => {
    // Un líder no puede votar su propia sanción (R14.4)
    const selfVote = await app.inject({
      method: 'POST',
      url: `/v1/groups/${mainGroupId}/votes`,
      headers: { 'x-user-id': thirdLeaderId },
      payload: { proposal: 'remove_leader', targetId: thirdLeaderId }
    });
    expect(selfVote.statusCode).toBe(400);

    // Propuesta para expulsar a guestMemberId (con 3 líderes, 1 voto la deja 'open', el 2º voto la aprueba)
    const propRes = await app.inject({
      method: 'POST',
      url: `/v1/groups/${mainGroupId}/votes`,
      headers: { 'x-user-id': founderLeaderId },
      payload: { proposal: 'remove_member', targetId: guestMemberId }
    });
    expect(propRes.statusCode).toBe(201);
    expect(propRes.json().state).toBe('open');
    const voteId = propRes.json().id;

    const ballot2 = await app.inject({
      method: 'POST',
      url: `/v1/votes/${voteId}/ballot`,
      headers: { 'x-user-id': secondLeaderId },
      payload: { approve: true }
    });
    expect(ballot2.statusCode).toBe(200);
    expect(ballot2.json().state).toBe('approved');
  });

  it('SQL Trigger trg_enforce_max_three_leaders e índices únicos parciales: PostgreSQL rechaza directamente el 4.º líder en SQL (R4.1)', async () => {
    // 1. Intento directo en SQL (saltándose Fastify/TypeScript) de insertar un 4.º líder en mainGroupId
    await expect(
      db.query(
        `INSERT INTO group_leaders (group_id, user_id, is_principal)
         VALUES ($1, $2, false)`,
        [mainGroupId, fourthLeaderId]
      )
    ).rejects.toThrow(/Un grupo no puede tener más de 3 líderes/i);

    // 2. Intento directo en SQL de insertar un segundo líder principal en secondGroupId (idx_group_leaders_one_principal)
    await expect(
      db.query(
        `INSERT INTO group_leaders (group_id, user_id, is_principal)
         VALUES ($1, $2, true)`,
        [secondGroupId, founderLeaderId]
      )
    ).rejects.toThrow();
  });

  it('Vectores JSON compartidos (docs/test-vectors/phase1_rules_vectors.json): paridad exacta entre TypeScript y Kotlin', () => {
    const vectorsPath = join(process.cwd(), '..', 'docs', 'test-vectors', 'phase1_rules_vectors.json');
    const vectors = JSON.parse(readFileSync(vectorsPath, 'utf8'));

    for (const nc of vectors.normalizationCases) {
      expect(normalizeGroupName(nc.input)).toBe(nc.expectedNormalized);
      expect(toLeetSkeleton(nc.input)).toBe(nc.expectedLeetSkeleton);
    }

    for (const cc of vectors.collisionCases) {
      const res = checkGroupNameCollision(cc.candidate, cc.existing);
      expect(res.collides).toBe(cc.expectedCollides);
      expect(res.reason).toBe(cc.expectedReason);
    }

    for (const pc of vectors.phashCases) {
      const cand = Buffer.from(pc.candidateHex, 'hex');
      const exist = Buffer.from(pc.existingHex, 'hex');
      expect(computePhashHammingDistance(cand, exist)).toBe(pc.expectedDistance);
      const evalRes = evaluateLogoUniqueness(cand, [{ id: 'g1', logoPhash: exist }]);
      expect(evalRes.rejected).toBe(pc.expectedRejected);
      expect(evalRes.needsManualReview).toBe(pc.expectedManualReview);
    }

    for (const sc of vectors.semaphoreCases) {
      const sem = computePaymentSemaphore(
        sc.paidUntilEpochMs ? new Date(sc.paidUntilEpochMs) : null,
        sc.graceUntilEpochMs ? new Date(sc.graceUntilEpochMs) : null,
        new Date(sc.nowEpochMs)
      );
      expect(sem).toBe(sc.expectedSemaphore);
    }
  });

  it(
    'D35/D40 Auth (scrypt N=2^17, accessToken 30m, refreshToken rotativo/revocable, bloqueo por IP) y DELETE /v1/groups/:id/leaders/:leaderUid (R14.2, R14.3)',
    { timeout: 30000 },
    async () => {
      // 1. Registro con contraseña hasheada con scrypt N=2^17 y obtención de accessToken (30m) + refreshToken hasheado en BD
      const reg = await app.inject({
        method: 'POST',
        url: '/v1/auth/register',
        payload: {
          email: 'secure-biker@rbs.mx',
          password: 'MotoPassword2026!',
          displayName: 'Biker Seguro',
          countryCode: 'MX',
          acceptedTermsAndConvivencia: true
        }
      });
      expect(reg.statusCode).toBe(200);
      const { accessToken, refreshToken, authPolicy } = reg.json();
      expect(authPolicy.hashAlgorithm).toBe('scrypt-N131072-r8-p1-k64');
      expect(authPolicy.accessTokenTtlSeconds).toBe(1800);

      // La ACL de GET /v1/groups/:id exige pertenencia: este usuario se añade
      // como miembro aceptado para que el token rotado siga siendo el sujeto probado.
      await db.query(
        `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state, accepted_at)
         VALUES ($1, $2, $3, 'full', 'accepted', now())
         ON CONFLICT (group_id, user_id) DO NOTHING`,
        [randomUUID(), mainGroupId, reg.json().user.id as string]
      );

      // 2. Rotación de refresh token en /v1/auth/refresh y bloqueo de reuso del refresh token anterior
      const rot = await app.inject({
        method: 'POST',
        url: '/v1/auth/refresh',
        payload: { refreshToken }
      });
      expect(rot.statusCode).toBe(200);
      const { accessToken: rotatedAccessToken, refreshToken: rotatedRefreshToken } = rot.json();
      expect(rotatedRefreshToken).not.toBe(refreshToken);

      // Consultar GET /v1/groups/:id con el nuevo accessToken rotado
      const grpDetail = await app.inject({
        method: 'GET',
        url: `/v1/groups/${mainGroupId}`,
        headers: { authorization: `Bearer ${rotatedAccessToken}` }
      });
      expect(grpDetail.statusCode).toBe(200);
      expect(grpDetail.json().leaders.length).toBe(3);

      // Cerrar sesión revocando explícitamente el refresh token activo
      const logoutRes = await app.inject({
        method: 'POST',
        url: '/v1/auth/logout',
        payload: { refreshToken: rotatedRefreshToken }
      });
      expect(logoutRes.statusCode).toBe(200);
      expect(logoutRes.json().revoked).toBe(true);

      // Intentar refrescar con el token revocado devuelve 401 REFRESH_TOKEN_REUSED
      const replayAfterLogout = await app.inject({
        method: 'POST',
        url: '/v1/auth/refresh',
        payload: { refreshToken: rotatedRefreshToken }
      });
      expect(replayAfterLogout.statusCode).toBe(401);
      expect(replayAfterLogout.json().error).toBe('REFRESH_TOKEN_REUSED');

      // 3. 5 intentos fallidos desde una IP atacante bloquean esa IP (429), pero NO bloquean al usuario legítimo desde otra IP
      for (let i = 0; i < 5; i++) {
        const bad = await app.inject({
          method: 'POST',
          url: '/v1/auth/login',
          remoteAddress: '203.0.113.99',
          payload: {
            email: 'secure-biker@rbs.mx',
            password: 'WrongPassword000',
            countryCode: 'MX',
            acceptedTermsAndConvivencia: true
          }
        });
        expect(bad.statusCode).toBe(401);
      }
      const lockedAttacker = await app.inject({
        method: 'POST',
        url: '/v1/auth/login',
        remoteAddress: '203.0.113.99',
        payload: {
          email: 'secure-biker@rbs.mx',
          password: 'MotoPassword2026!',
          countryCode: 'MX',
          acceptedTermsAndConvivencia: true
        }
      });
      expect(lockedAttacker.statusCode).toBe(429);
      expect(lockedAttacker.json().error).toBe('TOO_MANY_AUTH_ATTEMPTS');

      // El usuario legítimo desde su propia IP (198.51.100.10) inicia sesión sin ser bloqueado por el atacante
      const legitLogin = await app.inject({
        method: 'POST',
        url: '/v1/auth/login',
        remoteAddress: '198.51.100.10',
        payload: {
          email: 'secure-biker@rbs.mx',
          password: 'MotoPassword2026!',
          countryCode: 'MX',
          acceptedTermsAndConvivencia: true
        }
      });
      expect(legitLogin.statusCode).toBe(200);

      // 4. DELETE /v1/groups/:id/leaders/:leaderUid prohíbe remover al líder principal (R14.3)
      const delPrincipal = await app.inject({
        method: 'DELETE',
        url: `/v1/groups/${mainGroupId}/leaders/${founderLeaderId}`,
        headers: { 'x-user-id': founderLeaderId }
      });
      expect(delPrincipal.statusCode).toBe(403);
      expect(delPrincipal.json().error).toBe('CANNOT_REMOVE_PRINCIPAL_LEADER');

      // 5. Renuncia voluntaria de un co-líder (thirdLeaderId) vía DELETE /v1/groups/:id/leaders/:leaderUid
      const delCoLeader = await app.inject({
        method: 'DELETE',
        url: `/v1/groups/${mainGroupId}/leaders/${thirdLeaderId}`,
        headers: { 'x-user-id': thirdLeaderId }
      });
      expect(delCoLeader.statusCode).toBe(200);

      // 6. Separación estricta access vs refresh: un refreshToken NO sirve como Bearer en endpoints y un accessToken NO sirve en /v1/auth/refresh
      const refreshAsBearer = await app.inject({
        method: 'GET',
        url: '/v1/users/me',
        headers: { authorization: `Bearer ${legitLogin.json().refreshToken}` }
      });
      expect(refreshAsBearer.statusCode).toBe(401);

      const accessAsRefresh = await app.inject({
        method: 'POST',
        url: '/v1/auth/refresh',
        payload: { refreshToken: legitLogin.json().accessToken }
      });
      expect(accessAsRefresh.statusCode).toBe(401);

      // 7. Rotación de claves de firma (kid + SessionKeyring), algoritmo fijo HS256 y exp obligatorio
      const {
        AUTH_POLICY: policy,
        ScryptConcurrencyLimiter,
        issueSessionToken,
        verifySessionToken
      } = await import('../src/lib/auth.js');
      expect(policy.TOKEN_ALGORITHM).toBe('HS256');
      expect(policy.SCRYPT_MAXMEM).toBe(256 * 1024 * 1024);
      expect(policy.MAX_FAILED_ATTEMPTS_PER_IP).toBe(100);

      const keyringV1 = {
        activeKid: 'k1',
        keys: { k1: 'secret-key-v1-32-bytes-minimum!!' }
      };
      const keyringRotatedV2 = {
        activeKid: 'k2',
        keys: {
          k1: 'secret-key-v1-32-bytes-minimum!!',
          k2: 'secret-key-v2-rotated-32-bytes!!'
        }
      };
      const keyringRetiredV1 = {
        activeKid: 'k2',
        keys: { k2: 'secret-key-v2-rotated-32-bytes!!' }
      };

      const tokenK1 = issueSessionToken(founderLeaderId, 'access', 1_700_000_000, keyringV1);
      expect(tokenK1.kid).toBe('k1');
      // Durante ventana de rotación (k1 + k2 vigentes), el token emitido con k1 sigue siendo válido
      expect(verifySessionToken(tokenK1.token, 1_700_000_100, 'access', keyringRotatedV2)?.sub).toBe(
        founderLeaderId
      );
      // Una vez retirada k1 del llavero, el token k1 se rechaza inmediatamente
      expect(verifySessionToken(tokenK1.token, 1_700_000_100, 'access', keyringRetiredV1)).toBeNull();
      // Si exp <= nowSec, se rechaza
      expect(verifySessionToken(tokenK1.token, 1_700_005_000, 'access', keyringRotatedV2)).toBeNull();

      // 8. Semáforo de concurrencia de scrypt (128 MiB por hash)
      const limiter = new ScryptConcurrencyLimiter(2);
      let peakActive = 0;
      await Promise.all(
        Array.from({ length: 6 }, (_, idx) =>
          limiter.run(async () => {
            peakActive = Math.max(peakActive, limiter.activeCount);
            await new Promise((r) => setTimeout(r, 15));
            return idx;
          })
        )
      );
      expect(peakActive).toBeLessThanOrEqual(2);

      // 9. Alias /v1/users/me/profile eliminado (devuelve 404; solo existe PATCH /v1/users/me)
      const removedAlias = await app.inject({
        method: 'PATCH',
        url: '/v1/users/me/profile',
        headers: { 'x-user-id': founderLeaderId },
        payload: { displayName: 'No Existe Alias' }
      });
      expect(removedAlias.statusCode).toBe(404);
    }
  );

  it('Hito 1 — R2.2 y R2.3: Servidor replica exactamente Caso 3 de Android (prohibición de doble hermandad y admisión de rodada libre)', async () => {
    // 1. Crear piloto de prueba con membresía activa en mainGroupId
    const pilotId = randomUUID();
    await db.query(
      `INSERT INTO users (id, email, display_name, country_code, account_type)
       VALUES ($1, $2, 'Piloto Águila', 'VE', 'member')`,
      [pilotId, `pilot-r22-${Date.now()}@example.com`]
    );

    // Asignar al piloto a mainGroupId como FULL member
    await db.query(
      `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state, accepted_at, paid_until)
       VALUES ($1, $2, $3, 'full', 'accepted', now(), now() + interval '30 days')`,
      [randomUUID(), mainGroupId, pilotId]
    );

    // 2. Club 2 emite invitación directa de HERMANDAD para este piloto
    const inviteHermandadId = randomUUID();
    await db.query(
      `INSERT INTO invitations (id, group_id, invited_user, invited_by, status_kind, invitation_type, state, expires_at)
       VALUES ($1, $2, $3, $4, 'full', 'hermandad', 'pending', now() + interval '24 hours')`,
      [inviteHermandadId, secondGroupId, pilotId, fourthLeaderId]
    );

    // 3. Piloto intenta aceptar la invitación a Hermandad del segundo club
    const acceptHermandad = await app.inject({
      method: 'POST',
      url: `/v1/invitations/${inviteHermandadId}/accept`,
      headers: { 'x-user-id': pilotId },
      payload: {
        consents: {
          membership: true,
          location: true,
          emergencyPackage: true,
          notifications: true
        }
      }
    });

    // Debe rechazar con 409 ERR_DUAL_MEMBERSHIP_PROHIBITED (idéntico a R2.2 en Android)
    expect(acceptHermandad.statusCode).toBe(409);
    expect(acceptHermandad.json().error).toBe('ERR_DUAL_MEMBERSHIP_PROHIBITED');

    // Comprobar que su membresía original en mainGroupId permanece intacta
    const checkOriginalMembership = await db.query(
      `SELECT group_id, status_kind FROM memberships WHERE user_id = $1`,
      [pilotId]
    );
    expect(checkOriginalMembership.rows.length).toBe(1);
    expect(checkOriginalMembership.rows[0].group_id).toBe(mainGroupId);

    // 4. Club 2 emite ahora una invitación a RODADA LIBRE (R2.3)
    const inviteRodadaId = randomUUID();
    await db.query(
      `INSERT INTO invitations (id, group_id, invited_user, invited_by, status_kind, invitation_type, state, expires_at)
       VALUES ($1, $2, $3, $4, 'guest', 'rodada', 'pending', now() + interval '24 hours')`,
      [inviteRodadaId, secondGroupId, pilotId, fourthLeaderId]
    );

    // 5. Piloto acepta la invitación a rodar (sin consentimiento de membresía)
    const acceptRodada = await app.inject({
      method: 'POST',
      url: `/v1/invitations/${inviteRodadaId}/accept`,
      headers: { 'x-user-id': pilotId },
      payload: {
        consents: {
          location: true
        }
      }
    });

    expect(acceptRodada.statusCode).toBe(200);
    expect(acceptRodada.json().invitationType).toBe('rodada');

    // Confirmar que NO se añadió ninguna membresía adicional en memberships
    const checkStillSingleMembership = await db.query(
      `SELECT group_id FROM memberships WHERE user_id = $1`,
      [pilotId]
    );
    expect(checkStillSingleMembership.rows.length).toBe(1);
  });

  it('Hito 1 — Expulsión de Miembros (DELETE /v1/groups/:id/members/:memberUid) con protección del Fundador (R14.3)', async () => {
    // 1. Crear miembro común en mainGroupId
    const memberToExpelId = randomUUID();
    await db.query(
      `INSERT INTO users (id, email, display_name, country_code, account_type)
       VALUES ($1, $2, 'Miembro Expulsado', 'VE', 'member')`,
      [memberToExpelId, `expel-${Date.now()}@example.com`]
    );

    await db.query(
      `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state, accepted_at, paid_until)
       VALUES ($1, $2, $3, 'prospect', 'accepted', now(), now() + interval '30 days')`,
      [randomUUID(), mainGroupId, memberToExpelId]
    );

    // 2. Intento de expulsión por un usuario que NO es líder -> 403 LEADER_REQUIRED
    const nonLeaderExpel = await app.inject({
      method: 'DELETE',
      url: `/v1/groups/${mainGroupId}/members/${memberToExpelId}`,
      headers: { 'x-user-id': memberToExpelId }
    });
    expect(nonLeaderExpel.statusCode).toBe(403);
    expect(nonLeaderExpel.json().error).toBe('LEADER_REQUIRED');

    // 3. Intento de expulsar al Fundador / Líder Principal -> 403 CANNOT_EXPEL_FOUNDER
    const expelFounder = await app.inject({
      method: 'DELETE',
      url: `/v1/groups/${mainGroupId}/members/${founderLeaderId}`,
      headers: { 'x-user-id': secondLeaderId }
    });
    expect(expelFounder.statusCode).toBe(403);
    expect(expelFounder.json().error).toBe('CANNOT_EXPEL_FOUNDER');

    // 4. Líder legítimo expulsa al miembro común -> 200 OK
    const legitExpel = await app.inject({
      method: 'DELETE',
      url: `/v1/groups/${mainGroupId}/members/${memberToExpelId}`,
      headers: { 'x-user-id': founderLeaderId }
    });
    expect(legitExpel.statusCode).toBe(200);
    expect(legitExpel.json().success).toBe(true);

    // 5. Verificar que el miembro fue eliminado de memberships en la base de datos
    const checkExpelled = await db.query(
      `SELECT 1 FROM memberships WHERE group_id = $1 AND user_id = $2`,
      [mainGroupId, memberToExpelId]
    );
    expect(checkExpelled.rows.length).toBe(0);
  });
});
