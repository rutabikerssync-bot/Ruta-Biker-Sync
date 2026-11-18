import { beforeAll, afterAll, describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import { issueSessionToken } from '../src/lib/auth.js';
import { FcmService } from '../src/lib/fcm.js';

function resolveTestDatabaseUrl(): string {
  return process.env.PG_TEST_URL || process.env.DATABASE_URL || 'postgres://rbs:rbs_dev_password@localhost:5433/rbs';
}

async function createTestDb(): Promise<{ db: DbExecutor; pool: any }> {
  const connectionString = resolveTestDatabaseUrl();
  const pool = createPgPool(connectionString);
  await pool.query('SELECT 1');
  const sql = readInitMigrationSql();
  await pool.query(sql);
  await pool.query(`
    TRUNCATE TABLE
      fcm_tokens,
      refresh_tokens,
      rate_limit_events,
      ephemeral_telemetry,
      vault_recovery_tokens,
      vault_recovery_attempts,
      shared_medical_sheets,
      consents,
      rides,
      groups,
      users
    RESTART IDENTITY CASCADE
  `);
  return { db: pool, pool };
}

describe('Fase 7.1.2 — Notificaciones Push (FCM / Firebase Cloud Messaging)', () => {
  let app: FastifyInstance;
  let db: DbExecutor;
  let pool: any;

  let rider1Id: string;
  let rider1Token: string;
  let rider2Id: string;
  let rider2Token: string;

  beforeAll(async () => {
    const setup = await createTestDb();
    db = setup.db;
    pool = setup.pool;

    app = await buildApp({
      db,
      logger: false,
      fcmService: new FcmService() // Mock/Stub mode para tests herméticos
    });

    rider1Id = randomUUID();
    rider2Id = randomUUID();

    await db.query(
      `INSERT INTO users (id, email, display_name, country_code, account_type, status)
       VALUES
       ($1, 'rider1.fcm@rutabiker.com', 'Rider Uno', 'VE', 'member', 'active'),
       ($2, 'rider2.fcm@rutabiker.com', 'Rider Dos', 'VE', 'member', 'active')`,
      [rider1Id, rider2Id]
    );

    rider1Token = issueSessionToken(rider1Id, 'access').token;
    rider2Token = issueSessionToken(rider2Id, 'access').token;
  });

  afterAll(async () => {
    if (app) await app.close();
    if (pool) await pool.end();
  });

  describe('Registro de Tokens FCM (POST /v1/users/fcm-token)', () => {
    it('1.1 Debería registrar exitosamente el token FCM de un dispositivo Android', async () => {
      const fcmTokenSample = 'fcm_token_sample_android_1234567890_abcdef';

      const res = await app.inject({
        method: 'POST',
        url: '/v1/users/fcm-token',
        headers: {
          authorization: `Bearer ${rider1Token}`
        },
        payload: {
          token: fcmTokenSample,
          platform: 'android'
        }
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(body.success).toBe(true);
      expect(body.userId).toBe(rider1Id);
      expect(body.platform).toBe('android');

      // Verificar persistencia en base de datos PostgreSQL
      const dbRes = await db.query(
        `SELECT token, platform FROM fcm_tokens WHERE user_id = $1`,
        [rider1Id]
      );
      expect(dbRes.rows.length).toBe(1);
      expect(dbRes.rows[0].token).toBe(fcmTokenSample);
      expect(dbRes.rows[0].platform).toBe('android');
    });

    it('1.2 Debería actualizar idempotentemente el token sin duplicar registros', async () => {
      const fcmTokenSample = 'fcm_token_sample_android_1234567890_abcdef';

      const res = await app.inject({
        method: 'POST',
        url: '/v1/users/fcm-token',
        headers: {
          authorization: `Bearer ${rider1Token}`
        },
        payload: {
          token: fcmTokenSample,
          platform: 'android'
        }
      });

      expect(res.statusCode).toBe(200);

      const dbRes = await db.query(
        `SELECT COUNT(*)::int AS count FROM fcm_tokens WHERE user_id = $1`,
        [rider1Id]
      );
      expect(dbRes.rows[0].count).toBe(1);
    });

    it('1.3 Debería rechazar un token FCM demasiado corto o ausente con 400 VALIDATION_ERROR', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/users/fcm-token',
        headers: {
          authorization: `Bearer ${rider1Token}`
        },
        payload: {
          token: 'short'
        }
      });

      expect(res.statusCode).toBe(400);
      expect(res.json().error).toBe('VALIDATION_ERROR');
    });
  });

  describe('Envío y Despacho de Notificaciones (POST /v1/notifications/send)', () => {
    it('2.1 Debería despachar una notificación de SOS de proximidad a usuarios con token activo', async () => {
      // Registrar token para rider2
      await app.inject({
        method: 'POST',
        url: '/v1/users/fcm-token',
        headers: { authorization: `Bearer ${rider2Token}` },
        payload: { token: 'fcm_token_rider2_radar_emergency_token_777' }
      });

      const res = await app.inject({
        method: 'POST',
        url: '/v1/notifications/send',
        headers: {
          authorization: `Bearer ${rider1Token}`
        },
        payload: {
          type: 'SOS_PROXIMITY',
          recipientUserIds: [rider2Id],
          params: {
            rideId: randomUUID(),
            lat: 10.4806,
            lng: -66.9036
          }
        }
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(body.success).toBe(true);
      expect(body.type).toBe('SOS_PROXIMITY');
      expect(body.sentCount).toBe(1);
      expect(body.failureCount).toBe(0);
    });

    it('2.2 Debería despachar una convocatoria de rodada (RIDE_INVITATION)', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/notifications/send',
        headers: {
          authorization: `Bearer ${rider1Token}`
        },
        payload: {
          type: 'RIDE_INVITATION',
          recipientUserIds: [rider2Id],
          params: {
            rideId: randomUUID(),
            clubName: 'Centauros de Acero MC',
            rideTitle: 'Ruta Nocturna El Jarillo'
          }
        }
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(body.success).toBe(true);
      expect(body.type).toBe('RIDE_INVITATION');
      expect(body.sentCount).toBe(1);
    });

    it('2.3 Debería respetar la directiva R15: no enviar notificaciones a usuarios con consentimiento revocado', async () => {
      // Revocar consentimiento de notificaciones para rider2
      await db.query(
        `INSERT INTO consents (id, user_id, group_id, kind, granted, policy_version, created_at)
         VALUES ($1, $2, NULL, 'notifications', false, '1.0', now())`,
        [randomUUID(), rider2Id]
      );

      const res = await app.inject({
        method: 'POST',
        url: '/v1/notifications/send',
        headers: {
          authorization: `Bearer ${rider1Token}`
        },
        payload: {
          type: 'DIRECT_MESSAGE',
          recipientUserIds: [rider2Id],
          params: {
            preview: 'Hola compañero, ¿todo listo para la rodada?'
          }
        }
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(body.success).toBe(true);
      expect(body.sentCount).toBe(0);
      expect(body.message).toContain('No hay tokens FCM activos o con consentimiento');
    });
  });
});
