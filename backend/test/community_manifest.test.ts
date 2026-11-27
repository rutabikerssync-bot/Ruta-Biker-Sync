import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import { verifyRideManifest } from '../src/lib/credentials.js';

describe('Manifiesto comunitario para Ruta Libre (GET /v1/community/manifest)', () => {
  let app: FastifyInstance;
  let db: DbExecutor;
  let publicUserId: string;
  let clubOnlyUserId: string;

  beforeAll(async () => {
    const pool = createPgPool();
    await pool.query('SELECT 1');
    await pool.query(readInitMigrationSql());
    db = pool;
    app = await buildApp({ db });
    await app.ready();

    publicUserId = randomUUID();
    clubOnlyUserId = randomUUID();
    await db.query(
      `INSERT INTO users (id, email, display_name, country_code, account_type, profile_visibility, status)
       VALUES
         ($1, $2, 'Rider Public', 'VE', 'member', 'PUBLIC', 'active'),
         ($3, $4, 'Rider ClubOnly', 'VE', 'member', 'CLUB_ONLY', 'active')`,
      [
        publicUserId,
        `public-${publicUserId}@test.local`,
        clubOnlyUserId,
        `clubonly-${clubOnlyUserId}@test.local`
      ]
    );
  });

  afterAll(async () => {
    await db?.query(`DELETE FROM users WHERE id = ANY($1::uuid[])`, [[publicUserId, clubOnlyUserId]]);
    await app?.close();
    await db?.end();
  });

  it('usuario con visibilidad PUBLIC recibe manifiesto comunitario firmado con TTL 5 min', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/community/manifest',
      headers: { 'x-user-id': publicUserId }
    });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(typeof body.manifest).toBe('string');
    expect(body.publicKeys).toBeTruthy();

    const [payloadB64] = body.manifest.split('.');
    const payload = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8'));
    expect(payload.rideId).toBe('ride-live-01');
    expect(payload.exp - payload.iat).toBe(300);
    expect(payload.participants.some((p: any) => p.uid === publicUserId)).toBe(true);
    expect(payload.participants.some((p: any) => p.uid === clubOnlyUserId)).toBe(false);
  });

  it('usuario con visibilidad CLUB_ONLY recibe 403', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/community/manifest',
      headers: { 'x-user-id': clubOnlyUserId }
    });
    expect(res.statusCode).toBe(403);
    expect(res.json().error).toBe('COMMUNITY_VISIBILITY_REQUIRED');
  });

  it('requiere autenticación x-user-id', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/community/manifest'
    });
    expect(res.statusCode).toBe(401);
  });
});
