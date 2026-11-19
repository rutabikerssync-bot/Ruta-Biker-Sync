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

describe('ACL de detalle de club (GET /v1/groups/:id)', () => {
  let app: FastifyInstance;
  let db: DbExecutor;

  const leaderId = randomUUID();
  const memberId = randomUUID();
  const outsiderId = randomUUID();
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
       VALUES ($1, $2, 'VE', 'leader', $3),
              ($4, $5, 'VE', 'member', $6),
              ($7, $8, 'VE', 'member', $9)`,
      [
        leaderId, `acl-leader-${leaderId}@rbs.test`, 'ACL Leader',
        memberId, `acl-member-${memberId}@rbs.test`, 'ACL Member',
        outsiderId, `acl-outsider-${outsiderId}@rbs.test`, 'ACL Outsider'
      ]
    );
    await db.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader)
       VALUES ($1, 'VE', 'ACL Club', 'aclclub', decode('0000000000000000', 'hex'), $2)`,
      [groupId, leaderId]
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

    app = await buildApp({ db, logger: false });
  });

  afterAll(async () => {
    await app.close();
    await db.end();
  });

  it('permite al presidente leer el detalle de su club', async () => {
    const res = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}`,
      headers: { 'x-user-id': leaderId }
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().id).toBe(groupId);
  });

  it('permite a un miembro aceptado leer el detalle de su club', async () => {
    const res = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}`,
      headers: { 'x-user-id': memberId }
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().id).toBe(groupId);
  });

  it('rechaza con 403 a un usuario ajeno al club', async () => {
    const res = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}`,
      headers: { 'x-user-id': outsiderId }
    });
    expect(res.statusCode).toBe(403);
    expect(res.json().error).toBe('FORBIDDEN');
  });
});
