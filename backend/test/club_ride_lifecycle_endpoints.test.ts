import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
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

describe('Ciclo de rodada: presencia, fases y ventana RSVP con reloj servidor', () => {
  let app: FastifyInstance;
  let db: DbExecutor;
  let pool: ReturnType<typeof createPgPool>;
  let routeId = '';

  const presidentId = randomUUID();
  const coLeaderId = randomUUID();
  const memberId = randomUUID();
  const member2Id = randomUUID();
  const outsiderId = randomUUID();
  const groupId = randomUUID();

  beforeAll(async () => {
    pool = createPgPool(resolveTestDatabaseUrl());
    await pool.query('SELECT 1');
    db = pool;
    await db.query(readInitMigrationSql());
    await db.query(`
      TRUNCATE TABLE
        club_route_rsvps, club_routes,
        refresh_tokens, rate_limit_events, ephemeral_telemetry, imitation_reports,
        play_events, ride_mesh_metrics, leader_vote_ballots, leader_votes,
        moderation_reports, consents, emergency_access_log, badges, rank_progress,
        ride_participants, ride_groups, rides, group_transfers, invitations,
        memberships, group_leaders, groups, subscriptions, users
      RESTART IDENTITY CASCADE
    `);
    const users = [presidentId, coLeaderId, memberId, member2Id, outsiderId];
    for (const id of users) {
      await db.query(
        `INSERT INTO users (id, email, country_code, account_type, display_name)
         VALUES ($1, $2, 'VE', $3, $4)`,
        [id, `${id}@lifecycle.test`, id === presidentId || id === coLeaderId ? 'leader' : 'member', `Rider ${id.slice(0, 8)}`]
      );
    }
    await db.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader)
       VALUES ($1, 'VE', 'Lifecycle Test Club', 'lifecycletestclub', decode('0000000000000000', 'hex'), $2)`,
      [groupId, presidentId]
    );
    await db.query(
      `INSERT INTO group_leaders (group_id, user_id, is_principal)
       VALUES ($1, $2, true), ($1, $3, false)`,
      [groupId, presidentId, coLeaderId]
    );
    for (const uid of [presidentId, coLeaderId, memberId, member2Id]) {
      await db.query(
        `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state, accepted_at)
         VALUES ($1, $2, $3, 'full', 'accepted', now())`,
        [randomUUID(), groupId, uid]
      );
    }
    app = await buildApp({ db, logger: false });
  });

  afterAll(async () => {
    await app.close();
    await pool.end();
  });

  beforeEach(async () => {
    await db.query('TRUNCATE TABLE club_route_rsvps, club_routes RESTART IDENTITY CASCADE');
    const { rows } = await withUserDb(pool, presidentId, (client) =>
      client.query(
        `INSERT INTO club_routes (group_id, created_by, title, starts_at, status)
         VALUES ($1, $2, 'Lifecycle route', now() + ($3::text)::interval, 'published')
         RETURNING id`,
        [groupId, presidentId, '2 hours']
      )
    );
    routeId = rows[0].id;
  });

  async function createRouteFromNow(interval: string): Promise<string> {
    const { rows } = await withUserDb(pool, presidentId, (client) =>
      client.query(
        `INSERT INTO club_routes (group_id, created_by, title, starts_at, status)
         VALUES ($1, $2, 'Lifecycle window route', now() + ($3::text)::interval, 'published')
         RETURNING id`,
        [groupId, presidentId, interval]
      )
    );
    return rows[0].id;
  }

  async function seedRsvp(
    userId: string,
    status = 'confirmed',
    onSite = false,
    id = routeId
  ) {
    await withUserDb(pool, userId, (client) =>
      client.query(
        `INSERT INTO club_route_rsvps (route_id, user_id, status, responded_at, on_site_at)
         VALUES ($1, $2, $3, now(), CASE WHEN $4 THEN now() ELSE NULL END)`,
        [id, userId, status, onSite]
      )
    );
  }

  async function rsvp(status: string, userId = memberId, id = routeId) {
    return app.inject({
      method: 'POST', url: `/v1/routes/${id}/rsvp`,
      headers: { 'x-user-id': userId }, payload: { status }
    });
  }

  async function arrive(userId = memberId, id = routeId) {
    return app.inject({
      method: 'POST', url: `/v1/routes/${id}/arrive`,
      headers: { 'x-user-id': userId }, payload: {}
    });
  }

  async function lifecycle(path: string, userId = presidentId, id = routeId) {
    return app.inject({
      method: 'POST', url: `/v1/routes/${id}/${path}`,
      headers: { 'x-user-id': userId }, payload: {}
    });
  }

  async function routeRow(id = routeId) {
    return withUserDb(pool, presidentId, async (client) => {
      const { rows } = await client.query('SELECT * FROM club_routes WHERE id = $1', [id]);
      return rows[0];
    });
  }

  it('01 pendiente puede elegir asistir o no asistir', async () => {
    expect((await rsvp('confirmed')).statusCode).toBe(201);
    const declined = await rsvp('no_go');
    expect(declined.statusCode).toBe(200);
    expect(declined.json().rsvp.status).toBe('no_go');
    const { rows } = await withUserDb(pool, memberId, (client) =>
      client.query(
        'SELECT count(*)::int AS n FROM club_route_rsvps WHERE route_id = $1 AND user_id = $2',
        [routeId, memberId]
      )
    );
    expect(rows[0].n).toBe(1);
  });

  it('02 no_go no puede reconsiderar antes de T-1h', async () => {
    await seedRsvp(memberId, 'no_go');
    const res = await rsvp('confirmed');
    expect(res.statusCode).toBe(409);
    expect(res.json().error).toBe('RSVP_LOCKED');
  });

  it('03 no_go puede reconsiderar exactamente en T-1h y dentro de la ventana', async () => {
    for (const interval of ['1 hour', '30 minutes']) {
      const id = await createRouteFromNow(interval);
      await seedRsvp(memberId, 'no_go', false, id);
      const res = await rsvp('confirmed', memberId, id);
      expect(res.statusCode).toBe(200);
      expect(res.json().rsvp.status).toBe('confirmed');
    }
  });

  it('04 no_go no puede reconsiderar desde startsAt', async () => {
    const id = await createRouteFromNow('-1 second');
    await seedRsvp(memberId, 'no_go', false, id);
    const res = await rsvp('confirmed', memberId, id);
    expect(res.statusCode).toBe(409);
    expect(res.json().error).toBe('RSVP_LOCKED');
  });

  it('05 cero confirmados no habilita inicio', async () => {
    await seedRsvp(memberId, 'pending');
    const res = await lifecycle('start');
    expect(res.statusCode).toBe(409);
    expect(res.json().error).toBe('ROUTE_NOT_READY');
    expect((await routeRow()).outbound_started_at).toBeNull();
  });

  it('06 pendientes y no_go no bloquean inicio', async () => {
    await seedRsvp(memberId, 'confirmed');
    await seedRsvp(member2Id, 'no_go');
    expect((await arrive()).statusCode).toBe(201);
    const res = await lifecycle('start', coLeaderId);
    expect(res.statusCode).toBe(201);
    expect(res.json().route.outbound_started_at).not.toBeNull();
  });

  it('07 todos los confirmados deben llegar al sitio', async () => {
    await seedRsvp(memberId, 'confirmed');
    await seedRsvp(member2Id, 'confirmed');
    expect((await arrive(memberId)).statusCode).toBe(201);
    expect((await lifecycle('start')).statusCode).toBe(409);
    expect((await arrive(member2Id)).statusCode).toBe(201);
    expect((await lifecycle('start')).statusCode).toBe(201);
  });

  it('08 un nuevo confirmado vuelve a bloquear inicio', async () => {
    await withUserDb(pool, presidentId, (client) =>
      client.query(`UPDATE club_routes SET starts_at = now() + interval '30 minutes' WHERE id = $1`, [routeId])
    );
    await seedRsvp(memberId, 'confirmed', true);
    await seedRsvp(member2Id, 'no_go');
    // Antes de este cambio solo memberId era requerido y ya estaba en sitio.
    expect((await rsvp('confirmed', member2Id)).statusCode).toBe(200);
    const denied = await lifecycle('start');
    expect(denied.statusCode).toBe(409);
    expect(denied.json()).toMatchObject({ confirmed: 2, onSite: 1 });
    expect((await arrive(member2Id)).statusCode).toBe(201);
    expect((await lifecycle('start')).statusCode).toBe(201);
  });

  it('09 un participante no puede modificar a otro ni iniciar como no líder', async () => {
    await seedRsvp(memberId, 'confirmed');
    const res = await app.inject({
      method: 'POST', url: `/v1/routes/${routeId}/arrive`,
      headers: { 'x-user-id': memberId },
      payload: { userId: member2Id, onSiteAt: new Date(0).toISOString() }
    });
    expect(res.statusCode).toBe(201);
    const { rows } = await withUserDb(pool, memberId, (client) =>
      client.query(
        'SELECT user_id, on_site_at FROM club_route_rsvps WHERE route_id = $1 ORDER BY user_id',
        [routeId]
      )
    );
    expect(rows.find((r) => r.user_id === memberId)?.on_site_at).not.toBeNull();
    expect(rows.find((r) => r.user_id === member2Id)).toBeUndefined();
    expect((await lifecycle('start', memberId)).statusCode).toBe(403);
    // Un externo recibe 404: no debe conocer la existencia de la ruta ajena.
    expect((await lifecycle('start', outsiderId)).statusCode).toBe(404);
  });

  it('10 inicio sin todos en sitio devuelve 409', async () => {
    await seedRsvp(memberId, 'confirmed');
    const res = await lifecycle('start');
    expect(res.statusCode).toBe(409);
    expect(res.json().error).toBe('ROUTE_NOT_READY');
  });

  it('11 retorno antes de finalizar ida devuelve 409', async () => {
    await seedRsvp(memberId, 'confirmed', true);
    expect((await lifecycle('start')).statusCode).toBe(201);
    const res = await lifecycle('return');
    expect(res.statusCode).toBe(409);
    expect(res.json().error).toBe('DESTINATION_NOT_REACHED');
    expect((await routeRow()).return_started_at).toBeNull();
  });

  it('12 casa antes del retorno devuelve 409', async () => {
    await seedRsvp(memberId, 'confirmed', true);
    expect((await lifecycle('start')).statusCode).toBe(201);
    expect((await lifecycle('arrive-destination')).statusCode).toBe(201);
    const res = await rsvp('home');
    expect(res.statusCode).toBe(409);
    expect(res.json().error).toBe('RETURN_NOT_STARTED');
  });

  it('13 home es terminal', async () => {
    await seedRsvp(memberId, 'confirmed', true);
    expect((await lifecycle('start')).statusCode).toBe(201);
    expect((await lifecycle('arrive-destination')).statusCode).toBe(201);
    expect((await lifecycle('return')).statusCode).toBe(201);
    expect((await rsvp('home')).statusCode).toBe(200);
    const res = await rsvp('confirmed');
    expect(res.statusCode).toBe(409);
    expect(res.json().error).toBe('INVALID_RSVP_TRANSITION');
    expect(res.json().from).toBe('home');
  });

  it('14 reintentos no duplican ni reinician timestamps', async () => {
    await seedRsvp(memberId, 'confirmed');
    const arrive1 = await arrive();
    const arrive2 = await arrive();
    expect(arrive1.statusCode).toBe(201);
    expect(arrive2.statusCode).toBe(200);
    expect(arrive2.json().rsvp.on_site_at).toBe(arrive1.json().rsvp.on_site_at);
    expect(arrive2.json().changed).toBe(false);

    const started1 = await lifecycle('start');
    const started2 = await lifecycle('start');
    expect(started2.json().route.outbound_started_at).toEqual(started1.json().route.outbound_started_at);
    expect(started2.json().changed).toBe(false);

    const destination1 = await lifecycle('arrive-destination');
    const destination2 = await lifecycle('arrive-destination');
    expect(destination2.json().route.destination_reached_at).toEqual(destination1.json().route.destination_reached_at);

    const return1 = await lifecycle('return');
    const return2 = await lifecycle('return');
    expect(return2.json().route.return_started_at).toEqual(return1.json().route.return_started_at);

    const home1 = await rsvp('home');
    const home2 = await rsvp('home');
    expect(home2.json().rsvp.home_at).toEqual(home1.json().rsvp.home_at);

    const complete1 = await lifecycle('complete');
    const complete2 = await lifecycle('complete');
    expect(complete1.statusCode).toBe(201);
    expect(complete2.statusCode).toBe(200);
    expect(complete2.json().route.completed_at).toEqual(complete1.json().route.completed_at);

    const { rows } = await withUserDb(pool, memberId, (client) =>
      client.query(
        'SELECT count(*)::int AS n FROM club_route_rsvps WHERE route_id = $1 AND user_id = $2',
        [routeId, memberId]
      )
    );
    expect(rows[0].n).toBe(1);
  });

  it('15 una nueva app restaurada lee fase y presencia persistidas en PostgreSQL', async () => {
    await seedRsvp(memberId, 'confirmed');
    await arrive();
    const started = await lifecycle('start');
    const destination = await lifecycle('arrive-destination');
    const returning = await lifecycle('return');
    const reloaded = await buildApp({ db, logger: false });
    try {
      const routes = await reloaded.inject({
        method: 'GET', url: `/v1/groups/${groupId}/routes`, headers: { 'x-user-id': memberId }
      });
      const restored = routes.json().routes.find((r: any) => r.id === routeId);
      expect(restored.outbound_started_at).toEqual(started.json().route.outbound_started_at);
      expect(restored.destination_reached_at).toEqual(destination.json().route.destination_reached_at);
      expect(restored.return_started_at).toEqual(returning.json().route.return_started_at);

      const rsvps = await reloaded.inject({
        method: 'GET', url: `/v1/routes/${routeId}/rsvps`, headers: { 'x-user-id': memberId }
      });
      expect(rsvps.json().rsvps[0].on_site_at).not.toBeNull();
    } finally {
      await reloaded.close();
    }
  });
  // Caso 16: suites existentes reales BLE/chat/SOS/PTT ejecutadas como regresión separada.
});
