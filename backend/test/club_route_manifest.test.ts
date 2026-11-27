import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import { withUserDb } from '../src/db/withUserDb.js';

function resolveTestDatabaseUrl(): string | undefined {
  if (process.env.PG_TEST_URL || process.env.DATABASE_URL) {
    return process.env.PG_TEST_URL ?? process.env.DATABASE_URL;
  }
  for (const filename of ['.env', '.env.example']) {
    const filePath = join(process.cwd(), filename);
    if (existsSync(filePath)) {
      const content = readFileSync(filePath, 'utf8');
      const pgTestMatch = content.match(/^PG_TEST_URL=(.+)$/m);
      if (pgTestMatch?.[1]) return pgTestMatch[1].trim();
      const dbUrlMatch = content.match(/^DATABASE_URL=(.+)$/m);
      if (dbUrlMatch?.[1]) return dbUrlMatch[1].trim();
    }
  }
  return undefined;
}

describe('Manifiesto de grupo desde club_routes (GET /v1/rides/:id/manifest)', () => {
  let app: FastifyInstance;
  let pool: ReturnType<typeof createPgPool>;
  let db: DbExecutor;
  let leaderId: string;
  let memberId: string;
  let outsiderId: string;
  let groupId: string;
  let routeId: string;

  beforeAll(async () => {
    pool = createPgPool(resolveTestDatabaseUrl());
    await pool.query('SELECT 1');
    await pool.query(readInitMigrationSql());
    db = pool;
    app = await buildApp({ db });
    await app.ready();

    leaderId = randomUUID();
    memberId = randomUUID();
    outsiderId = randomUUID();
    groupId = randomUUID();

    await db.query(
      `INSERT INTO users (id, email, country_code, account_type, display_name, status)
       VALUES
         ($1, $2, 'VE', 'leader', 'Lider Club', 'active'),
         ($3, $4, 'VE', 'member', 'Miembro Club', 'active'),
         ($5, $6, 'VE', 'member', 'Externo', 'active')`,
      [
        leaderId, `leader-${leaderId}@test.local`,
        memberId, `member-${memberId}@test.local`,
        outsiderId, `outsider-${outsiderId}@test.local`
      ]
    );

    await db.query(
      `INSERT INTO groups (id, name, name_normalized, logo_phash, country_code, principal_leader)
       VALUES ($1, 'Club Manifiesto Test', $3, decode('0000000000000000', 'hex'), 'VE', $2)`,
      [groupId, leaderId, `clubmanifiestotest${groupId.replace(/-/g, '')}`]
    );

    await db.query(
      `INSERT INTO group_leaders (group_id, user_id, is_principal)
       VALUES ($1, $2, true)`,
      [groupId, leaderId]
    );

    await db.query(
      `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state, accepted_at)
       VALUES ($1, $2, $3, 'full', 'accepted', now())`,
      [randomUUID(), groupId, memberId]
    );
  });

  afterAll(async () => {
    if (routeId) {
      await withUserDb(pool, leaderId, async (client) => {
        await client.query(`DELETE FROM club_route_rsvps WHERE route_id = $1`, [routeId]);
        await client.query(`DELETE FROM club_routes WHERE id = $1`, [routeId]);
      });
    }
    await db?.query(`DELETE FROM memberships WHERE group_id = $1`, [groupId]);
    await db?.query(`DELETE FROM group_leaders WHERE group_id = $1`, [groupId]);
    await db?.query(`DELETE FROM groups WHERE id = $1`, [groupId]);
    await db?.query(`DELETE FROM users WHERE id = ANY($1::uuid[])`, [[leaderId, memberId, outsiderId]]);
    await app?.close();
    await db?.end();
  });

  it('POST /v1/groups/:id/routes genera y persiste manifest_ride_secret_key_hex y manifest_issued_at', async () => {
    const createRes = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/routes`,
      headers: { 'x-user-id': leaderId },
      payload: {
        title: 'Rodada Prueba Manifiesto',
        startsAt: new Date(Date.now() + 3600_000).toISOString()
      }
    });
    expect(createRes.statusCode).toBe(201);
    routeId = createRes.json().route.id;

    const dbRes = await withUserDb(pool, leaderId, (client) =>
      client.query(
        `SELECT manifest_ride_secret_key_hex, manifest_issued_at FROM club_routes WHERE id = $1`,
        [routeId]
      )
    );
    expect(dbRes.rows[0].manifest_ride_secret_key_hex).toMatch(/^[0-9a-f]{64}$/);
    expect(dbRes.rows[0].manifest_issued_at).toBeTruthy();
  });

  it('GET /v1/rides/:id/manifest resuelve ruta en club_routes con 200 y participantes', async () => {
    const rsvpRes = await app.inject({
      method: 'POST',
      url: `/v1/routes/${routeId}/rsvp`,
      headers: { 'x-user-id': memberId },
      payload: { status: 'confirmed' }
    });
    expect([200, 201]).toContain(rsvpRes.statusCode);

    const resLeader = await app.inject({
      method: 'GET',
      url: `/v1/rides/${routeId}/manifest`,
      headers: { 'x-user-id': leaderId }
    });
    expect(resLeader.statusCode).toBe(200);
    const body = resLeader.json();
    expect(typeof body.manifest).toBe('string');
    expect(body.publicKeys).toBeTruthy();

    const [payloadB64] = body.manifest.split('.');
    const payload = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8'));
    expect(payload.rideId).toBe(routeId);
    expect(payload.groupIds).toEqual([groupId]);
    expect(payload.participants.some((p: any) => p.uid === leaderId)).toBe(true);
    expect(payload.participants.some((p: any) => p.uid === memberId)).toBe(true);

    const resMember = await app.inject({
      method: 'GET',
      url: `/v1/rides/${routeId}/manifest`,
      headers: { 'x-user-id': memberId }
    });
    expect(resMember.statusCode).toBe(200);

    const resOutsider = await app.inject({
      method: 'GET',
      url: `/v1/rides/${routeId}/manifest`,
      headers: { 'x-user-id': outsiderId }
    });
    expect([403, 404]).toContain(resOutsider.statusCode);
  });

  it('GET /v1/rides/:id/manifest para ruta inexistente devuelve 404 RIDE_NOT_FOUND', async () => {
    const missingId = randomUUID();
    const res = await app.inject({
      method: 'GET',
      url: `/v1/rides/${missingId}/manifest`,
      headers: { 'x-user-id': leaderId }
    });
    expect(res.statusCode).toBe(404);
    expect(res.json().error).toBe('RIDE_NOT_FOUND');
  });
});
