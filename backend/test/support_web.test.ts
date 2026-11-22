import { describe, expect, it } from 'vitest';
import { newDb } from 'pg-mem';
import { buildApp } from '../src/app.js';

async function makeApp() {
  const mem = newDb();
  const pgAdapter = mem.adapters.createPg();
  const pool = new pgAdapter.Pool();
  const app = await buildApp({
    db: pool,
    logger: false,
    env: { ...process.env, RBS_PILOT_ALLOWED_KEYS: 'TEST-01-piloto-rbs-2026' }
  });
  return { app, pool };
}

describe('Servicio web de soporte', () => {
  it('sirve /soporte y sus assets aunque el gate de piloto esté activo', async () => {
    const { app, pool } = await makeApp();

    const page = await app.inject({ method: 'GET', url: '/soporte' });
    expect(page.statusCode).toBe(200);
    expect(page.headers['content-type']).toContain('text/html');
    expect(page.body).toContain('id="supportForm"');
    expect(page.body).toContain('id="deviceTable"');
    expect(page.body).toContain('soporte@rutabikersync.com');

    const script = await app.inject({ method: 'GET', url: '/js/soporte.js' });
    expect(script.statusCode).toBe(200);
    expect(script.body).toContain('/v1/support/report');
    expect(script.body).toContain('/v1/support/devices/light');

    const css = await app.inject({ method: 'GET', url: '/css/soporte.css' });
    expect(css.statusCode).toBe(200);

    const landing = await app.inject({ method: 'GET', url: '/' });
    expect(landing.body).toContain('href="/soporte"');
    expect(landing.body).not.toContain('href="#safety"');

    await app.close();
    await pool.end();
  });
});
