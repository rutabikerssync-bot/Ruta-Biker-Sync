import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { newDb } from 'pg-mem';
import { buildApp } from '../src/app.js';

const migrationPath = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../src/db/migrations/0022_pilot_telemetry.sql'
);

const PILOT_KEY = 'TECNO-01-piloto-rbs-2026';
const ADMIN_KEY = 'admin-piloto-clave-larga-2026';

async function makeApp() {
  const mem = newDb();
  const pgAdapter = mem.adapters.createPg();
  const pool = new pgAdapter.Pool();
  const migration = fs.readFileSync(migrationPath, 'utf8');
  for (const statement of migration.split(';').map((s) => s.trim()).filter(Boolean)) {
    await pool.query(statement);
  }
  const app = await buildApp({
    db: pool,
    logger: false,
    env: {
      ...process.env,
      RBS_PILOT_ALLOWED_KEYS: PILOT_KEY,
      ADMIN_API_KEY: ADMIN_KEY
    }
  });
  return { app, pool };
}

const headers = {
  'x-rbs-pilot-key': PILOT_KEY,
  'x-rbs-pilot-build': 'TECNO-01-debug'
};

describe('Telemetría de dispositivos del piloto', () => {
  it('requiere clave de piloto y redacta PII en errores y logs', async () => {
    const { app, pool } = await makeApp();

    const denied = await app.inject({
      method: 'POST',
      url: '/v1/support/device-telemetry',
      payload: { kind: 'heartbeat' }
    });
    expect(denied.statusCode).toBe(401);

    const res = await app.inject({
      method: 'POST',
      url: '/v1/support/device-telemetry',
      headers,
      payload: {
        deviceModel: 'KI5k-GL',
        deviceBrand: 'TECNO',
        appVersion: '0.1.0',
        gitCommit: 'abcdef123456',
        batteryPct: 78,
        isCharging: false,
        peerCount: 2,
        meshState: 'CONNECTED',
        kind: 'error',
        severity: 'error',
        message: 'Fallo para rider@yahoo.com en 39.12345,-0.98765',
        stack: 'Exception at 39.12345,-0.98765',
        logsTail: 'token=abc123def456 xyz\nlat=39.12345'
      }
    });
    expect(res.statusCode).toBe(201);
    expect(res.json().pilotKeyFp).toMatch(/^[0-9a-f]{12}$/);

    const light = await app.inject({
      method: 'GET',
      url: '/v1/support/devices/light',
      headers: { 'x-rbs-pilot-key': PILOT_KEY }
    });
    expect(light.statusCode).toBe(200);
    const body = light.json();
    expect(body.devices).toHaveLength(1);
    expect(body.devices[0].pilot_build).toBe('TECNO-01-debug');
    expect(body.devices[0].device_model).toBe('KI5k-GL');
    expect(body.devices[0].last_error_short).not.toContain('rider@yahoo.com');
    expect(body.devices[0].last_error_short).not.toContain('39.12345');
    expect(JSON.stringify(body)).not.toContain('abc123def456');
    expect(JSON.stringify(body)).not.toContain('logs_tail');

    const full = await app.inject({
      method: 'GET',
      url: '/v1/support/devices',
      headers: { ...headers, 'x-admin-key': ADMIN_KEY }
    });
    expect(full.statusCode).toBe(200);
    expect(full.json().devices).toHaveLength(1);
    expect(full.json().events).toHaveLength(1);
    expect(full.json().events[0].stack).not.toContain('39.12345');

    await app.close();
    await pool.end();
  });

  it('un latido posterior no borra el último error registrado', async () => {
    const { app, pool } = await makeApp();

    await app.inject({
      method: 'POST',
      url: '/v1/support/device-telemetry',
      headers,
      payload: { kind: 'error', severity: 'fatal', message: 'Reinicio inesperado' }
    });
    await app.inject({
      method: 'POST',
      url: '/v1/support/device-telemetry',
      headers,
      payload: { kind: 'heartbeat', batteryPct: 55, peerCount: 1 }
    });

    const full = await app.inject({
      method: 'GET',
      url: '/v1/support/devices',
      headers: { ...headers, 'x-admin-key': ADMIN_KEY }
    });
    const device = full.json().devices[0];
    expect(device.last_error).toContain('Reinicio inesperado');
    expect(device.battery_pct).toBe(55);
    expect(full.json().events).toHaveLength(2);

    await app.close();
    await pool.end();
  });
});
