import { beforeAll, afterAll, describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import { issueSessionToken } from '../src/lib/auth.js';
import { FcmService } from '../src/lib/fcm.js';
import {
  calculateHaversineDistanceKm,
  calculateBearingDegrees,
  degreesToCardinal,
  formatSosAlertText
} from '../src/lib/geo.js';

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
      sos_events,
      ephemeral_telemetry,
      fcm_tokens,
      refresh_tokens,
      rate_limit_events,
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

describe('Fase 7.1.5 — Radar SOS de Proximidad 5 km y Telemetría Efímera', () => {
  let app: FastifyInstance;
  let db: DbExecutor;
  let pool: any;
  let fakeFcm: FcmService;

  // Usuarios de prueba
  let victimId: string;
  let victimToken: string;

  let rescuerNearId: string; // Dentro de 5 km, consentimientos OK
  let rescuerNearToken: string;

  let rescuerFarId: string; // Fuera de 5 km (> 7 km)
  let rescuerFarToken: string;

  let rescuerNoConsentId: string; // Dentro de 5 km, pero emergency_package = false
  let rescuerNoConsentToken: string;

  let rescuerNoNotifId: string; // Dentro de 5 km, pero notifications = false
  let rescuerNoNotifToken: string;

  // Coordenadas base (Caracas)
  const VICTIM_LAT = 10.5000;
  const VICTIM_LNG = -66.9167;

  // Punto a ~1.8 km al Noreste
  const NEAR_LAT = 10.5120;
  const NEAR_LNG = -66.9030;

  // Punto a ~8 km al Norte
  const FAR_LAT = 10.5750;
  const FAR_LNG = -66.9167;

  beforeAll(async () => {
    const setup = await createTestDb();
    db = setup.db;
    pool = setup.pool;

    fakeFcm = new FcmService();

    app = await buildApp({
      db,
      logger: false,
      fcmService: fakeFcm
    });

    victimId = randomUUID();
    rescuerNearId = randomUUID();
    rescuerFarId = randomUUID();
    rescuerNoConsentId = randomUUID();
    rescuerNoNotifId = randomUUID();

    // Crear usuarios
    await db.query(
      `INSERT INTO users (id, email, display_name, country_code, account_type, status)
       VALUES
       ($1, 'victim.sos@rutabiker.com', 'Piloto Accidentado', 'VE', 'member', 'active'),
       ($2, 'near.sos@rutabiker.com', 'Rescatista Cercano', 'VE', 'member', 'active'),
       ($3, 'far.sos@rutabiker.com', 'Rescatista Lejano', 'VE', 'member', 'active'),
       ($4, 'noconsent.sos@rutabiker.com', 'Sin Consentimiento', 'VE', 'member', 'active'),
       ($5, 'nonotif.sos@rutabiker.com', 'Sin Notificaciones', 'VE', 'member', 'active')`,
      [victimId, rescuerNearId, rescuerFarId, rescuerNoConsentId, rescuerNoNotifId]
    );

    victimToken = issueSessionToken(victimId, 'access').token;
    rescuerNearToken = issueSessionToken(rescuerNearId, 'access').token;
    rescuerFarToken = issueSessionToken(rescuerFarId, 'access').token;
    rescuerNoConsentToken = issueSessionToken(rescuerNoConsentId, 'access').token;
    rescuerNoNotifToken = issueSessionToken(rescuerNoNotifId, 'access').token;

    // Configurar consentimientos canónicos
    // 1. Victima: emergency_package = true, location = true, notifications = true
    await db.query(
      `INSERT INTO consents (id, user_id, kind, granted, policy_version) VALUES
       (gen_random_uuid(), $1, 'emergency_package', TRUE, '1.0'),
       (gen_random_uuid(), $1, 'location', TRUE, '1.0'),
       (gen_random_uuid(), $1, 'notifications', TRUE, '1.0')`,
      [victimId]
    );

    // 2. Rescatista Cercano: emergency_package = true, location = true, notifications = true
    await db.query(
      `INSERT INTO consents (id, user_id, kind, granted, policy_version) VALUES
       (gen_random_uuid(), $1, 'emergency_package', TRUE, '1.0'),
       (gen_random_uuid(), $1, 'location', TRUE, '1.0'),
       (gen_random_uuid(), $1, 'notifications', TRUE, '1.0')`,
      [rescuerNearId]
    );

    // 3. Rescatista Lejano: emergency_package = true, location = true, notifications = true
    await db.query(
      `INSERT INTO consents (id, user_id, kind, granted, policy_version) VALUES
       (gen_random_uuid(), $1, 'emergency_package', TRUE, '1.0'),
       (gen_random_uuid(), $1, 'location', TRUE, '1.0'),
       (gen_random_uuid(), $1, 'notifications', TRUE, '1.0')`,
      [rescuerFarId]
    );

    // 4. Sin consentimiento: emergency_package = FALSE, notifications = TRUE
    await db.query(
      `INSERT INTO consents (id, user_id, kind, granted, policy_version) VALUES
       (gen_random_uuid(), $1, 'emergency_package', FALSE, '1.0'),
       (gen_random_uuid(), $1, 'location', TRUE, '1.0'),
       (gen_random_uuid(), $1, 'notifications', TRUE, '1.0')`,
      [rescuerNoConsentId]
    );

    // 5. Sin notificaciones: emergency_package = TRUE, notifications = FALSE
    await db.query(
      `INSERT INTO consents (id, user_id, kind, granted, policy_version) VALUES
       (gen_random_uuid(), $1, 'emergency_package', TRUE, '1.0'),
       (gen_random_uuid(), $1, 'location', TRUE, '1.0'),
       (gen_random_uuid(), $1, 'notifications', FALSE, '1.0')`,
      [rescuerNoNotifId]
    );

    // Registrar tokens FCM de prueba
    await db.query(
      `INSERT INTO fcm_tokens (user_id, token, platform) VALUES
       ($1, 'token_near_device_123', 'android'),
       ($2, 'token_far_device_456', 'android'),
       ($3, 'token_noconsent_device_789', 'android'),
       ($4, 'token_nonotif_device_000', 'android')`,
      [rescuerNearId, rescuerFarId, rescuerNoConsentId, rescuerNoNotifId]
    );
  });

  afterAll(async () => {
    if (app) await app.close();
    if (pool) await pool.end();
  });

  describe('1. Utilidades Matemáticas Geoespaciales (Haversine & Bearing)', () => {
    it('1.1 Debería calcular con precisión la distancia Haversine entre coordenadas conocidas', () => {
      const dist = calculateHaversineDistanceKm(VICTIM_LAT, VICTIM_LNG, NEAR_LAT, NEAR_LNG);
      // Entre (10.5000, -66.9167) y (10.5120, -66.9030) hay ~1.98 km
      expect(dist).toBeGreaterThan(1.5);
      expect(dist).toBeLessThan(2.5);
    });

    it('1.2 Debería calcular el rumbo cardinal correcto en grados y 8 direcciones tácticas', () => {
      // De Víctima a Rescatista Cercano (Noreste)
      const bearing = calculateBearingDegrees(VICTIM_LAT, VICTIM_LNG, NEAR_LAT, NEAR_LNG);
      expect(bearing).toBeGreaterThan(25);
      expect(bearing).toBeLessThan(65);
      const cardinal = degreesToCardinal(bearing);
      expect(cardinal).toBe('Noreste ↗');

      // Desde Rescatista hacia Víctima (Suroeste)
      const bearingReverse = calculateBearingDegrees(NEAR_LAT, NEAR_LNG, VICTIM_LAT, VICTIM_LNG);
      const cardinalReverse = degreesToCardinal(bearingReverse);
      expect(cardinalReverse).toBe('Suroeste ↙');
    });

    it('1.3 Debería formatear el texto del banner sin exponer coordenadas en texto plano', () => {
      const alert = formatSosAlertText(1.8, 'Noreste ↗');
      expect(alert).toBe('🚨 SOS BIKER CERCANO — A 1.8 km al Noreste ↗');
      expect(alert).not.toContain('lat');
      expect(alert).not.toContain('10.5');
      expect(alert).not.toContain('-66.9');
    });
  });

  describe('2. Telemetría Efímera (POST /v1/telemetry/heartbeat)', () => {
    it('2.1 Debería actualizar la posición efímera de los usuarios con consentimiento de ubicación', async () => {
      // Posición del rescatista cercano
      const resNear = await app.inject({
        method: 'POST',
        url: '/v1/telemetry/heartbeat',
        headers: { authorization: `Bearer ${rescuerNearToken}` },
        payload: {
          latitude: NEAR_LAT,
          longitude: NEAR_LNG,
          heading: 45.0,
          speedKmh: 60.0
        }
      });
      expect(resNear.statusCode).toBe(200);
      expect(resNear.json().success).toBe(true);

      // Posición del rescatista lejano (> 7 km)
      const resFar = await app.inject({
        method: 'POST',
        url: '/v1/telemetry/heartbeat',
        headers: { authorization: `Bearer ${rescuerFarToken}` },
        payload: {
          latitude: FAR_LAT,
          longitude: FAR_LNG,
          heading: 0.0,
          speedKmh: 40.0
        }
      });
      expect(resFar.statusCode).toBe(200);

      // Posición de los otros dos usuarios (dentro de radio para evaluar consentimientos)
      await app.inject({
        method: 'POST',
        url: '/v1/telemetry/heartbeat',
        headers: { authorization: `Bearer ${rescuerNoConsentToken}` },
        payload: { latitude: NEAR_LAT, longitude: NEAR_LNG }
      });

      await app.inject({
        method: 'POST',
        url: '/v1/telemetry/heartbeat',
        headers: { authorization: `Bearer ${rescuerNoNotifToken}` },
        payload: { latitude: NEAR_LAT, longitude: NEAR_LNG }
      });

      // Verificar persistencia en ephemeral_telemetry
      const dbCheck = await db.query('SELECT count(*) FROM ephemeral_telemetry');
      expect(parseInt(dbCheck.rows[0].count, 10)).toBe(4);
    });
  });

  describe('3. Detonación de SOS y Consulta Haversine en SQL (POST /v1/sos)', () => {
    it('3.1 Debería detonar SOS, filtrar por Bounding Box + Haversine y notificar SOLO a usuarios cercanos con consentimiento', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/sos',
        headers: { authorization: `Bearer ${victimToken}` },
        payload: {
          latitude: VICTIM_LAT,
          longitude: VICTIM_LNG,
          hasMedicalSheet: true,
          radiusKm: 5.0,
          notes: 'Caída en curva, posible fractura'
        }
      });

      expect(res.statusCode).toBe(201);
      const body = res.json();
      expect(body.success).toBe(true);
      expect(body.sosId).toBeDefined();

      // Debe detectar y notificar ÚNICAMENTE al Rescatista Cercano:
      // - Rescatista Lejano: DESCARTADO por Haversine (> 5 km)
      // - Rescatista Sin Consentimiento: DESCARTADO por emergency_package = false (R15)
      // - Rescatista Sin Notificaciones: DESCARTADO por notifications = false (R15)
      expect(body.notifiedUsersCount).toBe(1);
      expect(body.notifiedUserIds).toContain(rescuerNearId);
      expect(body.notifiedUserIds).not.toContain(rescuerFarId);
      expect(body.notifiedUserIds).not.toContain(rescuerNoConsentId);
      expect(body.notifiedUserIds).not.toContain(rescuerNoNotifId);
      expect(body.notifiedUserIds).not.toContain(victimId); // La propia víctima no se auto-notifica
    });

    it('3.2 Debería rechazar con 403 si el usuario emisor no tiene consentimiento emergency_package', async () => {
      // Crear un usuario sin consentimiento de emergencia
      const unconsentedId = randomUUID();
      await db.query(
        `INSERT INTO users (id, email, display_name, country_code, account_type, status)
         VALUES ($1, 'denied@rutabiker.com', 'Usuario Denegado', 'VE', 'member', 'active')`,
        [unconsentedId]
      );
      await db.query(
        `INSERT INTO consents (id, user_id, kind, granted, policy_version)
         VALUES (gen_random_uuid(), $1, 'emergency_package', FALSE, '1.0')`,
        [unconsentedId]
      );
      const token = issueSessionToken(unconsentedId, 'access').token;

      const res = await app.inject({
        method: 'POST',
        url: '/v1/sos',
        headers: { authorization: `Bearer ${token}` },
        payload: {
          latitude: VICTIM_LAT,
          longitude: VICTIM_LNG
        }
      });

      expect(res.statusCode).toBe(403);
      expect(res.json().error).toBe('EMERGENCY_PACKAGE_CONSENT_REQUIRED');
    });
  });

  describe('4. Consulta de Alertas Cercanas en Vivo (GET /v1/sos/nearby)', () => {
    it('4.1 Rescatista dentro de 5 km debería consultar exitosamente el SOS activo con rumbo y distancia', async () => {
      const res = await app.inject({
        method: 'GET',
        url: `/v1/sos/nearby?lat=${NEAR_LAT}&lng=${NEAR_LNG}&radiusKm=5.0`,
        headers: { authorization: `Bearer ${rescuerNearToken}` }
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(body.count).toBeGreaterThanOrEqual(1);

      const alert = body.sosAlerts[0];
      expect(alert.distanceKm).toBeGreaterThan(1.0);
      expect(alert.distanceKm).toBeLessThanOrEqual(5.0);
      expect(alert.bearing).toBeDefined();
      expect(alert.hasMedicalSheet).toBe(true);
      expect(alert.emitterAlias).toBe('Piloto Accidentado');
    });

    it('4.2 Rescatista fuera de 5 km no debería recibir alertas lejanas', async () => {
      const res = await app.inject({
        method: 'GET',
        url: `/v1/sos/nearby?lat=${FAR_LAT}&lng=${FAR_LNG}&radiusKm=5.0`,
        headers: { authorization: `Bearer ${rescuerFarToken}` }
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(body.count).toBe(0);
      expect(body.sosAlerts).toHaveLength(0);
    });

    it('4.3 Debería resolver el evento SOS con POST /v1/sos/:id/resolve', async () => {
      // Consultar el ID del SOS activo
      const sosList = await db.query('SELECT id FROM sos_events WHERE status = \'active\' LIMIT 1');
      const sosId = sosList.rows[0].id;

      const resolveRes = await app.inject({
        method: 'POST',
        url: `/v1/sos/${sosId}/resolve`,
        headers: { authorization: `Bearer ${victimToken}` }
      });

      expect(resolveRes.statusCode).toBe(200);
      expect(resolveRes.json().status).toBe('resolved');

      // Ahora GET /v1/sos/nearby ya no debe devolverlo
      const checkNearby = await app.inject({
        method: 'GET',
        url: `/v1/sos/nearby?lat=${NEAR_LAT}&lng=${NEAR_LNG}`,
        headers: { authorization: `Bearer ${rescuerNearToken}` }
      });
      expect(checkNearby.json().count).toBe(0);
    });
  });
});
