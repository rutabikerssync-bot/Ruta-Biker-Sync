import { beforeAll, afterAll, describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import { verifyAdminAccess } from '../src/lib/admin.js';
import type { FastifyInstance } from 'fastify';

const TEST_ADMIN_KEY = 'test-admin-key-rotada-2026';

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
  return { db: pool, usingRealPostgres: true };
}

describe('Fase 8 — Sistema de Administración, Soporte y Analítica', () => {
  it('valida autenticación admin por ADMIN_API_KEY y falla cerrado sin variable de entorno', () => {
    expect(verifyAdminAccess('cualquier-clave', undefined)).toBe(false);
    expect(verifyAdminAccess('', '')).toBe(false);
    expect(verifyAdminAccess('clave-incorrecta', TEST_ADMIN_KEY)).toBe(false);
    expect(verifyAdminAccess(undefined, TEST_ADMIN_KEY)).toBe(false);
    expect(verifyAdminAccess('mi-clave-custom', 'mi-clave-custom')).toBe(true);
  });

  it('el panel admin HTML/JS no precarga ninguna clave secreta', () => {
    const files = [
      join(process.cwd(), 'public', 'admin.html'),
      join(process.cwd(), 'public', 'admin', 'index.html'),
      join(process.cwd(), 'public', 'js', 'api.js'),
      join(process.cwd(), '..', 'admin-dashboard', 'index.html'),
      join(process.cwd(), '..', 'admin-dashboard', 'js', 'api.js')
    ];
    for (const file of files) {
      const content = readFileSync(file, 'utf8');
      expect(content).not.toContain('rbs-dev-admin-secret-key-2026');
    }
  });

  describe('Endpoints /v1/admin (Autenticación y Operaciones)', () => {
    let app: FastifyInstance;
    let db: DbExecutor;
    let isDbOnline = false;

    beforeAll(async () => {
      try {
        const dbSetup = await createTestDb();
        db = dbSetup.db;
        isDbOnline = true;
        app = await buildApp({ db, env: { ...process.env, ADMIN_API_KEY: TEST_ADMIN_KEY } });
        await app.ready();
      } catch (err: any) {
        console.warn('[phase8_admin_test] PostgreSQL test DB offline, testing with mock/fallback:', err?.message || err);
        // Crear mock executor para pruebas sin docker local
        const mockDb: DbExecutor = {
          async query(text: string, values?: unknown[]) {
            if (text.includes('SELECT') && text.includes('FROM users')) {
              return { rows: [{ total: '15', active: '14', suspended: '1' }], command: '', rowCount: 1, oid: 0, fields: [] };
            }
            if (text.includes('rank_progress')) {
              return { rows: [{ novato: '5', rider: '4', veterano: '3', elite: '2', leyenda: '1' }], command: '', rowCount: 1, oid: 0, fields: [] };
            }
            if (text.includes('FROM groups')) {
              return { rows: [{ total: '4', active: '4', suspended: '0', tier_rc: '2', tier_mg: '1', tier_gang: '1', tier_mc: '0' }], command: '', rowCount: 1, oid: 0, fields: [] };
            }
            if (text.includes('FROM rides')) {
              return { rows: [{ total: '12', completed: '10', active: '1', planned: '1' }], command: '', rowCount: 1, oid: 0, fields: [] };
            }
            if (text.includes('emergency_access_log')) {
              return { rows: [{ total_accesses: '2', sos_reason: '2', leader_reason: '0' }], command: '', rowCount: 1, oid: 0, fields: [] };
            }
            if (text.includes('ride_mesh_metrics')) {
              return { rows: [{ metrics_count: '6', participants_sum: '24' }], command: '', rowCount: 1, oid: 0, fields: [] };
            }
            if (text.includes('subscriptions')) {
              return { rows: [{ active_leaders: '4', active_members: '28', in_grace: '1' }], command: '', rowCount: 1, oid: 0, fields: [] };
            }
            if (text.includes('support_tickets')) {
              return { rows: [{ id: 'test-ticket-1', subject: 'Problema BLE', status: 'open' }], command: '', rowCount: 1, oid: 0, fields: [] };
            }
            return { rows: [], command: '', rowCount: 0, oid: 0, fields: [] };
          },
          async end() {}
        };
        db = mockDb;
        app = await buildApp({ db, env: { ...process.env, ADMIN_API_KEY: TEST_ADMIN_KEY } });
        await app.ready();
      }
    });

    afterAll(async () => {
      if (app) await app.close();
      if (db && isDbOnline) await db.end();
    });

    it('falla cerrado (HTTP 401) cuando ADMIN_API_KEY no está configurada en el entorno', async () => {
      const appWithoutEnv = await buildApp({ db, env: {} });
      await appWithoutEnv.ready();
      try {
        const res = await appWithoutEnv.inject({
          method: 'GET',
          url: '/v1/admin/analytics/overview',
          headers: {
            'x-admin-key': TEST_ADMIN_KEY
          }
        });
        expect(res.statusCode).toBe(401);
        expect(res.json()).toHaveProperty('error', 'UNAUTHORIZED_ADMIN');
      } finally {
        await appWithoutEnv.close();
      }
    });

    it('rechaza solicitudes no autorizadas sin clave de admin (HTTP 401)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: '/v1/admin/analytics/overview'
      });
      expect(res.statusCode).toBe(401);
      expect(res.json()).toHaveProperty('error', 'UNAUTHORIZED_ADMIN');
    });

    it('rechaza solicitudes con clave incorrecta (HTTP 401)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: '/v1/admin/analytics/overview',
        headers: {
          'x-admin-key': 'clave_falsa_123'
        }
      });
      expect(res.statusCode).toBe(401);
    });

    it('retorna el resumen analítico con la clave autorizada (HTTP 200)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: '/v1/admin/analytics/overview',
        headers: {
          'x-admin-key': TEST_ADMIN_KEY
        }
      });
      expect(res.statusCode).toBe(200);
      const data = res.json();
      expect(data).toHaveProperty('users');
      expect(data).toHaveProperty('groups');
      expect(data).toHaveProperty('rides');
      expect(data).toHaveProperty('safetySos');
      expect(data).toHaveProperty('meshOffline');
      expect(data).toHaveProperty('subscriptions');
      expect(data.subscriptions).toHaveProperty('monthlyEstimatedRevenueUsd');
    });

    it('permite listar clubes con filtros de búsqueda (HTTP 200)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: '/v1/admin/groups?search=halcones',
        headers: {
          'x-admin-key': TEST_ADMIN_KEY
        }
      });
      expect(res.statusCode).toBe(200);
      expect(res.json()).toHaveProperty('groups');
      expect(res.json()).toHaveProperty('total');
    });

    it('permite consultar la bandeja de reportes de convivencia escalados (HTTP 200)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: '/v1/admin/support/reports',
        headers: {
          'x-admin-key': TEST_ADMIN_KEY
        }
      });
      expect(res.statusCode).toBe(200);
      expect(res.json()).toHaveProperty('reports');
    });

    it('permite consultar la lista de tickets de soporte (HTTP 200)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: '/v1/admin/support/tickets',
        headers: {
          'x-admin-key': TEST_ADMIN_KEY
        }
      });
      expect(res.statusCode).toBe(200);
      expect(res.json()).toHaveProperty('tickets');
    });

    it('permite consultar la auditoría forense de rescates SOS (HTTP 200)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: '/v1/admin/audit/sos',
        headers: {
          'x-admin-key': TEST_ADMIN_KEY
        }
      });
      expect(res.statusCode).toBe(200);
      expect(res.json()).toHaveProperty('logs');
    });
  });
});
