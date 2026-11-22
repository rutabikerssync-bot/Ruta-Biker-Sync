import { describe, expect, it } from 'vitest';
import { newDb } from 'pg-mem';
import { buildApp } from '../src/app.js';

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
});
