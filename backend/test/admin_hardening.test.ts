import { describe, expect, it } from 'vitest';
import { newDb } from 'pg-mem';
import { buildApp } from '../src/app.js';
import type { DbExecutor } from '../src/db/pool.js';

const TEST_ADMIN_KEY = 'admin-hardening-test-key-1234567890';

async function makeApp() {
  const mem = newDb();
  const pgAdapter = mem.adapters.createPg();
  const pool = new pgAdapter.Pool();
  const app = await buildApp({ db: pool, logger: false, env: process.env });
  return { app, pool };
}

describe('Backoffice sin secretos ni datos demo', () => {
  it('sirve el panel real y no expone claves ni fallbacks', async () => {
    const { app, pool } = await makeApp();

    const page = await app.inject({ method: 'GET', url: '/admin' });
    expect(page.statusCode).toBe(200);
    expect(page.body).toContain('tab-devices');
    expect(page.body).not.toContain('badge-escalated-count">2');

    const api = await app.inject({ method: 'GET', url: '/js/api.js' });
    expect(api.statusCode).toBe(200);
    expect(api.body).toContain('sessionStorage');
    expect(api.body).toContain('/v1/support/devices');
    expect(api.body).not.toContain('rbs-dev-admin-secret-key-2026');
    expect(api.body).not.toContain('fetchWithFallback');
    expect(api.body).not.toContain('aguila@halconesmc.mx');

    const ui = await app.inject({ method: 'GET', url: '/js/admin.js' });
    expect(ui.statusCode).toBe(200);
    expect(ui.body).toContain('openPromptModal');
    expect(ui.body).toContain('loadDeviceTelemetry');
    expect(ui.body).not.toContain('prompt(');
    expect(ui.body).not.toContain('alert(');
    expect(ui.body).not.toContain('Modo de Demostración');

    await app.close();
    await pool.end();
  });

  it('devuelve listados vacíos y métricas en cero con la base vacía', async () => {
    const emptyDb = {
      async query() {
        return { rows: [], command: '', rowCount: 0, oid: 0, fields: [] };
      },
      async end() {}
    } as unknown as DbExecutor;

    const app = await buildApp({
      db: emptyDb,
      logger: false,
      env: { ...process.env, ADMIN_API_KEY: TEST_ADMIN_KEY, RBS_PILOT_MODE: 'false' }
    });
    const headers = { 'x-admin-key': TEST_ADMIN_KEY };

    const emptyLists: Array<[string, string]> = [
      ['/v1/admin/users', 'users'],
      ['/v1/admin/groups', 'groups'],
      ['/v1/admin/support/reports', 'reports'],
      ['/v1/admin/support/tickets', 'tickets'],
      ['/v1/admin/audit/sos', 'logs']
    ];
    for (const [url, key] of emptyLists) {
      const res = await app.inject({ method: 'GET', url, headers });
      expect(res.statusCode).toBe(200);
      expect(res.json()[key]).toEqual([]);
    }

    const overview = await app.inject({ method: 'GET', url: '/v1/admin/analytics/overview', headers });
    expect(overview.statusCode).toBe(200);
    const data = overview.json();
    expect(data.users.total).toBe(0);
    expect(data.groups.total).toBe(0);
    expect(data.rides.total).toBe(0);
    expect(data.safetySos.totalAlerts).toBe(0);
    expect(data.meshOffline.ridesOfflineCount).toBe(0);
    for (const value of Object.values(data.users.ranks)) expect(value).toBe(0);
    for (const value of Object.values(data.groups.byTier)) expect(value).toBe(0);

    await app.close();
  });
});
