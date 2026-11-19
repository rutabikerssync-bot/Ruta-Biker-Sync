import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';

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

describe('Versionado del perfil de club y respuesta 304', () => {
  let app: FastifyInstance;
  let db: DbExecutor;

  const presidentId = randomUUID();
  const memberId = randomUUID();
  const groupId = randomUUID();

  beforeAll(async () => {
    const pool = createPgPool(resolveTestDatabaseUrl());
    await pool.query('SELECT 1');
    db = pool;
    await db.query(readInitMigrationSql());
    await db.query(`
      TRUNCATE TABLE
        refresh_tokens, rate_limit_events, ephemeral_telemetry, imitation_reports,
        play_events, ride_mesh_metrics, leader_vote_ballots, leader_votes,
        moderation_reports, consents, emergency_access_log, badges, rank_progress,
        ride_participants, ride_groups, rides, group_transfers, invitations,
        memberships, group_leaders, groups, subscriptions, users
      RESTART IDENTITY CASCADE
    `);
    await db.query(
      `INSERT INTO users (id, email, country_code, account_type, display_name)
       VALUES ($1, $2, 'VE', 'leader', 'Presidente'),
              ($3, $4, 'VE', 'member', 'Miembro')`,
      [presidentId, `pv-pres-${presidentId}@rbs.test`, memberId, `pv-mem-${memberId}@rbs.test`]
    );
    await db.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader)
       VALUES ($1, 'VE', 'Perfil Versionado', 'perfilversionado',
               decode('0000000000000000', 'hex'), $2)`,
      [groupId, presidentId]
    );
    await db.query(
      `INSERT INTO group_leaders (group_id, user_id, is_principal)
       VALUES ($1, $2, true)`,
      [groupId, presidentId]
    );
    await db.query(
      `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state, accepted_at)
       VALUES ($1, $2, $3, 'full', 'accepted', now())`,
      [randomUUID(), groupId, memberId]
    );
    app = await buildApp({ db, logger: false });
  });

  afterAll(async () => {
    await app.close();
    await db.end();
  });

  it('rechaza el PUT a un miembro normal', async () => {
    const res = await app.inject({
      method: 'PUT',
      url: `/v1/groups/${groupId}/profile`,
      headers: { 'x-user-id': memberId },
      payload: { motto: 'Intento de miembro' }
    });
    expect(res.statusCode).toBe(403);
    expect(res.json().error).toBe('LEADER_REQUIRED');
  });

  it('el presidente actualiza perfil, incrementa versión y llena el espejo', async () => {
    const res = await app.inject({
      method: 'PUT',
      url: `/v1/groups/${groupId}/profile`,
      headers: { 'x-user-id': presidentId },
      payload: { motto: 'Nuevo lema', profile: { motto: 'Nuevo lema' } }
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().group.profileVersion).toBe(2);

    const row = await db.query(
      `SELECT profile_version, profile_updated_by, club_profile, motto FROM groups WHERE id = $1`,
      [groupId]
    );
    expect(row.rows[0].profile_version).toBe(2);
    expect(row.rows[0].profile_updated_by).toBe(presidentId);
    expect(row.rows[0].motto).toBe('Nuevo lema');
    expect((row.rows[0].club_profile as { motto?: string }).motto).toBe('Nuevo lema');
  });

  it('devuelve 304 cuando since es la versión actual', async () => {
    const res = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}?since=2`,
      headers: { 'x-user-id': presidentId }
    });
    expect(res.statusCode).toBe(304);
  });

  it('devuelve 200 con datos nuevos cuando since es una versión anterior', async () => {
    const res = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}?since=1`,
      headers: { 'x-user-id': presidentId }
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().profileVersion).toBe(2);
    expect(res.json().motto).toBe('Nuevo lema');
  });
});
