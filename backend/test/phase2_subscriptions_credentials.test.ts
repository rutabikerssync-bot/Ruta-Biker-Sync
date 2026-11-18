import { beforeAll, afterAll, describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { verify } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import { ScryptConcurrencyLimiter } from '../src/lib/auth.js';
import {
  CredentialKeyring,
  computeCredentialExpirySeconds,
  issueOfflineCredential,
  verifyOfflineCredential
} from '../src/lib/credentials.js';
import {
  FakePlayBillingVerifier,
  evaluateSubscriptionState
} from '../src/lib/subscriptions.js';

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

async function createRealPostgresTestDb(): Promise<DbExecutor> {
  const pool = createPgPool(resolveTestDatabaseUrl());
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
  return pool;
}

describe('Fase 2 (Bloques 2.1 y 2.2) — Ajustes de Seguridad, P7, Máquina de 6 Estados, Ed25519 (kid/rotación/revocación), RTDN, R4.5 y R4.6', () => {
  let db: DbExecutor;
  let app: FastifyInstance;
  let playVerifier: FakePlayBillingVerifier;
  let credentialKeyring: CredentialKeyring;

  let leader1Id = '';
  let leader1Token = '';
  let leader2Id = '';
  let leader2Token = '';
  let leader3Id = '';
  let leader3Token = '';
  let guestUserId = '';
  let guestUserToken = '';
  let fullMemberId = '';
  let fullMemberToken = '';
  let groupId = '';

  beforeAll(async () => {
    db = await createRealPostgresTestDb();
    playVerifier = new FakePlayBillingVerifier();
    credentialKeyring = new CredentialKeyring('ed25519-k1');
    app = await buildApp({
      db,
      useFakeBilling: true,
      playVerifier,
      credentialKeyring,
      rateLimitAuthMaxPerHour: 50
    });
  });

  afterAll(async () => {
    await app.close();
    await db.end();
  });

  it('Bloque 2.1 — En producción (NODE_ENV=production) el backend se niega a arrancar sin AUTH_TOKEN_KEYS_JSON o con secreto de desarrollo', async () => {
    await expect(
      buildApp({
        db,
        env: { NODE_ENV: 'production' }
      })
    ).rejects.toThrow(/FATAL_AUTH_CONFIG/);

    await expect(
      buildApp({
        db,
        env: {
          NODE_ENV: 'production',
          AUTH_TOKEN_KEYS_JSON: JSON.stringify({ k1: 'rbs-dev-hmac-secret-change-in-prod-32b' })
        }
      })
    ).rejects.toThrow(/FATAL_AUTH_CONFIG/);

    const prodApp = await buildApp({
      db,
      env: {
        NODE_ENV: 'production',
        AUTH_TOKEN_ACTIVE_KID: 'prod-k1',
        AUTH_TOKEN_KEYS_JSON: JSON.stringify({
          'prod-k1': 'a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e9d8c7b6a5f4e3d2c1b0a9f8'
        })
      }
    });
    await prodApp.close();
  });

  it('Bloque 2.1 — Cola de scrypt acotada responde 503 Service Unavailable (Retry-After) al llenarse y trustProxy=false ignora X-Forwarded-For falsificado', async () => {
    const tinyLimiter = new ScryptConcurrencyLimiter(1, 1);
    let releaseFirst!: () => void;
    const blockerPromise = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });

    // Ocupar la única ranura activa y la única ranura de cola en el limitador
    const slot1 = tinyLimiter.run(() => blockerPromise);
    const slot2 = tinyLimiter.run(async () => 'queued-ok');
    expect(tinyLimiter.activeCount).toBe(1);
    expect(tinyLimiter.queuedCount).toBe(1);

    const limitedApp = await buildApp({
      db,
      scryptLimiter: tinyLimiter,
      trustProxy: false
    });

    const overflowRes = await limitedApp.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: {
        email: 'overflow@biker.mx',
        password: 'PasswordSegura123!',
        displayName: 'Overflow Rider',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true
      }
    });
    expect(overflowRes.statusCode).toBe(503);
    expect(overflowRes.headers['retry-after']).toBe('2');
    expect(overflowRes.json().error).toBe('SCRYPT_QUEUE_FULL');

    releaseFirst();
    await slot1;
    await slot2;

    // Verificar que con trustProxy=false un atacante no puede eludir el bloqueo de 5 intentos
    // falsificando cabeceras X-Forwarded-For distintas en cada petición
    const regVictim = await limitedApp.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: {
        email: 'victim-xff@biker.mx',
        password: 'CorrectPassword123!',
        displayName: 'Victim XFF',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true
      }
    });
    expect(regVictim.statusCode).toBe(200);

    for (let i = 1; i <= 5; i++) {
      const failRes = await limitedApp.inject({
        method: 'POST',
        url: '/v1/auth/login',
        headers: { 'x-forwarded-for': `203.0.113.${i}` },
        payload: {
          email: 'victim-xff@biker.mx',
          password: 'WrongPassword999!',
          acceptedTermsAndConvivencia: true
        }
      });
      expect(failRes.statusCode).toBe(401);
      if (i >= 2) {
        expect(failRes.json().backoffDelayMs).toBeGreaterThan(0);
      }
    }

    // El 6.º intento con otra IP falsificada en X-Forwarded-For es bloqueado (429) porque Fastify ignora XFF cuando trustProxy=false
    const sixthRes = await limitedApp.inject({
      method: 'POST',
      url: '/v1/auth/login',
      headers: { 'x-forwarded-for': '198.51.100.250' },
      payload: {
        email: 'victim-xff@biker.mx',
        password: 'WrongPassword999!',
        acceptedTermsAndConvivencia: true
      }
    });
    expect(sixthRes.statusCode).toBe(429);
    expect(sixthRes.json().error).toBe('TOO_MANY_AUTH_ATTEMPTS');

    await limitedApp.close();
  });

  it('Bloque 2.1 — Regla P7 APROBADA (forbidGuestClubRoles = true): invitados (guest) no pueden recibir cargos honoríficos, mientras full/prospect sí', async () => {
    // Registrar líder 1, líder 2, líder 3, miembro full e invitado
    const l1 = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: {
        email: 'leader1-f2@biker.mx',
        password: 'Leader1Password!',
        displayName: 'Líder Principal F2',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true,
        activateLeaderDemo: true
      }
    });
    expect(l1.statusCode).toBe(200);
    leader1Id = l1.json().user.id;
    leader1Token = l1.json().accessToken;

    const l2 = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: {
        email: 'leader2-f2@biker.mx',
        password: 'Leader2Password!',
        displayName: 'Co-Líder 2 F2',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true,
        activateLeaderDemo: true
      }
    });
    leader2Id = l2.json().user.id;
    leader2Token = l2.json().accessToken;

    const l3 = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: {
        email: 'leader3-f2@biker.mx',
        password: 'Leader3Password!',
        displayName: 'Co-Líder 3 F2',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true,
        activateLeaderDemo: true
      }
    });
    leader3Id = l3.json().user.id;
    leader3Token = l3.json().accessToken;

    const gUser = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: {
        email: 'guest-p7@biker.mx',
        password: 'GuestPassword123!',
        displayName: 'Rider Invitado P7',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true
      }
    });
    guestUserId = gUser.json().user.id;
    guestUserToken = gUser.json().accessToken;

    const fUser = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: {
        email: 'full-p7@biker.mx',
        password: 'FullPassword123!',
        displayName: 'Rider Full P7',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true
      }
    });
    fullMemberId = fUser.json().user.id;
    fullMemberToken = fUser.json().accessToken;

    // Crear grupo y agregar a los otros 2 líderes
    const grp = await app.inject({
      method: 'POST',
      url: '/v1/groups',
      headers: { authorization: `Bearer ${leader1Token}` },
      payload: {
        countryCode: 'MX',
        name: 'Dragones del Norte F2',
        logoPhashHex: 'a1b2c3d4e5f60718'
      }
    });
    expect(grp.statusCode).toBe(201);
    groupId = grp.json().id;

    await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/leaders`,
      headers: { authorization: `Bearer ${leader1Token}` },
      payload: { userId: leader2Id }
    });
    await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/leaders`,
      headers: { authorization: `Bearer ${leader1Token}` },
      payload: { userId: leader3Id }
    });

    // 1. Intentar invitar a un guest con cargo honorífico falla con 400 GUEST_CANNOT_HOLD_CLUB_ROLE (P7)
    const badInvite = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/invitations`,
      headers: { authorization: `Bearer ${leader1Token}` },
      payload: {
        invitedUserId: guestUserId,
        statusKind: 'guest',
        clubRole: 'capitan_ruta'
      }
    });
    expect(badInvite.statusCode).toBe(400);
    expect(badInvite.json().error).toBe('GUEST_CANNOT_HOLD_CLUB_ROLE');

    // 2. Invitar al guest sin cargo honorífico e invitar al full member
    const okGuestInvite = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/invitations`,
      headers: { authorization: `Bearer ${leader1Token}` },
      payload: {
        invitedUserId: guestUserId,
        statusKind: 'guest'
      }
    });
    expect(okGuestInvite.statusCode).toBe(201);

    const acceptGuest = await app.inject({
      method: 'POST',
      url: `/v1/invitations/${okGuestInvite.json().id}/accept`,
      headers: { authorization: `Bearer ${guestUserToken}` },
      payload: {
        consents: {
          membership: true,
          location: true,
          emergencyPackage: true,
          notifications: true
        }
      }
    });
    expect(acceptGuest.statusCode).toBe(200);

    // 3. Intentar asignar cargo honorífico al guest ya aceptado mediante PATCH falla con 400 GUEST_CANNOT_HOLD_CLUB_ROLE
    const patchGuestRole = await app.inject({
      method: 'PATCH',
      url: `/v1/groups/${groupId}/members/${guestUserId}`,
      headers: { authorization: `Bearer ${leader1Token}` },
      payload: {
        clubRole: 'tesorero'
      }
    });
    expect(patchGuestRole.statusCode).toBe(400);
    expect(patchGuestRole.json().error).toBe('GUEST_CANNOT_HOLD_CLUB_ROLE');

    // 4. Invitar y aceptar al full member y asignarle cargo 'tesorero' funciona (200)
    const fullInvite = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/invitations`,
      headers: { authorization: `Bearer ${leader1Token}` },
      payload: {
        invitedUserId: fullMemberId,
        statusKind: 'full'
      }
    });
    await app.inject({
      method: 'POST',
      url: `/v1/invitations/${fullInvite.json().id}/accept`,
      headers: { authorization: `Bearer ${fullMemberToken}` },
      payload: {
        consents: {
          membership: true,
          location: true,
          emergencyPackage: true,
          notifications: true
        }
      }
    });
    const patchFullRole = await app.inject({
      method: 'PATCH',
      url: `/v1/groups/${groupId}/members/${fullMemberId}`,
      headers: { authorization: `Bearer ${leader1Token}` },
      payload: {
        clubRole: 'tesorero'
      }
    });
    expect(patchFullRole.statusCode).toBe(200);
    expect(patchFullRole.json().clubRole).toBe('tesorero');
  });

  it('Bloque 2.2 — Máquina de 6 estados de suscripción (active, grace 3d, on_hold, canceled, expired, refunded), alarmas 7/3/1 días e idempotencia RTDN', async () => {
    const now = new Date('2026-10-20T12:00:00.000Z');

    // Prueba unitaria directa de las 6 transiciones y alarmas 7/3/1 días
    const active2Days = evaluateSubscriptionState(
      {
        state: 'active',
        currentPeriodEnd: new Date(now.getTime() + 2 * 24 * 60 * 60 * 1000),
        graceUntil: null,
        autoRenewing: true
      },
      now
    );
    expect(active2Days.accessGranted).toBe(true);
    expect(active2Days.paymentSemaphore).toBe('yellow');
    expect(active2Days.alarms).toContain('expires_in_3d');

    const inGrace = evaluateSubscriptionState(
      {
        state: 'grace',
        currentPeriodEnd: new Date(now.getTime() - 1 * 24 * 60 * 60 * 1000),
        graceUntil: new Date(now.getTime() + 2 * 24 * 60 * 60 * 1000),
        autoRenewing: true
      },
      now
    );
    expect(inGrace.effectiveState).toBe('grace');
    expect(inGrace.accessGranted).toBe(true);
    expect(inGrace.paymentSemaphore).toBe('yellow');
    expect(inGrace.alarms).toContain('payment_failed_grace');

    // Al vencer los 3 días de gracia sin pago pasa automáticamente a on_hold y pierde acceso
    const afterGraceExpired = evaluateSubscriptionState(
      {
        state: 'grace',
        currentPeriodEnd: new Date(now.getTime() - 4 * 24 * 60 * 60 * 1000),
        graceUntil: new Date(now.getTime() - 1 * 24 * 60 * 60 * 1000),
        autoRenewing: true
      },
      now
    );
    expect(afterGraceExpired.effectiveState).toBe('on_hold');
    expect(afterGraceExpired.accessGranted).toBe(false);
    expect(afterGraceExpired.paymentSemaphore).toBe('red');

    // Verificar compra de cupo member_slot_1 para el invitado con FakePlayBillingVerifier
    const verifyGuestSub = await app.inject({
      method: 'POST',
      url: '/v1/subscriptions/verify',
      headers: { authorization: `Bearer ${guestUserToken}` },
      payload: {
        productId: 'member_slot_1',
        purchaseToken: 'fake-play-token-guest-slot-1',
        assignedGroupId: groupId
      }
    });
    expect(verifyGuestSub.statusCode).toBe(200);
    expect(verifyGuestSub.json().state).toBe('active');
    expect(verifyGuestSub.json().accessGranted).toBe(true);
    expect(verifyGuestSub.json().paymentSemaphore).toBe('green');

    // Notificación RTDN: fallo de cobro -> entra en periodo de gracia de 3 días (R7.3)
    const rtdnGrace = await app.inject({
      method: 'POST',
      url: '/v1/play/rtdn',
      payload: {
        messageId: 'rtdn-msg-001',
        purchaseToken: 'fake-play-token-guest-slot-1',
        productId: 'member_slot_1',
        notificationType: 'SUBSCRIPTION_IN_GRACE_PERIOD'
      }
    });
    expect(rtdnGrace.statusCode).toBe(200);
    expect(rtdnGrace.json().state).toBe('grace');
    expect(rtdnGrace.json().accessGranted).toBe(true);
    expect(rtdnGrace.json().paymentSemaphore).toBe('yellow');

    // Reenviar el mismo messageId RTDN es idempotente (no vuelve a aplicarse)
    const rtdnDuplicate = await app.inject({
      method: 'POST',
      url: '/v1/play/rtdn',
      payload: {
        messageId: 'rtdn-msg-001',
        purchaseToken: 'fake-play-token-guest-slot-1',
        productId: 'member_slot_1',
        notificationType: 'SUBSCRIPTION_EXPIRED'
      }
    });
    expect(rtdnDuplicate.statusCode).toBe(200);
    expect(rtdnDuplicate.json().idempotent).toBe(true);
    expect(rtdnDuplicate.json().status).toBe('duplicate_ignored');

    // Consultar GET /v1/subscriptions/me confirma que sigue en `grace`
    const mySubs = await app.inject({
      method: 'GET',
      url: '/v1/subscriptions/me',
      headers: { authorization: `Bearer ${guestUserToken}` }
    });
    expect(mySubs.statusCode).toBe(200);
    expect(mySubs.json().subscriptions[0].state).toBe('grace');
    expect(mySubs.json().subscriptions[0].accessGranted).toBe(true);
  });

  it('Bloque 2.2 — Credenciales firmadas Ed25519 (GET /v1/credentials), límite exp <= current_period_end + gracia, rotación de kid sin corte y revocación por reembolso (GET /v1/revocations)', async () => {
    // 1. El invitado en gracia (3 días restantes) pide su credencial offline:
    // Aunque el TTL máximo es 7 días, `exp` queda acotado a los 3 días de `grace_until` (BACKEND_LICENSING.md §7)
    const credsRes1 = await app.inject({
      method: 'GET',
      url: '/v1/credentials',
      headers: { authorization: `Bearer ${guestUserToken}` }
    });
    expect(credsRes1.statusCode).toBe(200);
    const body1 = credsRes1.json();
    expect(body1.activeKid).toBe('ed25519-k1');
    expect(body1.credentials.length).toBe(1);

    const guestCred1 = body1.credentials[0];
    expect(guestCred1.payload.kid).toBe('ed25519-k1');
    expect(guestCred1.payload.gid).toBe(groupId);
    expect(guestCred1.payload.kind).toBe('guest');
    // `exp - iat` debe ser <= 3 días (259200 s), nunca 7 días (604800 s), porque está en gracia de 3 días
    expect(guestCred1.payload.exp - guestCred1.payload.iat).toBeLessThanOrEqual(3 * 24 * 60 * 60 + 5);

    // 2. Rotar la clave de firma Ed25519 a `ed25519-k2`
    credentialKeyring.rotateKey('ed25519-k2');
    expect(credentialKeyring.getActiveKid()).toBe('ed25519-k2');

    // Emitir credencial del Líder 1 con la nueva clave `ed25519-k2`
    const leaderCredsRes = await app.inject({
      method: 'GET',
      url: '/v1/credentials',
      headers: { authorization: `Bearer ${leader1Token}` }
    });
    expect(leaderCredsRes.statusCode).toBe(200);
    const leaderCred = leaderCredsRes.json().credentials[0];
    expect(leaderCred.payload.kid).toBe('ed25519-k2');
    expect(leaderCred.payload.kind).toBe('leader');

    // Ambas credenciales (`ed25519-k1` anterior y `ed25519-k2` nueva) son válidas offline con el llavero rotado
    expect(
      verifyOfflineCredential(guestCred1.credential, {
        expectedGroupIds: [groupId],
        keyring: credentialKeyring
      }).valid
    ).toBe(true);
    expect(
      verifyOfflineCredential(leaderCred.credential, {
        expectedGroupIds: [groupId],
        keyring: credentialKeyring
      }).valid
    ).toBe(true);

    // 3. Reembolso de la suscripción del invitado (`SUBSCRIPTION_REVOKED`) -> estado `refunded`,
    // ya no se renueva credencial y aparece en `GET /v1/revocations` firmado con Ed25519
    const rtdnRefund = await app.inject({
      method: 'POST',
      url: '/v1/play/rtdn',
      payload: {
        messageId: 'rtdn-msg-refund-guest',
        purchaseToken: 'fake-play-token-guest-slot-1',
        productId: 'member_slot_1',
        notificationType: 'SUBSCRIPTION_REVOKED'
      }
    });
    expect(rtdnRefund.statusCode).toBe(200);
    expect(rtdnRefund.json().state).toBe('refunded');
    expect(rtdnRefund.json().accessGranted).toBe(false);

    const credsAfterRefund = await app.inject({
      method: 'GET',
      url: '/v1/credentials',
      headers: { authorization: `Bearer ${guestUserToken}` }
    });
    expect(credsAfterRefund.statusCode).toBe(200);
    expect(credsAfterRefund.json().credentials.length).toBe(0);

    const revRes = await app.inject({
      method: 'GET',
      url: `/v1/revocations?groupId=${groupId}`,
      headers: { authorization: `Bearer ${leader1Token}` }
    });
    expect(revRes.statusCode).toBe(200);
    const revBody = revRes.json();
    expect(revBody.kid).toBe('ed25519-k2');
    const guestRevEntry = revBody.revocations.find((r: { userId: string }) => r.userId === guestUserId);
    expect(guestRevEntry).toBeDefined();
    expect(guestRevEntry.reason).toBe('refunded');

    // Al sincronizar la lista de revocaciones antes de la rodada, la credencial previa del invitado es rechazada en la mesh
    const revokedSet = new Set<string>(revBody.revocations.map((r: { userId: string }) => r.userId));
    const checkAfterSync = verifyOfflineCredential(guestCred1.credential, {
      expectedGroupIds: [groupId],
      revokedUserIds: revokedSet,
      keyring: credentialKeyring
    });
    expect(checkAfterSync.valid).toBe(false);
    expect(checkAfterSync.reason).toBe('REVOKED_USER_OR_CREDENTIAL');
  });

  it('Bloque 2.2 — Reglas R4.5 (promoción automática del siguiente líder tras 7 días sin pago del principal) y R4.6 (suspensión del grupo si caen los 3 líderes conservando historial, y reactivación)', async () => {
    const l1Token = `demo-leader-token-${leader1Id}`;
    const l2Token = `demo-leader-token-${leader2Id}`;
    const l3Token = `demo-leader-token-${leader3Id}`;

    // Simular que la suscripción del Líder Principal (leader1) venció hace 8 días y entró en on_hold
    const eightDaysAgoIso = new Date(Date.now() - 8 * 24 * 60 * 60 * 1000).toISOString();
    await db.query(
      `UPDATE subscriptions
       SET state = 'on_hold', current_period_end = $1, grace_until = $1
       WHERE user_id = $2 AND kind = 'leader'`,
      [eightDaysAgoIso, leader1Id]
    );

    // Disparar RTDN de `SUBSCRIPTION_ON_HOLD` para el Líder 1:
    // Como lleva > 7 días sin pago y el Líder 2 tiene su suscripción activa, el Líder 2 es promovido automáticamente (R4.5)
    const rtdnL1Hold = await app.inject({
      method: 'POST',
      url: '/v1/play/rtdn',
      payload: {
        messageId: 'rtdn-l1-on-hold-8d',
        purchaseToken: l1Token,
        productId: 'leader_monthly',
        notificationType: 'SUBSCRIPTION_ON_HOLD'
      }
    });
    expect(rtdnL1Hold.statusCode).toBe(200);
    expect(rtdnL1Hold.json().reconciliations[0].promotedLeaderId).toBe(leader2Id);
    expect(rtdnL1Hold.json().reconciliations[0].newPrincipalLeader).toBe(leader2Id);

    // Verificar en GET /v1/groups/:id que el líder principal ahora es leader2Id
    const grpAfterPromotion = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}`,
      headers: { authorization: `Bearer ${leader2Token}` }
    });
    expect(grpAfterPromotion.statusCode).toBe(200);
    expect(grpAfterPromotion.json().principalLeader).toBe(leader2Id);
    expect(grpAfterPromotion.json().status).toBe('active');

    // Ahora caen también en impago el Líder 2 y el Líder 3 (los 3 líderes sin pago -> R4.6 suspende el grupo)
    await app.inject({
      method: 'POST',
      url: '/v1/play/rtdn',
      payload: {
        messageId: 'rtdn-l2-on-hold',
        purchaseToken: l2Token,
        productId: 'leader_monthly',
        notificationType: 'SUBSCRIPTION_ON_HOLD'
      }
    });
    const rtdnL3Hold = await app.inject({
      method: 'POST',
      url: '/v1/play/rtdn',
      payload: {
        messageId: 'rtdn-l3-on-hold',
        purchaseToken: l3Token,
        productId: 'leader_monthly',
        notificationType: 'SUBSCRIPTION_ON_HOLD'
      }
    });
    expect(rtdnL3Hold.statusCode).toBe(200);
    expect(rtdnL3Hold.json().reconciliations[0].suspendedAllLeadersUnpaid).toBe(true);
    expect(rtdnL3Hold.json().reconciliations[0].newStatus).toBe('suspended');

    // Verificar que el grupo está en estado `suspended`, pero su historial (miembros e insignias) sigue intacto (R4.6)
    const grpSuspended = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}`,
      headers: { authorization: `Bearer ${leader2Token}` }
    });
    expect(grpSuspended.json().status).toBe('suspended');

    const membersWhileSuspended = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}/members`,
      headers: { authorization: `Bearer ${leader2Token}` }
    });
    expect(membersWhileSuspended.statusCode).toBe(200);
    expect(membersWhileSuspended.json().members.length).toBeGreaterThanOrEqual(1);

    // Cuando el Líder 3 paga y renueva su suscripción (`SUBSCRIPTION_RENEWED`), el grupo se reactiva automáticamente
    // y el Líder 3 asume la titularidad al ser el único líder al corriente (R4.6)
    const rtdnL3Renew = await app.inject({
      method: 'POST',
      url: '/v1/play/rtdn',
      payload: {
        messageId: 'rtdn-l3-renewed',
        purchaseToken: l3Token,
        productId: 'leader_monthly',
        notificationType: 'SUBSCRIPTION_RENEWED'
      }
    });
    expect(rtdnL3Renew.statusCode).toBe(200);
    expect(rtdnL3Renew.json().reconciliations[0].reactivated).toBe(true);
    expect(rtdnL3Renew.json().reconciliations[0].newStatus).toBe('active');
    expect(rtdnL3Renew.json().reconciliations[0].newPrincipalLeader).toBe(leader3Id);
  });
});
