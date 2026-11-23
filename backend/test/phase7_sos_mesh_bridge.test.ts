import { beforeAll, afterAll, describe, expect, it } from 'vitest';
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
  return { db: pool, pool };
}

describe('Fase 7.1.5.5 — Radar SOS Off-Grid BLE Mesh Bridge (Ingesta HTTP & Deduplicación)', () => {
  let app: FastifyInstance;
  let db: DbExecutor;
  let pool: any;
  let fakeFcm: FcmService;

  let relayUserId: string;
  let relayUserToken: string;

  beforeAll(async () => {
    const testDb = await createTestDb();
    db = testDb.db;
    pool = testDb.pool;

    fakeFcm = new FcmService();
    app = await buildApp({ db, fcmService: fakeFcm });
    await app.ready();

    relayUserId = randomUUID();
    relayUserToken = issueSessionToken(relayUserId, 'access').token;

    await db.query(
      `INSERT INTO users (id, email, display_name, country_code, account_type, status)
       VALUES ($1, $2, 'Motero Puente', 'VE', 'member', 'active')
       ON CONFLICT (id) DO NOTHING`,
      [relayUserId, `motero_puente_${randomUUID()}@rutabikersync.com`]
    );

    // Consentimiento emergency_package del usuario puente
    await db.query(
      `INSERT INTO consents (id, user_id, kind, granted, policy_version)
       VALUES (gen_random_uuid(), $1, 'emergency_package', TRUE, '1.0')`,
      [relayUserId]
    );
  });

  afterAll(async () => {
    await app.close();
    await pool.end();
  });

  it('1. Ingesta de SOS con source = "ble_mesh" y meshPacketId retorna 201 y persiste metadata', async () => {
    const meshPacketId = `mesh-pkt-${randomUUID()}`;
    const payload = {
      latitude: 10.4064,
      longitude: -67.2886,
      hasMedicalSheet: true,
      radiusKm: 5.0,
      notes: 'Accidente en curva - Puenteado por BLE Mesh',
      source: 'ble_mesh',
      meshPacketId
    };

    const res = await app.inject({
      method: 'POST',
      url: '/v1/sos',
      headers: {
        authorization: `Bearer ${relayUserToken}`
      },
      payload
    });

    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.success).toBe(true);
    expect(body.source).toBe('ble_mesh');
    expect(body.meshPacketId).toBe(meshPacketId);
    expect(body.sosId).toBeDefined();

    // Verificar en base de datos
    const queryRes = await db.query(
      `SELECT source, mesh_packet_id FROM sos_events WHERE id = $1`,
      [body.sosId]
    );
    expect(queryRes.rows.length).toBe(1);
    expect(queryRes.rows[0].source).toBe('ble_mesh');
    expect(queryRes.rows[0].mesh_packet_id).toBe(meshPacketId);
  });

  it('2. Deduplicación idempotente: 2do envío con idéntico meshPacketId retorna 200 duplicate=true sin recrear evento', async () => {
    const meshPacketId = `duplicate-mesh-${randomUUID()}`;
    const payload = {
      latitude: 10.4000,
      longitude: -67.2800,
      hasMedicalSheet: false,
      radiusKm: 5.0,
      source: 'ble_mesh',
      meshPacketId
    };

    // Primer envío -> 201 Created
    const res1 = await app.inject({
      method: 'POST',
      url: '/v1/sos',
      headers: {
        authorization: `Bearer ${relayUserToken}`
      },
      payload
    });
    expect(res1.statusCode).toBe(201);
    const body1 = res1.json();
    const originalSosId = body1.sosId;

    // Segundo envío idéntico (simulando 2 moteros puentes subiendo el mismo paquete BLE) -> 200 OK con duplicate=true
    const res2 = await app.inject({
      method: 'POST',
      url: '/v1/sos',
      headers: {
        authorization: `Bearer ${relayUserToken}`
      },
      payload
    });
    expect(res2.statusCode).toBe(200);
    const body2 = res2.json();
    expect(body2.success).toBe(true);
    expect(body2.duplicate).toBe(true);
    expect(body2.sosId).toBe(originalSosId);
    expect(body2.meshPacketId).toBe(meshPacketId);

    // Verificar que solo existe 1 registro en sos_events para este meshPacketId
    const countRes = await db.query(
      `SELECT count(*)::int AS count FROM sos_events WHERE mesh_packet_id = $1`,
      [meshPacketId]
    );
    expect(countRes.rows[0].count).toBe(1);
  });

  it('3. Fallback a source = "app" cuando se omite el campo source', async () => {
    const payload = {
      latitude: 10.4100,
      longitude: -67.2900,
      hasMedicalSheet: false,
      radiusKm: 5.0
    };

    const res = await app.inject({
      method: 'POST',
      url: '/v1/sos',
      headers: {
        authorization: `Bearer ${relayUserToken}`
      },
      payload
    });

    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.success).toBe(true);
    expect(body.source).toBe('app');
    expect(body.meshPacketId).toBeNull();
  });
});
