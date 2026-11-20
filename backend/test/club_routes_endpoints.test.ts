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

describe('Fase 3 �?" endpoints de rutas de club y RSVP idempotente', () => {
  let app: FastifyInstance;
  let db: DbExecutor;
  let pool: ReturnType<typeof createPgPool>;

  const presidentId = randomUUID();
  const coLeaderId = randomUUID();
  const memberId = randomUUID();
  const outsiderId = randomUUID();
  const groupId = randomUUID();
  let routeId = '';

  const futureStartsAt = (hoursFromNow = 24) =>
    new Date(Date.now() + hoursFromNow * 60 * 60 * 1000).toISOString();

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
    await db.query(
      `INSERT INTO users (id, email, country_code, account_type, display_name)
       VALUES ($1, $2, 'VE', 'leader', 'Presidente Ruta'),
              ($3, $4, 'VE', 'leader', 'Colider Ruta'),
              ($5, $6, 'VE', 'member', 'Miembro Ruta'),
              ($7, $8, 'VE', 'member', 'Externo Ruta')`,
      [
        presidentId, `cr-pres-${presidentId}@rbs.test`,
        coLeaderId, `cr-colead-${coLeaderId}@rbs.test`,
        memberId, `cr-mem-${memberId}@rbs.test`,
        outsiderId, `cr-out-${outsiderId}@rbs.test`
      ]
    );
    await db.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader)
       VALUES ($1, 'VE', 'Rutas Club Test', 'rutasclubtest',
               decode('0000000000000000', 'hex'), $2)`,
      [groupId, presidentId]
    );
    await db.query(
      `INSERT INTO group_leaders (group_id, user_id, is_principal)
       VALUES ($1, $2, true),
              ($1, $3, false)`,
      [groupId, presidentId, coLeaderId]
    );
    await db.query(
      `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state, accepted_at)
       VALUES ($1, $2, $3, 'full', 'accepted', now()),
              ($4, $5, $6, 'full', 'accepted', now()),
              ($7, $8, $9, 'full', 'accepted', now())`,
      [
        randomUUID(), groupId, presidentId,
        randomUUID(), groupId, coLeaderId,
        randomUUID(), groupId, memberId
      ]
    );
    app = await buildApp({ db, logger: false });
  });

  afterAll(async () => {
    await app.close();
    await pool.end();
  });

  it('rechaza POST /routes a un miembro normal', async () => {
    const res = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/routes`,
      headers: { 'x-user-id': memberId },
      payload: { title: 'Ruta intentada por miembro', startsAt: futureStartsAt() }
    });
    expect(res.statusCode).toBe(403);
    expect(res.json().error).toBe('LEADER_REQUIRED');
  });

  it('el presidente crea la ruta con status published, startsAt y description', async () => {
    const startsAt = futureStartsAt(48);
    const res = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/routes`,
      headers: { 'x-user-id': presidentId },
      payload: {
        title: 'Ruta de club Fase 3',
        description: 'Concepto de la rodada',
        startsAt
      }
    });
    expect(res.statusCode).toBe(201);
    const route = res.json().route;
    expect(route.status).toBe('published');
    expect(route.created_by).toBe(presidentId);
    expect(route.group_id).toBe(groupId);
    expect(route.description).toBe('Concepto de la rodada');
    expect(new Date(route.starts_at).toISOString()).toBe(new Date(startsAt).toISOString());
    routeId = route.id;
  });

  it('un lider no principal (mesa directiva) puede crear rutas', async () => {
    const res = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/routes`,
      headers: { 'x-user-id': coLeaderId },
      payload: { title: 'Ruta creada por colider', startsAt: futureStartsAt(72) }
    });
    expect(res.statusCode).toBe(201);
    expect(res.json().route.created_by).toBe(coLeaderId);
    expect(res.json().route.description).toBeNull();
  });

  it('GET /groups/:id/routes devuelve description', async () => {
    const res = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}/routes`,
      headers: { 'x-user-id': memberId }
    });
    expect(res.statusCode).toBe(200);
    const route = res.json().routes.find((r: any) => r.id === routeId);
    expect(route).toBeTruthy();
    expect(route.description).toBe('Concepto de la rodada');
  });

  it('un miembro hace RSVP confirmed', async () => {
    const res = await app.inject({
      method: 'POST',
      url: `/v1/routes/${routeId}/rsvp`,
      headers: { 'x-user-id': memberId },
      payload: { status: 'confirmed' }
    });
    expect(res.statusCode).toBe(201);
    expect(res.json().rsvp.status).toBe('confirmed');
    expect(res.json().changed).toBe(true);
  });

  it('repetir confirmed es idempotente: 200, sin duplicar ni mover responded_at', async () => {
    const before = await app.inject({
      method: 'GET',
      url: `/v1/routes/${routeId}/rsvps`,
      headers: { 'x-user-id': presidentId }
    });
    const firstRespondedAt = before.json().rsvps[0].responded_at;

    const res = await app.inject({
      method: 'POST',
      url: `/v1/routes/${routeId}/rsvp`,
      headers: { 'x-user-id': memberId },
      payload: { status: 'confirmed' }
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().changed).toBe(false);
    expect(res.json().rsvp.responded_at).toBe(firstRespondedAt);

    const { rows: countRows } = await withUserDb(pool, memberId, (client) =>
      client.query(
        `SELECT count(*)::int AS n FROM club_route_rsvps WHERE route_id = $1 AND user_id = $2`,
        [routeId, memberId]
      )
    );
    expect(countRows[0].n).toBe(1);
  });

  it('acepta en_route como estado escribible (confirmed -> en_route -> home)', async () => {
    const enRoute = await app.inject({
      method: 'POST',
      url: `/v1/routes/${routeId}/rsvp`,
      headers: { 'x-user-id': memberId },
      payload: { status: 'en_route' }
    });
    expect(enRoute.statusCode).toBe(200);
    expect(enRoute.json().rsvp.status).toBe('en_route');

    const home = await app.inject({
      method: 'POST',
      url: `/v1/routes/${routeId}/rsvp`,
      headers: { 'x-user-id': memberId },
      payload: { status: 'home' }
    });
    expect(home.statusCode).toBe(200);
    expect(home.json().rsvp.status).toBe('home');

    const backToEnRoute = await app.inject({
      method: 'POST',
      url: `/v1/routes/${routeId}/rsvp`,
      headers: { 'x-user-id': memberId },
      payload: { status: 'en_route' }
    });
    expect(backToEnRoute.statusCode).toBe(409);
    expect(backToEnRoute.json().error).toBe('INVALID_RSVP_TRANSITION');
    expect(backToEnRoute.json().from).toBe('home');

    // restaurar a confirmed para no afectar pruebas posteriores que esperan otros flujos? no: home es terminal
    // Para el resto de pruebas que dependen del estado del miembro, creamos un RSVP aislado en otro flujo si hace falta.
  });

  it('un externo no ve rutas ni RSVP (RLS)', async () => {
    const routes = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}/routes`,
      headers: { 'x-user-id': outsiderId }
    });
    expect(routes.statusCode).toBe(200);
    expect(routes.json().routes).toHaveLength(0);

    const rsvps = await app.inject({
      method: 'GET',
      url: `/v1/routes/${routeId}/rsvps`,
      headers: { 'x-user-id': outsiderId }
    });
    expect(rsvps.statusCode).toBe(404);
    expect(rsvps.json().error).toBe('ROUTE_NOT_FOUND');
  });

  it('la mesa directiva ve scope full y el miembro solo la suya mas el resumen', async () => {
    const fullPresident = await app.inject({
      method: 'GET',
      url: `/v1/routes/${routeId}/rsvps`,
      headers: { 'x-user-id': presidentId }
    });
    expect(fullPresident.statusCode).toBe(200);
    expect(fullPresident.json().scope).toBe('full');
    expect(fullPresident.json().rsvps.length).toBeGreaterThanOrEqual(1);

    const fullCoLeader = await app.inject({
      method: 'GET',
      url: `/v1/routes/${routeId}/rsvps`,
      headers: { 'x-user-id': coLeaderId }
    });
    expect(fullCoLeader.statusCode).toBe(200);
    expect(fullCoLeader.json().scope).toBe('full');

    const member = await app.inject({
      method: 'GET',
      url: `/v1/routes/${routeId}/rsvps`,
      headers: { 'x-user-id': memberId }
    });
    expect(member.statusCode).toBe(200);
    expect(member.json().scope).toBe('member');
    expect(member.json().rsvps).toHaveLength(1);
    expect(member.json().rsvps[0].user_id).toBe(memberId);
    expect(member.json().summary.total).toBeGreaterThanOrEqual(1);
  });

  it('un lider no principal puede editar una ruta futura publicada', async () => {
    const newStartsAt = futureStartsAt(96);
    const res = await app.inject({
      method: 'PATCH',
      url: `/v1/routes/${routeId}`,
      headers: { 'x-user-id': coLeaderId },
      payload: {
        title: 'Ruta de club Fase 3 (editada)',
        description: 'Concepto actualizado',
        startsAt: newStartsAt
      }
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().route.title).toBe('Ruta de club Fase 3 (editada)');
    expect(res.json().route.description).toBe('Concepto actualizado');
    expect(new Date(res.json().route.starts_at).toISOString()).toBe(new Date(newStartsAt).toISOString());
  });

  it('rechaza editar ruta a un miembro normal', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: `/v1/routes/${routeId}`,
      headers: { 'x-user-id': memberId },
      payload: { title: 'Intento miembro' }
    });
    expect(res.statusCode).toBe(403);
    expect(res.json().error).toBe('LEADER_REQUIRED');
  });

  it('rechaza editar una ruta pasada (reloj servidor)', async () => {
    const pastRouteId = randomUUID();
    await withUserDb(pool, presidentId, (client) =>
      client.query(
        `INSERT INTO club_routes (id, group_id, created_by, title, starts_at, status)
         VALUES ($1, $2, $3, $4, now() - interval '1 hour', 'published')`,
        [pastRouteId, groupId, presidentId, 'Ruta pasada']
      )
    );
    const res = await app.inject({
      method: 'PATCH',
      url: `/v1/routes/${pastRouteId}`,
      headers: { 'x-user-id': presidentId },
      payload: { title: 'No debe editarse' }
    });
    expect(res.statusCode).toBe(409);
    expect(res.json().error).toBe('ROUTE_ALREADY_STARTED');
  });

  it('rechaza actualizar startsAt a una fecha pasada', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: `/v1/routes/${routeId}`,
      headers: { 'x-user-id': presidentId },
      payload: { startsAt: new Date(Date.now() - 3600_000).toISOString() }
    });
    expect(res.statusCode).toBe(409);
    expect(res.json().error).toBe('STARTS_AT_MUST_BE_FUTURE');
  });

  it('rechaza una transicion invalida home -> confirmed', async () => {
    // el miembro ya quedó en 'home' en la prueba de en_route; este caso valida la terminalidad
    const back = await app.inject({
      method: 'POST',
      url: `/v1/routes/${routeId}/rsvp`,
      headers: { 'x-user-id': memberId },
      payload: { status: 'confirmed' }
    });
    expect(back.statusCode).toBe(409);
    expect(back.json().error).toBe('INVALID_RSVP_TRANSITION');
    expect(back.json().from).toBe('home');
  });
});
