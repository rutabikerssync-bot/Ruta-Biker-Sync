import { describe, expect, it } from 'vitest';
import { newDb } from 'pg-mem';
import { buildApp } from '../src/app.js';

const RIDE_ID = '11111111-1111-4111-8111-111111111111';
const LEADER_ID = '33333333-3333-4333-8333-333333333333';
const SCOUT_ID = '44444444-4444-4444-8444-444444444444';

describe('GET /v1/rides/:id/participants — roster enriquecido', () => {
  it('devuelve nombre, avatar y rol reales, con fallbacks honestos', async () => {
    const mem = newDb();
    const pgAdapter = mem.adapters.createPg();
    const pool = new pgAdapter.Pool();

    await pool.query(`
      CREATE TABLE users (
        id text PRIMARY KEY,
        display_name text,
        photo_url text,
        map_color text,
        bike_model text,
        public_key text
      )
    `);
    await pool.query(`
      CREATE TABLE rides (
        id text PRIMARY KEY,
        host_leader text,
        cohost_leader text
      )
    `);
    await pool.query(`
      CREATE TABLE ride_participants (
        ride_id text,
        user_id text,
        group_id text,
        status_kind text,
        ride_role text,
        joined_at timestamp
      )
    `);

    await pool.query(
      `INSERT INTO users (id, display_name, photo_url, map_color, bike_model)
       VALUES ($1, $2, $3, $4, $5), ($6, $7, NULL, NULL, NULL)`,
      [
        LEADER_ID, 'Juan Martínez', 'https://example.test/juan.webp', '#FFB300', 'Yamaha XT1200',
        SCOUT_ID, null
      ]
    );
    await pool.query(
      `INSERT INTO rides (id, host_leader, cohost_leader) VALUES ($1, $2, NULL)`,
      [RIDE_ID, LEADER_ID]
    );
    await pool.query(
      `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind, ride_role, joined_at)
       VALUES ($1, $2, $3, $4, $5, now()), ($1, $6, $3, $4, $7, now())`,
      [RIDE_ID, LEADER_ID, 'g-1', 'full', 'capitan_rodada', SCOUT_ID, 'scout']
    );

    const app = await buildApp({ db: pool, logger: false });

    const ok = await app.inject({
      method: 'GET',
      url: `/v1/rides/${RIDE_ID}/participants`,
      headers: { 'x-user-id': LEADER_ID }
    });
    expect(ok.statusCode).toBe(200);
    const body = ok.json();
    expect(body.participants).toHaveLength(2);
    expect(body.participants[0]).toMatchObject({
      userId: LEADER_ID,
      displayName: 'Juan Martínez',
      avatarUrl: 'https://example.test/juan.webp',
      rideRole: 'capitan_rodada',
      roleInConvoy: 'Punta',
      bikeModel: 'Yamaha XT1200'
    });
    expect(body.participants[1]).toMatchObject({
      userId: SCOUT_ID,
      displayName: 'Desconocido',
      avatarUrl: null,
      roleInConvoy: 'Scout'
    });
    expect(body.note).toContain('shortId');

    const forbidden = await app.inject({
      method: 'GET',
      url: `/v1/rides/${RIDE_ID}/participants`,
      headers: { 'x-user-id': '55555555-5555-4555-8555-555555555555' }
    });
    expect(forbidden.statusCode).toBe(403);

    const notFound = await app.inject({
      method: 'GET',
      url: '/v1/rides/22222222-2222-4222-8222-222222222222/participants',
      headers: { 'x-user-id': LEADER_ID }
    });
    expect(notFound.statusCode).toBe(404);

    await app.close();
    await pool.end();
  });
});
