import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
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

describe('RLS de rutas de club (0017) — aislamiento cross-tenant', () => {
  let db: DbExecutor;
  let pool: ReturnType<typeof createPgPool>;

  const leaderA = randomUUID();
  const memberA = randomUUID();
  const leaderB = randomUUID();
  const groupA = randomUUID();
  const groupB = randomUUID();
  let routeA = '';

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
       VALUES ($1, $2, 'VE', 'leader', 'Leader A'),
              ($3, $4, 'VE', 'member', 'Member A'),
              ($5, $6, 'VE', 'leader', 'Leader B')`,
      [
        leaderA, `rls-leader-a-${leaderA}@rbs.test`,
        memberA, `rls-member-a-${memberA}@rbs.test`,
        leaderB, `rls-leader-b-${leaderB}@rbs.test`
      ]
    );
    await db.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader)
       VALUES ($1, 'VE', 'RLS Club A', 'rlscluba', decode('0000000000000000', 'hex'), $2),
              ($3, 'VE', 'RLS Club B', 'rlsclubb', decode('0000000000000000', 'hex'), $4)`,
      [groupA, leaderA, groupB, leaderB]
    );
    await db.query(
      `INSERT INTO group_leaders (group_id, user_id, is_principal)
       VALUES ($1, $2, true), ($3, $4, true)`,
      [groupA, leaderA, groupB, leaderB]
    );
    await db.query(
      `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state, accepted_at)
       VALUES ($1, $2, $3, 'full', 'accepted', now()),
              ($4, $5, $6, 'full', 'accepted', now())`,
      [randomUUID(), groupA, memberA, randomUUID(), groupB, leaderB]
    );
  });

  afterAll(async () => {
    await pool.end();
  });

  it('el presidente de A crea una ruta en A', async () => {
    const res = await withUserDb(pool, leaderA, (client) =>
      client.query(
        `INSERT INTO club_routes (group_id, created_by, title, status)
         VALUES ($1, $2, 'Ruta RLS', 'published') RETURNING id`,
        [groupA, leaderA]
      )
    );
    expect(res.rows).toHaveLength(1);
    routeA = res.rows[0].id as string;
    expect(routeA).toBeTruthy();
  });

  it('un miembro aceptado de A ve la ruta de A', async () => {
    const res = await withUserDb(pool, memberA, (client) =>
      client.query('SELECT id FROM club_routes')
    );
    expect(res.rows.map((r) => r.id)).toContain(routeA);
  });

  it('un lider de B NO ve la ruta de A (cross-tenant)', async () => {
    const res = await withUserDb(pool, leaderB, (client) =>
      client.query('SELECT id FROM club_routes')
    );
    expect(res.rows).toHaveLength(0);
  });

  it('un miembro de A NO puede crear rutas en A', async () => {
    await expect(
      withUserDb(pool, memberA, (client) =>
        client.query(
          `INSERT INTO club_routes (group_id, created_by, title)
           VALUES ($1, $2, 'Intento de miembro')`,
          [groupA, memberA]
        )
      )
    ).rejects.toThrow(/row-level security/i);
  });

  it('un miembro de A NO puede escribir el RSVP de otro', async () => {
    await expect(
      withUserDb(pool, memberA, (client) =>
        client.query(
          `INSERT INTO club_route_rsvps (route_id, user_id, status)
           VALUES ($1, $2, 'confirmed')`,
          [routeA, leaderA]
        )
      )
    ).rejects.toThrow(/row-level security/i);
  });

  it('un miembro de A escribe SOLO su propio RSVP', async () => {
    const res = await withUserDb(pool, memberA, (client) =>
      client.query(
        `INSERT INTO club_route_rsvps (route_id, user_id, status)
         VALUES ($1, $2, 'confirmed') RETURNING user_id, status`,
        [routeA, memberA]
      )
    );
    expect(res.rows[0]).toMatchObject({ user_id: memberA, status: 'confirmed' });
  });

  it('la identidad no queda residual en la conexion del pool', async () => {
    const res = await db.query('SELECT id FROM club_routes');
    expect(res.rows).toHaveLength(0);
  });
});
