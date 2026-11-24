import { beforeAll, afterAll, describe, expect, it, vi } from 'vitest';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import { issueSessionToken } from '../src/lib/auth.js';
import { FcmService } from '../src/lib/fcm.js';

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
      fcm_tokens,
      notification_send_receipts,
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

describe('Fase 7.1.2 / Corte 2B — Notificaciones Push (FCM / Firebase Cloud Messaging)', () => {
  let app: FastifyInstance;
  let db: DbExecutor;
  let pool: any;
  let fcmStub: FcmService;
  let sendSpy: ReturnType<typeof vi.spyOn>;

  let groupId: string;
  let rider1Id: string;
  let rider1Token: string;
  let rider2Id: string;
  let rider2Token: string;
  let rider3Id: string;
  let rider3Token: string;
  let rider4Id: string;
  let rider4Token: string;

  beforeAll(async () => {
    const setup = await createTestDb();
    db = setup.db;
    pool = setup.pool;

    // Ruta inexistente => modo Stub determinista aunque el shell tenga GOOGLE_APPLICATION_CREDENTIALS.
    fcmStub = new FcmService('C:\\__rbs_missing_service_account__.json');
    sendSpy = vi.spyOn(fcmStub, 'sendMulticast');

    app = await buildApp({
      db,
      logger: false,
      fcmService: fcmStub
    });

    groupId = randomUUID();
    rider1Id = randomUUID();
    rider2Id = randomUUID();
    rider3Id = randomUUID();
    rider4Id = randomUUID();

    await db.query(
      `INSERT INTO users (id, email, display_name, country_code, account_type, status)
       VALUES
       ($1, 'rider1.fcm@rutabiker.com', 'Rider Uno', 'VE', 'member', 'active'),
       ($2, 'rider2.fcm@rutabiker.com', 'Rider Dos', 'VE', 'member', 'active'),
       ($3, 'rider3.fcm@rutabiker.com', 'Rider Tres', 'VE', 'member', 'active'),
       ($4, 'rider4.fcm@rutabiker.com', 'Rider Cuatro', 'VE', 'member', 'active')`,
      [rider1Id, rider2Id, rider3Id, rider4Id]
    );

    await db.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader)
       VALUES ($1, 'VE', 'Club FCM Test', 'club fcm test', decode('0000000000000000', 'hex'), $2)`,
      [groupId, rider1Id]
    );

    await db.query(
      `INSERT INTO group_leaders (group_id, user_id, is_principal)
       VALUES ($1, $2, true)`,
      [groupId, rider1Id]
    );

    await db.query(
      `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state, accepted_at)
       VALUES
       ($1, $4, $2, 'full', 'accepted', now()),
       ($3, $4, $5, 'full', 'accepted', now()),
       ($6, $4, $7, 'full', 'accepted', now())`,
      [randomUUID(), rider1Id, randomUUID(), groupId, rider2Id, randomUUID(), rider3Id]
    );

    // Rider 3 ya tiene token activo; rider 4 NO pertenece al club (prueba de ACL).
    await db.query(
      `INSERT INTO fcm_tokens (user_id, token, platform, updated_at)
       VALUES ($1, $2, 'android', now())`,
      [rider3Id, 'fcm_token_rider3_corte2b_abcdefghijklmnop']
    );

    rider1Token = issueSessionToken(rider1Id, 'access').token;
    rider2Token = issueSessionToken(rider2Id, 'access').token;
    rider3Token = issueSessionToken(rider3Id, 'access').token;
    rider4Token = issueSessionToken(rider4Id, 'access').token;
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
          groupId,
          messageId: randomUUID(),
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

      const sosCall = sendSpy.mock.calls.find((call) => (call[2] as Record<string, string>).type === 'sos');
      expect(sosCall?.[2]).toMatchObject({
        type: 'sos',
        lat: '10.4806',
        lng: '-66.9036'
      });
      expect(sosCall?.[3]).toBe('sos_channel');
    });

    it('2.2 Debería despachar una convocatoria de rodada (RIDE_INVITATION) en minúsculas al canal correcto', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/notifications/send',
        headers: {
          authorization: `Bearer ${rider1Token}`
        },
        payload: {
          type: 'RIDE_INVITATION',
          groupId,
          messageId: randomUUID(),
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

      const rideCall = sendSpy.mock.calls.find((call) => (call[2] as Record<string, string>).type === 'ride_invitation');
      expect(rideCall?.[3]).toBe('ride_invitation_channel');
    });

    it('2.3 Debería respetar la directiva R15: no enviar notificaciones a usuarios con consentimiento revocado', async () => {
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
          groupId,
          messageId: randomUUID(),
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

    it('2.4 Debería rechazar con 403 a un emisor que no es miembro del club', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/notifications/send',
        headers: { authorization: `Bearer ${rider4Token}` },
        payload: {
          type: 'DIRECT_MESSAGE',
          groupId,
          messageId: randomUUID(),
          recipientUserIds: [rider2Id],
          params: { preview: 'Intento de intruso' }
        }
      });

      expect(res.statusCode).toBe(403);
      expect(res.json().error).toBe('NOT_CLUB_MEMBER');
    });

    it('2.5 Debería rechazar con 403 si algún destinatario no es miembro del club', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/notifications/send',
        headers: { authorization: `Bearer ${rider1Token}` },
        payload: {
          type: 'DIRECT_MESSAGE',
          groupId,
          messageId: randomUUID(),
          recipientUserIds: [rider4Id],
          params: { preview: 'Destinatario ajeno' }
        }
      });

      expect(res.statusCode).toBe(403);
      expect(res.json().error).toBe('NOT_CLUB_MEMBER');
    });

    it('2.6 Un messageId repetido no duplica el envío (idempotencia)', async () => {
      const messageId = randomUUID();
      const payload = {
        type: 'DIRECT_MESSAGE' as const,
        groupId,
        messageId,
        recipientUserIds: [rider3Id],
        params: { preview: 'Mensaje idempotente' }
      };
      const callsBefore = sendSpy.mock.calls.length;

      const first = await app.inject({
        method: 'POST',
        url: '/v1/notifications/send',
        headers: { authorization: `Bearer ${rider1Token}` },
        payload
      });
      const second = await app.inject({
        method: 'POST',
        url: '/v1/notifications/send',
        headers: { authorization: `Bearer ${rider1Token}` },
        payload
      });

      expect(first.statusCode).toBe(200);
      expect(second.statusCode).toBe(200);
      expect(first.json().sentCount).toBe(1);
      expect(second.json().sentCount).toBe(1);
      expect(second.json().idempotentReplay).toBe(true);
      expect(sendSpy.mock.calls.length - callsBefore).toBe(1);

      const receipts = await db.query(
        `SELECT COUNT(*)::int AS count FROM notification_send_receipts
         WHERE sender_user_id = $1 AND message_id = $2`,
        [rider1Id, messageId]
      );
      expect(receipts.rows[0].count).toBe(1);
    });

    it('2.7 Debería devolver 429 al superar 10 envíos por hora y emisor', async () => {
      let lastStatus = 200;
      for (let i = 0; i < 10; i++) {
        const res = await app.inject({
          method: 'POST',
          url: '/v1/notifications/send',
          headers: { authorization: `Bearer ${rider3Token}` },
          payload: {
            type: 'DIRECT_MESSAGE',
            groupId,
            messageId: randomUUID(),
            // Rider 2 tiene consentimiento revocado: no consume FCM real.
            recipientUserIds: [rider2Id],
            params: { preview: `Rate limit ${i}` }
          }
        });
        lastStatus = res.statusCode;
      }
      expect(lastStatus).toBe(200);

      const exceeded = await app.inject({
        method: 'POST',
        url: '/v1/notifications/send',
        headers: { authorization: `Bearer ${rider3Token}` },
        payload: {
          type: 'DIRECT_MESSAGE',
          groupId,
          messageId: randomUUID(),
          recipientUserIds: [rider2Id],
          params: { preview: 'Debe ser rechazado' }
        }
      });

      expect(exceeded.statusCode).toBe(429);
      expect(exceeded.json().error).toBe('RATE_LIMIT_EXCEEDED');
    });

    it('2.8 TACTICAL_CHAT y CONVOY_SIGNAL usan tipo lowercase y canal de chat', async () => {
      const tactical = await app.inject({
        method: 'POST',
        url: '/v1/notifications/send',
        headers: { authorization: `Bearer ${rider1Token}` },
        payload: {
          type: 'TACTICAL_CHAT',
          groupId,
          messageId: randomUUID(),
          recipientUserIds: [rider3Id],
          params: { preview: 'Formación en rombo' }
        }
      });
      const convoy = await app.inject({
        method: 'POST',
        url: '/v1/notifications/send',
        headers: { authorization: `Bearer ${rider1Token}` },
        payload: {
          type: 'CONVOY_SIGNAL',
          groupId,
          messageId: randomUUID(),
          recipientUserIds: [rider3Id],
          params: { rideId: randomUUID(), signal: 'REGROUP' }
        }
      });

      expect(tactical.statusCode).toBe(200);
      expect(convoy.statusCode).toBe(200);
      expect(tactical.json().sentCount).toBe(1);
      expect(convoy.json().sentCount).toBe(1);

      const tacticalCall = sendSpy.mock.calls.find((call) => (call[2] as Record<string, string>).type === 'tactical_chat');
      const convoyCall = sendSpy.mock.calls.find((call) => (call[2] as Record<string, string>).type === 'convoy_signal');
      expect(tacticalCall?.[3]).toBe('direct_message_channel');
      expect(convoyCall?.[3]).toBe('direct_message_channel');
    });
  });
});
