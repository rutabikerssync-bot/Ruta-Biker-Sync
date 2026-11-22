import { describe, expect, it } from 'vitest';
import { newDb } from 'pg-mem';
import { buildApp } from '../src/app.js';
import {
  isPilotKeyAllowed,
  isPilotOriginAllowed,
  parsePilotConfig,
  pilotKeyFingerprint
} from '../src/lib/pilotAccess.js';

const PILOT_KEY = 'TECNO-01-piloto-rbs-2026';
const OTHER_KEY = 'REDMI-01-piloto-rbs-2026';

async function makeApp(envOverride: NodeJS.ProcessEnv = {}) {
  const mem = newDb();
  const pgAdapter = mem.adapters.createPg();
  const pool = new pgAdapter.Pool();
  const app = await buildApp({
    db: pool,
    logger: false,
    env: { ...process.env, RBS_PILOT_ALLOWED_KEYS: PILOT_KEY, ...envOverride }
  });
  return { app, pool };
}

describe('Gate de acceso del piloto', () => {
  it('falla cerrado si RBS_PILOT_MODE=true sin claves', () => {
    expect(() => parsePilotConfig({ RBS_PILOT_MODE: 'true' })).toThrow(
      /RBS_PILOT_ALLOWED_KEYS/
    );
  });

  it('hash de clave en comparación constante y fingerprints distintos', () => {
    const config = parsePilotConfig({ RBS_PILOT_ALLOWED_KEYS: `${PILOT_KEY},${OTHER_KEY}` });
    expect(isPilotKeyAllowed(config, PILOT_KEY)).toBe(true);
    expect(isPilotKeyAllowed(config, OTHER_KEY)).toBe(true);
    expect(isPilotKeyAllowed(config, 'CLAVE-NO-AUTORIZADA-1234')).toBe(false);
    expect(pilotKeyFingerprint(PILOT_KEY)).not.toBe(pilotKeyFingerprint(OTHER_KEY));
    expect(pilotKeyFingerprint(PILOT_KEY)).toHaveLength(12);
  });

  it('revoca explícitamente una clave aunque siga en la lista blanca', () => {
    const config = parsePilotConfig({
      RBS_PILOT_ALLOWED_KEYS: PILOT_KEY,
      RBS_PILOT_REVOKED_KEYS: PILOT_KEY
    });
    expect(isPilotKeyAllowed(config, PILOT_KEY)).toBe(false);
  });

  it('solo admite orígenes HTTPS de quick tunnel o los configurados', () => {
    const config = parsePilotConfig({ RBS_PILOT_ALLOWED_KEYS: PILOT_KEY });
    expect(isPilotOriginAllowed(config, 'https://abc-1234-5678.trycloudflare.com')).toBe(true);
    expect(isPilotOriginAllowed(config, 'http://127.0.0.1:3000')).toBe(true);
    expect(isPilotOriginAllowed(config, 'https://pagina-maliciosa.example')).toBe(false);
    expect(isPilotOriginAllowed(config, undefined)).toBe(true);
  });

  it('exige clave en /v1/* pero no en /health ni en el formulario público', async () => {
    const { app, pool } = await makeApp();

    const health = await app.inject({ method: 'GET', url: '/health' });
    expect(health.statusCode).toBe(200);

    const publicReport = await app.inject({
      method: 'POST',
      url: '/v1/support/report',
      payload: { subject: 'Consulta piloto', message: 'No puedo sincronizar el convoy' }
    });
    expect(publicReport.statusCode).not.toBe(401);

    const withoutKey = await app.inject({ method: 'GET', url: '/v1/app/version' });
    expect(withoutKey.statusCode).toBe(401);
    expect(withoutKey.json().error).toBe('PILOT_KEY_REQUIRED');

    const withKey = await app.inject({
      method: 'GET',
      url: '/v1/app/version',
      headers: { 'x-rbs-pilot-key': PILOT_KEY }
    });
    expect(withKey.statusCode).toBe(200);

    await app.close();
    await pool.end();
  });

  it('devuelve 403 para una clave revocada', async () => {
    const { app, pool } = await makeApp({ RBS_PILOT_REVOKED_KEYS: PILOT_KEY });
    const res = await app.inject({
      method: 'GET',
      url: '/v1/app/version',
      headers: { 'x-rbs-pilot-key': PILOT_KEY }
    });
    expect(res.statusCode).toBe(403);
    expect(res.json().error).toBe('PILOT_KEY_REVOKED');
    await app.close();
    await pool.end();
  });

  it('no permite el backoffice con la clave dev si falta ADMIN_API_KEY en modo piloto', async () => {
    const { app, pool } = await makeApp();
    const res = await app.inject({
      method: 'GET',
      url: '/v1/admin/analytics/overview',
      headers: { 'x-rbs-pilot-key': PILOT_KEY, 'x-admin-key': 'rbs-dev-admin-secret-key-2026' }
    });
    expect(res.statusCode).toBe(503);
    expect(res.json().error).toBe('ADMIN_KEY_NOT_CONFIGURED');
    await app.close();
    await pool.end();
  });
});
