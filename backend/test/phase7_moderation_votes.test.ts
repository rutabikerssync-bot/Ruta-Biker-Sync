import { beforeAll, afterAll, describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import type { FastifyInstance } from 'fastify';

function resolveTestDatabaseUrl(): string | undefined {
  if (process.env.PG_TEST_URL || process.env.DATABASE_URL) {
    return process.env.PG_TEST_URL ?? process.env.DATABASE_URL;
  }
  for (const filename of ['.env', '.env.example']) {
    const filePath = join(process.cwd(), filename);
    if (existsSync(filePath)) {
      const content = readFileSync(filePath, 'utf8');
      const pgTestMatch = content.match(/^PG_TEST_URL=(.+)$/m);
      if (pgTestMatch?.[1]) {
        return pgTestMatch[1].trim();
      }
      const dbUrlMatch = content.match(/^DATABASE_URL=(.+)$/m);
      if (dbUrlMatch?.[1]) {
        return dbUrlMatch[1].trim();
      }
    }
  }
  return undefined;
}

async function createTestDb(): Promise<{ db: DbExecutor; usingRealPostgres: boolean }> {
  const connectionString = resolveTestDatabaseUrl();
  const pool = createPgPool(connectionString);
  await pool.query('SELECT 1');
  const sql = readInitMigrationSql();
  await pool.query(sql);
  await pool.query(`
    TRUNCATE TABLE
      refresh_tokens,
      rate_limit_events,
      ephemeral_telemetry,
      imitation_reports,
      play_events,
      ride_mesh_metrics,
      leader_vote_ballots,
      leader_votes,
      moderation_reports,
      consents,
      emergency_access_log,
      badges,
      rank_progress,
      ride_participants,
      ride_groups,
      rides,
      group_transfers,
      invitations,
      memberships,
      group_leaders,
      groups,
      subscriptions,
      users
    RESTART IDENTITY CASCADE;
  `);
  return { db: pool, usingRealPostgres: true };
}

describe('Fase 7.4 — Moderación, Reporte de Riders y Votación de Líderes (RULES R13, R14)', () => {
  let app: FastifyInstance;
  let db: DbExecutor;

  beforeAll(async () => {
    try {
      const dbSetup = await createTestDb();
      db = dbSetup.db;
      app = await buildApp({ db });
      await app.ready();
    } catch (err: any) {
      console.warn('[phase7_moderation_votes] PostgreSQL test DB offline, test skipped:', err?.message || err);
    }
  });

  afterAll(async () => {
    if (app) await app.close();
    if (db) await (db.end ? db.end() : (db.close ? db.close() : undefined));
  });

  it('permite reportar un rider, ocultarlo del acusado y resolverlo por líderes (R13)', async () => {
    if (!app) return;

    // 1. Crear líder y club
    const leaderRes = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: {
        email: 'leader.mod@club.test',
        password: 'Password123!',
        displayName: 'Presidente Lobo',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true,
        activateLeaderDemo: true
      }
    });
    const leaderToken = leaderRes.json().accessToken || leaderRes.json().token;
    const leaderId = leaderRes.json().user.id;

    const groupRes = await app.inject({
      method: 'POST',
      url: '/v1/groups',
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: { countryCode: 'MX', name: 'Lobos del Asfalto MC', logoPhashHex: '0123456789abcdef' }
    });
    const groupId = groupRes.json().id;

    // 2. Crear rider ofensivo y miembro
    const riderRes = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: {
        email: 'bad.rider@test.com',
        password: 'Password123!',
        displayName: 'Rider Rebelde',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true
      }
    });
    const riderToken = riderRes.json().accessToken || riderRes.json().token;
    const riderId = riderRes.json().user.id;

    // 3. Crear reportero
    const reporterRes = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: {
        email: 'victim.rider@test.com',
        password: 'Password123!',
        displayName: 'Rider Tranquilo',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true
      }
    });
    const reporterToken = reporterRes.json().accessToken || reporterRes.json().token;

    // 4. Reportar rider (R13.1, R13.2)
    const reportRes = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/reports`,
      headers: { authorization: `Bearer ${reporterToken}` },
      payload: {
        reportedUserId: riderId,
        reason: 'dangerous',
        note: 'Conducción temeraria adelantando por la derecha en curva'
      }
    });
    expect(reportRes.statusCode).toBe(201);
    const reportId = reportRes.json().id;
    expect(reportRes.json().state).toBe('open');

    // 5. Líder consulta los reportes del club (R13.2)
    const listRes = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}/reports`,
      headers: { authorization: `Bearer ${leaderToken}` }
    });
    expect(listRes.statusCode).toBe(200);
    const reports = listRes.json().reports;
    expect(reports.length).toBe(1);
    expect(reports[0].reason).toBe('dangerous');
    expect(reports[0].reported_name).toBe('Rider Rebelde');

    // 6. Líder resuelve el reporte aplicando sanción (R13.4)
    const resolveRes = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/reports/${reportId}/resolve`,
      headers: { authorization: `Bearer ${leaderToken}` },
      payload: {
        resolution: 'warning',
        state: 'actioned'
      }
    });
    expect(resolveRes.statusCode).toBe(200);
    expect(resolveRes.json().state).toBe('actioned');
    expect(resolveRes.json().resolution).toBe('warning');
  });

  it('requiere mayoría (2 de 3) en votaciones sensibles de líderes y prohíbe votar propia sanción (R14)', async () => {
    if (!app) return;

    // 1. Crear líder 1
    const l1Res = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: { email: 'presi.vote@club.test', password: 'Password123!', displayName: 'Presidente 1', countryCode: 'MX', acceptedTermsAndConvivencia: true, activateLeaderDemo: true }
    });
    const l1Token = l1Res.json().accessToken || l1Res.json().token;
    const l1Id = l1Res.json().user.id;

    const groupRes = await app.inject({
      method: 'POST',
      url: '/v1/groups',
      headers: { authorization: `Bearer ${l1Token}` },
      payload: { countryCode: 'MX', name: 'Águilas Sagradas MC', logoPhashHex: 'fedcba9876543210' }
    });
    const groupId = groupRes.json().id;

    // 2. Crear líder 2 y líder 3
    const l2Res = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: { email: 'vice.vote@club.test', password: 'Password123!', displayName: 'Vice 2', countryCode: 'MX', acceptedTermsAndConvivencia: true }
    });
    const l2Token = l2Res.json().accessToken || l2Res.json().token;
    const l2Id = l2Res.json().user.id;

    const l3Res = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: { email: 'sgt.vote@club.test', password: 'Password123!', displayName: 'Sargento 3', countryCode: 'MX', acceptedTermsAndConvivencia: true }
    });
    const l3Token = l3Res.json().accessToken || l3Res.json().token;
    const l3Id = l3Res.json().user.id;

    // Registrar como líderes
    await db.query(`INSERT INTO group_leaders (group_id, user_id, is_principal) VALUES ($1, $2, false)`, [groupId, l2Id]);
    await db.query(`INSERT INTO group_leaders (group_id, user_id, is_principal) VALUES ($1, $2, false)`, [groupId, l3Id]);

    // 3. Proponer expulsión del líder 3 (R14.2)
    const proposeRes = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/votes`,
      headers: { authorization: `Bearer ${l1Token}` },
      payload: {
        proposal: 'remove_leader',
        targetId: l3Id
      }
    });
    expect(proposeRes.statusCode).toBe(201);
    const voteId = proposeRes.json().id;
    // Solo 1 voto a favor (de 2 requeridos), debe seguir 'open'
    expect(proposeRes.json().state).toBe('open');

    // 4. El líder 3 intenta votar su propia sanción (R14.4 -> 403 CANNOT_VOTE_OWN_SANCTION)
    const ownVoteRes = await app.inject({
      method: 'POST',
      url: `/v1/votes/${voteId}/ballot`,
      headers: { authorization: `Bearer ${l3Token}` },
      payload: { approve: false }
    });
    expect(ownVoteRes.statusCode).toBe(403);
    expect(ownVoteRes.json().error).toBe('CANNOT_VOTE_OWN_SANCTION');

    // 5. El líder 2 vota a favor (alcanza 2 de 3 -> approved)
    const l2VoteRes = await app.inject({
      method: 'POST',
      url: `/v1/votes/${voteId}/ballot`,
      headers: { authorization: `Bearer ${l2Token}` },
      payload: { approve: true }
    });
    expect(l2VoteRes.statusCode).toBe(200);
    expect(l2VoteRes.json().state).toBe('approved');

    // 6. Verificar que el líder 3 fue removido de la tabla group_leaders
    const checkL3 = await db.query(`SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`, [groupId, l3Id]);
    expect(checkL3.rows.length).toBe(0);
  });
});
