import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import { issueSessionToken } from '../src/lib/auth.js';

function resolveTestDatabaseUrl(): string {
  return process.env.PG_TEST_URL || process.env.DATABASE_URL || 'postgres://rbs:rbs_dev_password@localhost:5433/rbs';
}

async function createTestDb(): Promise<{ db: DbExecutor; pool: any }> {
  const pool = createPgPool(resolveTestDatabaseUrl());
  await pool.query('SELECT 1');
  await pool.query(readInitMigrationSql());
  await pool.query(`
    TRUNCATE TABLE
      ephemeral_telemetry,
      sos_events,
      ride_participants,
      ride_groups,
      rides,
      group_leaders,
      memberships,
      invitations,
      groups,
      consents,
      users
    RESTART IDENTITY CASCADE
  `);
  return { db: pool, pool };
}

describe('Redundancia híbrida — telemetría de rodada por HTTP', () => {
  let app: FastifyInstance;
  let db: DbExecutor;
  let pool: any;

  let leaderId: string;
  let memberId: string;
  let outsiderId: string;
  let groupId: string;
  let rideId: string;
  let leaderToken: string;
  let memberToken: string;
  let outsiderToken: string;

  beforeAll(async () => {
    const setup = await createTestDb();
    db = setup.db;
    pool = setup.pool;
    app = await buildApp({ db, logger: false });

    leaderId = randomUUID();
    memberId = randomUUID();
    outsiderId = randomUUID();
    groupId = randomUUID();
    rideId = randomUUID();

    await db.query(
      `INSERT INTO users (id, email, display_name, country_code, account_type, status)
       VALUES
         ($1, 'hybrid-leader@rutabiker.com', 'Líder Híbrido', 'VE', 'leader', 'active'),
         ($2, 'hybrid-member@rutabiker.com', 'Miembro Híbrido', 'VE', 'member', 'active'),
         ($3, 'hybrid-outsider@rutabiker.com', 'Foráneo Híbrido', 'VE', 'member', 'active')`,
      [leaderId, memberId, outsiderId]
    );

    await db.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader)
       VALUES ($1, 'VE', 'Club Híbrido', 'club hibrido', decode('0102030405060708', 'hex'), $2)`,
      [groupId, leaderId]
    );

    await db.query(
      `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state, accepted_at)
       VALUES (gen_random_uuid(), $1, $2, 'full', 'accepted', now()),
              (gen_random_uuid(), $1, $3, 'full', 'accepted', now())`,
      [groupId, leaderId, memberId]
    );

    await db.query(
      `INSERT INTO rides (id, host_group, host_leader, title, state, starts_at)
       VALUES ($1, $2, $3, 'Rodada híbrida', 'active', now())`,
      [rideId, groupId, leaderId]
    );

    await db.query(
      `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind)
       VALUES ($1, $2, $3, 'full'), ($1, $4, $3, 'full')`,
      [rideId, leaderId, groupId, memberId]
    );

    leaderToken = issueSessionToken(leaderId, 'access').token;
    memberToken = issueSessionToken(memberId, 'access').token;
    outsiderToken = issueSessionToken(outsiderId, 'access').token;
  });

  afterAll(async () => {
    if (app) await app.close();
    if (pool) await pool.end();
  });

  it('1. El heartbeat con rideId persiste la posición y la lectura devuelve un rider por usuario', async () => {
    const first = await app.inject({
      method: 'POST',
      url: '/v1/telemetry/heartbeat',
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: {
        latitude: 10.3538,
        longitude: -67.0425,
        heading: 90,
        speedKmh: 42,
        rideId
      }
    });
    expect(first.statusCode).toBe(200);
    expect(first.json().success).toBe(true);

    const second = await app.inject({
      method: 'POST',
      url: '/v1/telemetry/heartbeat',
      headers: { authorization: `Bearer ${memberToken}` },
      payload: { latitude: 10.3452, longitude: -67.1648, rideId }
    });
    expect(second.statusCode).toBe(200);

    const res = await app.inject({
      method: 'GET',
      url: `/v1/rides/${rideId}/telemetry`,
      headers: { authorization: `Bearer ${memberToken}` }
    });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.rideId).toBe(rideId);
    expect(body.riders).toHaveLength(2);
    expect(body.riders.map((r: any) => r.userId).sort()).toEqual([leaderId, memberId].sort());

    const leaderSample = body.riders.find((r: any) => r.userId === leaderId);
    expect(leaderSample.latitude).toBeCloseTo(10.3538, 6);
    expect(leaderSample.heading).toBe(90);
    expect(leaderSample.speedKmh).toBe(42);
  });

  it('2. Rechaza lectura y escritura a foráneos de la rodada', async () => {
    const read = await app.inject({
      method: 'GET',
      url: `/v1/rides/${rideId}/telemetry`,
      headers: { authorization: `Bearer ${outsiderToken}` }
    });
    expect(read.statusCode).toBe(403);
    expect(read.json().error).toBe('NOT_IN_RIDE');

    const write = await app.inject({
      method: 'POST',
      url: '/v1/telemetry/heartbeat',
      headers: { authorization: `Bearer ${outsiderToken}` },
      payload: { latitude: 10.5, longitude: -66.9, rideId }
    });
    expect(write.statusCode).toBe(403);
    expect(write.json().error).toBe('NOT_IN_RIDE');
  });

  it('3. La lectura respeta la ventana de frescura y devuelve solo la muestra más reciente por usuario', async () => {
    await db.query(
      `INSERT INTO ephemeral_telemetry (ride_id, user_id, lat, lng, recorded_at)
       VALUES ($1, $2, 0.0, 0.0, now() - interval '2 hours')`,
      [rideId, leaderId]
    );
    await db.query(
      `INSERT INTO ephemeral_telemetry (ride_id, user_id, lat, lng, recorded_at)
       VALUES ($1, $2, 10.4000, -67.0000, now())`,
      [rideId, leaderId]
    );

    const res = await app.inject({
      method: 'GET',
      url: `/v1/rides/${rideId}/telemetry?maxAgeSeconds=30&limit=50`,
      headers: { authorization: `Bearer ${leaderToken}` }
    });
    expect(res.statusCode).toBe(200);
    const riders = res.json().riders as any[];
    const leaderSamples = riders.filter((r) => r.userId === leaderId);
    expect(leaderSamples).toHaveLength(1);
    expect(leaderSamples[0].latitude).toBeCloseTo(10.4, 6);
  });

  it('4. Valida identificadores y mantiene el heartbeat sin rideId retrocompatible', async () => {
    const invalidRead = await app.inject({
      method: 'GET',
      url: '/v1/rides/no-es-uuid/telemetry',
      headers: { authorization: `Bearer ${leaderToken}` }
    });
    expect(invalidRead.statusCode).toBe(400);
    expect(invalidRead.json().error).toBe('INVALID_RIDE_ID');

    const invalidWrite = await app.inject({
      method: 'POST',
      url: '/v1/telemetry/heartbeat',
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: { latitude: 10.5, longitude: -66.9, rideId: 'no-es-uuid' }
    });
    expect(invalidWrite.statusCode).toBe(400);

    const legacy = await app.inject({
      method: 'POST',
      url: '/v1/telemetry/heartbeat',
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: { latitude: 10.5, longitude: -66.9 }
    });
    expect(legacy.statusCode).toBe(200);

    const stored = await db.query(
      `SELECT ride_id FROM ephemeral_telemetry WHERE user_id = $1 ORDER BY created_at DESC LIMIT 1`,
      [leaderId]
    );
    expect(stored.rows[0].ride_id).toBeNull();
  });
});
