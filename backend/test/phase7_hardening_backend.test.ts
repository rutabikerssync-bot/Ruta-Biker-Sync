import { beforeAll, afterAll, describe, expect, it } from 'vitest';
import { generateKeyPairSync, sign, randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import { issueSessionToken } from '../src/lib/auth.js';
import { canonicalRideSummaryString, type RideSummaryData } from '../src/lib/credentials.js';
import { executeRgpdPurge } from '../src/jobs/rgpdPurgeJob.js';

function resolveTestDatabaseUrl(): string {
  return process.env.PG_TEST_URL || process.env.DATABASE_URL || 'postgres://rbs:rbs_dev_password@localhost:5433/rbs_test';
}

async function createTestDb(): Promise<{ db: DbExecutor; pool: any }> {
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
      vault_recovery_tokens,
      vault_recovery_attempts,
      shared_medical_sheets,
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
  return { db: pool, pool };
}

describe('Fase 7.1.1 — Hardening de Servidor & Backend', () => {
  let app: FastifyInstance;
  let db: DbExecutor;
  let pool: any;

  let leaderUserId: string;
  let leaderToken: string;
  let leaderGroupId: string;
  let keyPair: { publicKey: any; privateKey: any };
  let leaderPubRaw: Buffer;

  beforeAll(async () => {
    const setup = await createTestDb();
    db = setup.db;
    pool = setup.pool;

    app = await buildApp({
      db,
      logger: false,
      rateLimitAuthMaxPerHour: 10,
      rateLimitInviteMaxPerHour: 5
    });

    // Generar par de claves Ed25519 nativo
    keyPair = generateKeyPairSync('ed25519');
    const spkiDer = keyPair.publicKey.export({ type: 'spki', format: 'der' }) as Buffer;
    leaderPubRaw = spkiDer.subarray(spkiDer.length - 32);

    // Crear líder de prueba
    leaderUserId = randomUUID();
    await db.query(
      `INSERT INTO users (id, email, display_name, country_code, account_type, status, public_key)
       VALUES ($1, 'leader.hardening@rutabiker.com', 'Líder Hardening', 'VE', 'leader', 'active', $2)`,
      [leaderUserId, leaderPubRaw]
    );

    leaderGroupId = randomUUID();
    await db.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader)
       VALUES ($1, 'VE', 'Águilas Hardening MC', 'aguilas hardening mc', decode('0102030405060708', 'hex'), $2)`,
      [leaderGroupId, leaderUserId]
    );

    await db.query(
      `INSERT INTO group_leaders (group_id, user_id, is_principal)
       VALUES ($1, $2, true)`,
      [leaderGroupId, leaderUserId]
    );

    await db.query(
      `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state)
       VALUES ($1, $2, $3, 'full', 'accepted')`,
      [randomUUID(), leaderGroupId, leaderUserId]
    );

    leaderToken = issueSessionToken(leaderUserId, 'access').token;
  });

  afterAll(async () => {
    if (app) await app.close();
    if (pool) await pool.end();
  });

  // ---------------------------------------------------------------------------
  // 1. Cierre de Rodada con Ed25519 y Anti-Cheat (POST /v1/rides/:id/close)
  // ---------------------------------------------------------------------------
  describe('Cierre de Rodada: Firma Ed25519 y Anti-Cheat', () => {
    it('1.1 Debería cerrar exitosamente la rodada con firma Ed25519 canónica válida', async () => {
      const rideId = randomUUID();
      await db.query(
        `INSERT INTO rides (id, host_group, host_leader, title, state, starts_at)
         VALUES ($1, $2, $3, 'Rodada Hardening Válida', 'active', now())`,
        [rideId, leaderGroupId, leaderUserId]
      );

      const summaryData: RideSummaryData = {
        rideId,
        distanceKm: 28.5,
        durationMinutes: 50,
        meshKm: 25.0,
        maxSpeedKmh: 98.4,
        elevationGainM: 210,
        startedAtNight: false,
        rained: false,
        offlineMinutes: 5,
        closedAtIso: '2026-11-14T21:00:00.000Z'
      };

      const canonicalStr = canonicalRideSummaryString(summaryData);
      const signatureBuf = sign(null, Buffer.from(canonicalStr, 'utf8'), keyPair.privateKey);
      const signatureBase64 = signatureBuf.toString('base64');

      const res = await app.inject({
        method: 'POST',
        url: `/v1/rides/${rideId}/close`,
        headers: {
          authorization: `Bearer ${leaderToken}`
        },
        payload: {
          distanceKm: summaryData.distanceKm,
          durationMinutes: summaryData.durationMinutes,
          meshKm: summaryData.meshKm,
          maxSpeedKmh: summaryData.maxSpeedKmh,
          elevationGainM: summaryData.elevationGainM,
          startedAtNight: summaryData.startedAtNight,
          rained: summaryData.rained,
          offlineMinutes: summaryData.offlineMinutes,
          closedAtIso: summaryData.closedAtIso,
          signature: signatureBase64,
          leaderPublicKey: leaderPubRaw.toString('base64')
        }
      });

      expect(res.statusCode).toBe(200);
      const body = JSON.parse(res.payload);
      expect(body.id).toBe(rideId);
      expect(body.distanceKm).toBe(28.5);
      expect(body.durationMinutes).toBe(50);
      expect(['closed', 'validated']).toContain(body.state);
    });

    it('1.2 Debería rechazar con 403 INVALID_SIGNATURE si la firma Ed25519 está alterada o falsificada', async () => {
      const rideId = randomUUID();
      await db.query(
        `INSERT INTO rides (id, host_group, host_leader, title, state, starts_at)
         VALUES ($1, $2, $3, 'Rodada Firma Falsificada', 'active', now())`,
        [rideId, leaderGroupId, leaderUserId]
      );

      const fakeSignature = Buffer.alloc(64, 0xaa).toString('base64');

      const res = await app.inject({
        method: 'POST',
        url: `/v1/rides/${rideId}/close`,
        headers: {
          authorization: `Bearer ${leaderToken}`
        },
        payload: {
          distanceKm: 30.0,
          durationMinutes: 45,
          meshKm: 20.0,
          maxSpeedKmh: 80.0,
          signature: fakeSignature,
          leaderPublicKey: leaderPubRaw.toString('base64')
        }
      });

      expect(res.statusCode).toBe(403);
      const body = JSON.parse(res.payload);
      expect(body.error).toBe('INVALID_SIGNATURE');
    });

    it('1.3 Debería rechazar con 403 INVALID_SUMMARY si meshKm > distanceKm (Anti-Cheat)', async () => {
      const rideId = randomUUID();
      await db.query(
        `INSERT INTO rides (id, host_group, host_leader, title, state, starts_at)
         VALUES ($1, $2, $3, 'Rodada meshKm Trampa', 'active', now())`,
        [rideId, leaderGroupId, leaderUserId]
      );

      const res = await app.inject({
        method: 'POST',
        url: `/v1/rides/${rideId}/close`,
        headers: {
          authorization: `Bearer ${leaderToken}`
        },
        payload: {
          distanceKm: 20.0,
          meshKm: 35.0, // 35 km de malla en una rodada de 20 km es físicamente imposible
          durationMinutes: 40
        }
      });

      expect(res.statusCode).toBe(403);
      const body = JSON.parse(res.payload);
      expect(body.error).toBe('INVALID_SUMMARY');
      expect(body.message).toContain('Los kilómetros en malla no pueden superar');
    });

    it('1.4 Debería rechazar con 403 INVALID_SUMMARY si maxSpeedKmh > 200 (Anti-Cheat)', async () => {
      const rideId = randomUUID();
      await db.query(
        `INSERT INTO rides (id, host_group, host_leader, title, state, starts_at)
         VALUES ($1, $2, $3, 'Rodada Velocidad Absurda', 'active', now())`,
        [rideId, leaderGroupId, leaderUserId]
      );

      const res = await app.inject({
        method: 'POST',
        url: `/v1/rides/${rideId}/close`,
        headers: {
          authorization: `Bearer ${leaderToken}`
        },
        payload: {
          distanceKm: 25.0,
          durationMinutes: 30,
          maxSpeedKmh: 245.5 // Excede el umbral de 200 km/h
        }
      });

      expect(res.statusCode).toBe(403);
      const body = JSON.parse(res.payload);
      expect(body.error).toBe('INVALID_SUMMARY');
      expect(body.message).toContain('200 km/h');
    });
  });

  // ---------------------------------------------------------------------------
  // 2. Rate Limiting (/v1/auth y /v1/invites)
  // ---------------------------------------------------------------------------
  describe('Rate Limiting de Seguridad', () => {
    it('2.1 Debería bloquear con 429 TOO_MANY_AUTH_ATTEMPTS y Retry-After: 3600 al superar 10 intentos en 1h', async () => {
      const testIp = '198.51.100.77';

      // Realizar 10 peticiones dentro del límite permitido
      for (let i = 1; i <= 10; i++) {
        const res = await app.inject({
          method: 'POST',
          url: '/v1/auth/login',
          remoteAddress: testIp,
          payload: {
            email: `nonexistent_${i}@rutabiker.com`,
            password: 'SomePassword123!',
            acceptedTermsAndConvivencia: true,
            policyVersion: '1.0'
          }
        });
        // 401 o 400 son válidos aquí porque las credenciales no existen, pero NO 429
        expect(res.statusCode).not.toBe(429);
      }

      // La 11ª petición desde la misma IP debe ser bloqueada por rate limit
      const blockedRes = await app.inject({
        method: 'POST',
        url: '/v1/auth/login',
        remoteAddress: testIp,
        payload: {
          email: 'blocked@rutabiker.com',
          password: 'SomePassword123!',
          acceptedTermsAndConvivencia: true,
          policyVersion: '1.0'
        }
      });

      expect(blockedRes.statusCode).toBe(429);
      expect(blockedRes.headers['retry-after']).toBe('3600');
      const body = JSON.parse(blockedRes.payload);
      expect(body.error).toBe('TOO_MANY_AUTH_ATTEMPTS');
      expect(body.retryAfter).toBe(3600);

      // Otra IP distinta debe poder operar sin verse afectada
      const otherIpRes = await app.inject({
        method: 'POST',
        url: '/v1/auth/login',
        remoteAddress: '198.51.100.88',
        payload: {
          email: 'other_ip@rutabiker.com',
          password: 'SomePassword123!',
          acceptedTermsAndConvivencia: true,
          policyVersion: '1.0'
        }
      });
      expect(otherIpRes.statusCode).not.toBe(429);
    });

    it('2.2 Debería bloquear con 429 TOO_MANY_INVITE_ATTEMPTS al superar 5 invitaciones en 1h por (IP, userId)', async () => {
      const testIp = '198.51.100.99';

      // 5 invitaciones permitidas
      for (let i = 1; i <= 5; i++) {
        const res = await app.inject({
          method: 'POST',
          url: '/v1/invites',
          remoteAddress: testIp,
          headers: {
            authorization: `Bearer ${leaderToken}`
          },
          payload: {
            groupId: leaderGroupId,
            invitedUserId: randomUUID(),
            statusKind: 'prospect'
          }
        });
        expect(res.statusCode).toBe(201);
      }

      // La 6ª petición desde la misma (IP, userId) debe retornar 429
      const blockedRes = await app.inject({
        method: 'POST',
        url: '/v1/invites',
        remoteAddress: testIp,
        headers: {
          authorization: `Bearer ${leaderToken}`
        },
        payload: {
          groupId: leaderGroupId,
          invitedUserId: randomUUID(),
          statusKind: 'prospect'
        }
      });

      expect(blockedRes.statusCode).toBe(429);
      expect(blockedRes.headers['retry-after']).toBe('3600');
      const body = JSON.parse(blockedRes.payload);
      expect(body.error).toBe('TOO_MANY_INVITE_ATTEMPTS');
      expect(body.retryAfter).toBe(3600);
    });
  });

  // ---------------------------------------------------------------------------
  // 3. Purga RGPD (24h)
  // ---------------------------------------------------------------------------
  describe('Purga RGPD Automática (24h)', () => {
    it('3.1 Debería purgar registros antiguos (>24h / >30d) y mantener intactos los vigentes', async () => {
      // 1. Insertar telemetría efímera antigua (>25 horas) y reciente (<1 hora)
      const rideId = randomUUID();
      await db.query(
        `INSERT INTO rides (id, host_group, host_leader, title, state, starts_at)
         VALUES ($1, $2, $3, 'Rodada RGPD', 'closed', now() - interval '30 hours')`,
        [rideId, leaderGroupId, leaderUserId]
      );

      await db.query(
        `INSERT INTO ephemeral_telemetry (ride_id, user_id, lat, lng, created_at)
         VALUES ($1, $2, 10.48, -66.90, now() - interval '26 hours')`,
        [rideId, leaderUserId]
      );
      await db.query(
        `INSERT INTO ephemeral_telemetry (ride_id, user_id, lat, lng, created_at)
         VALUES ($1, $2, 10.49, -66.89, now() - interval '30 minutes')`,
        [rideId, leaderUserId]
      );

      // 2. Insertar token de bóveda usado antiguo (>25 horas) y token vigente
      await db.query(
        `INSERT INTO vault_recovery_tokens (id, user_id, token, expires_at, used, created_at)
         VALUES ($1, $2, 'token_antiguo', now() - interval '2 hours', true, now() - interval '26 hours')`,
        [randomUUID(), leaderUserId]
      );
      await db.query(
        `INSERT INTO vault_recovery_tokens (id, user_id, token, expires_at, used, created_at)
         VALUES ($1, $2, 'token_vigente', now() + interval '5 minutes', false, now())`,
        [randomUUID(), leaderUserId]
      );

      // 3. Insertar intento de recuperación antiguo (>31 días) y reciente (<1 día)
      await db.query(
        `INSERT INTO vault_recovery_attempts (ip, user_id, endpoint, success, created_at)
         VALUES ('127.0.0.1', $1, '/v1/vault/recovery/token', false, now() - interval '32 days')`,
        [leaderUserId]
      );
      await db.query(
        `INSERT INTO vault_recovery_attempts (ip, user_id, endpoint, success, created_at)
         VALUES ('127.0.0.1', $1, '/v1/vault/recovery/token', true, now() - interval '2 hours')`,
        [leaderUserId]
      );

      // 4. Insertar evento de rate limit antiguo (>25 horas)
      await db.query(
        `INSERT INTO rate_limit_events (scope, ip, user_id, created_at)
         VALUES ('auth', '10.0.0.99', NULL, now() - interval '26 hours')`,
        []
      );

      // Ejecutar la purga RGPD
      const result = await executeRgpdPurge(db);

      expect(result.oldEphemeralTelemetry).toBeGreaterThanOrEqual(1);
      expect(result.expiredRecoveryTokens).toBeGreaterThanOrEqual(1);
      expect(result.oldRecoveryAttempts).toBeGreaterThanOrEqual(1);
      expect(result.oldRateLimitEvents).toBeGreaterThanOrEqual(1);

      // Verificar que los datos vigentes permanecen
      const teleCount = await db.query(
        `SELECT COUNT(*)::int AS count FROM ephemeral_telemetry WHERE ride_id = $1`,
        [rideId]
      );
      expect(teleCount.rows[0].count).toBe(1);

      const tokenVigente = await db.query(
        `SELECT token FROM vault_recovery_tokens WHERE token = 'token_vigente'`,
        []
      );
      expect(tokenVigente.rows.length).toBe(1);
    });
  });
});
