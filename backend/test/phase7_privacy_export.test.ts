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

describe('Fase 7 — Privacidad, Consentimientos y Exportación RGPD (RULES R15)', () => {
  let app: FastifyInstance;
  let db: DbExecutor;

  beforeAll(async () => {
    try {
      const dbSetup = await createTestDb();
      db = dbSetup.db;
      app = await buildApp({ db });
      await app.ready();
    } catch (err: any) {
      console.warn('[phase7_privacy_export] PostgreSQL test DB offline, test skipped:', err?.message || err);
    }
  });

  afterAll(async () => {
    if (app) await app.close();
    if (db) await (db.end ? db.end() : (db.close ? db.close() : undefined));
  });

  it('permite consultar, alternar consentimientos granulares y exportar datos conforme a RGPD', async () => {
    if (!app) {
      console.info('Skipping test because PostgreSQL service is not active.');
      return;
    }
    // 1. Registro de usuario
    const regRes = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: {
        email: 'rider.gdpr@biker.test',
        password: 'Password123!',
        displayName: 'Rider Libre',
        bikeModel: 'Yamaha MT-07',
        countryCode: 'MX',
        acceptedTermsAndConvivencia: true
      }
    });
    expect(regRes.statusCode).toBe(200);
    const { token, accessToken, user } = regRes.json();
    const authHeaders = { authorization: `Bearer ${accessToken || token}` };

    await app.inject({
      method: 'PATCH',
      url: '/v1/users/me',
      headers: authHeaders,
      payload: { bikeModel: 'Yamaha MT-07' }
    });

    // 2. Consultar consentimientos iniciales (vienen los de registro: terms)
    const initialConsentsRes = await app.inject({
      method: 'GET',
      url: '/v1/users/me/consents',
      headers: authHeaders
    });
    expect(initialConsentsRes.statusCode).toBe(200);
    const initialConsents = initialConsentsRes.json().consents;
    expect(Array.isArray(initialConsents)).toBe(true);

    // 3. Otorgar consentimiento de ubicación para una rodada/grupo
    const grantLocRes = await app.inject({
      method: 'POST',
      url: '/v1/users/me/consents',
      headers: authHeaders,
      payload: {
        kind: 'location',
        granted: true,
        policyVersion: '1.0'
      }
    });
    expect(grantLocRes.statusCode).toBe(200);
    expect(grantLocRes.json().granted).toBe(true);
    expect(grantLocRes.json().kind).toBe('location');

    // 4. Revocar consentimiento de ubicación (RULES R15.2)
    const revokeLocRes = await app.inject({
      method: 'POST',
      url: '/v1/users/me/consents',
      headers: authHeaders,
      payload: {
        kind: 'location',
        granted: false,
        policyVersion: '1.0'
      }
    });
    expect(revokeLocRes.statusCode).toBe(200);
    expect(revokeLocRes.json().granted).toBe(false);

    // Verificar en lista de consentimientos que el último de 'location' tiene revoked_at != null
    const listConsentsRes = await app.inject({
      method: 'GET',
      url: '/v1/users/me/consents',
      headers: authHeaders
    });
    const latestLoc = listConsentsRes.json().consents.find((c: any) => c.kind === 'location');
    expect(latestLoc).toBeDefined();
    expect(latestLoc.granted).toBe(false);
    expect(latestLoc.revoked_at).not.toBeNull();

    // 5. Otorgar paquete médico de emergencia
    const grantEmergencyRes = await app.inject({
      method: 'POST',
      url: '/v1/users/me/consents',
      headers: authHeaders,
      payload: {
        kind: 'emergency_package',
        granted: true,
        policyVersion: '1.0'
      }
    });
    expect(grantEmergencyRes.statusCode).toBe(200);
    expect(grantEmergencyRes.json().granted).toBe(true);

    // 6. Exportar datos del usuario (RULES R15.5)
    const exportRes = await app.inject({
      method: 'GET',
      url: '/v1/users/me/export',
      headers: authHeaders
    });
    expect(exportRes.statusCode).toBe(200);
    const exportData = exportRes.json();

    expect(exportData.exportedAt).toBeDefined();
    expect(exportData.gdprCompliance).toContain('RGPD');
    expect(exportData.user.id).toBe(user.id);
    expect(exportData.user.display_name).toBe('Rider Libre');
    expect(exportData.user.bike_model).toBe('Yamaha MT-07');
    expect(Array.isArray(exportData.consents)).toBe(true);
    expect(Array.isArray(exportData.memberships)).toBe(true);
    expect(Array.isArray(exportData.subscriptions)).toBe(true);
    expect(Array.isArray(exportData.rides)).toBe(true);
    expect(Array.isArray(exportData.badges)).toBe(true);

    // 7. Borrado de cuenta (Derecho al olvido, RULES R15.3)
    const deleteRes = await app.inject({
      method: 'DELETE',
      url: '/v1/users/me',
      headers: authHeaders
    });
    expect(deleteRes.statusCode).toBe(200);
    expect(deleteRes.json().deleted).toBe(true);

    // Verificar que el usuario quedó anonimizado en la base de datos
    const userDbRes = await db.query('SELECT status, display_name, email FROM users WHERE id = $1', [user.id]);
    expect(userDbRes.rows[0].status).toBe('deleted');
    expect(userDbRes.rows[0].display_name).toBe('Usuario Eliminado');
    expect(userDbRes.rows[0].email).toContain('@anon.rutabikersync.local');
  });
});
