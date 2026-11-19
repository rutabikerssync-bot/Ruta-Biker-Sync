import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { newDb } from 'pg-mem';
import { buildApp } from '../src/app.js';
import { isDebugLogDate, redactBackendLogLine, resolveDebugLogPath } from '../src/lib/debugLogs.js';

const FIXTURE_DATE = '20990101';
const ADMIN_KEY = 'test-admin-key-1234567890';
const logsDir = path.resolve(process.cwd(), 'logs');
const fixturePath = path.join(logsDir, `fastify_dev_${FIXTURE_DATE}.log`);

const FIXTURE = [
  'uid=4fb8dbf0-1111-2222-3333-444455556666 correo=juan@ejemplo.com lat=10.349246',
  'Authorization: Bearer eyJhbGciOi.payload.signature',
  'logo=data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA==',
  'linea util de diagnostico MeshRelayEngine'
].join('\n');

async function makeApp(extraEnv: Record<string, string>) {
  const mem = newDb();
  const pgAdapter = mem.adapters.createPg();
  const pool = new pgAdapter.Pool();
  const app = await buildApp({
    db: pool,
    logger: false,
    env: { ...process.env, ...extraEnv } as NodeJS.ProcessEnv
  });
  return { app, pool };
}

describe('Endpoint debug de logs (Bloque D)', () => {
  beforeAll(() => {
    fs.mkdirSync(logsDir, { recursive: true });
    fs.writeFileSync(fixturePath, FIXTURE, 'utf8');
  });

  afterAll(() => {
    if (fs.existsSync(fixturePath)) fs.unlinkSync(fixturePath);
  });

  it('sólo acepta fechas YYYYMMDD y rutas dentro de logs/', () => {
    expect(isDebugLogDate('20261118')).toBe(true);
    expect(isDebugLogDate('../../etc/passwd')).toBe(false);
    expect(resolveDebugLogPath(logsDir, '../secreto')).toBeNull();
    expect(resolveDebugLogPath(logsDir, '20261118')).toBe(
      path.join(logsDir, 'fastify_dev_20261118.log')
    );
  });

  it('redacta correos, UUIDs, coordenadas, JWT y base64', () => {
    const clean = redactBackendLogLine(FIXTURE.split('\n')[0]);
    expect(clean).not.toContain('4fb8dbf0');
    expect(clean).not.toContain('juan@ejemplo.com');
    expect(clean).not.toContain('10.349246');
    expect(clean).toContain('[email-redacted]');
    expect(clean).toContain('uid=[redacted]');
    expect(clean).toContain('[coord]');
    const standalone = redactBackendLogLine('piloto 4fb8dbf0-1111-2222-3333-444455556666');
    expect(standalone).toContain('[uuid-redacted]');
    expect(redactBackendLogLine("name: 'BUFALO BEIKERS'")).not.toContain('BUFALO');
    expect(redactBackendLogLine("name: 'BUFALO BEIKERS'")).not.toContain('BEIKERS');
  });

  it('devuelve 404 si no hay opt-in de entorno', async () => {
    const { app, pool } = await makeApp({ ADMIN_API_KEY: ADMIN_KEY });
    const res = await app.inject({
      method: 'GET',
      url: `/v1/debug/logs?date=${FIXTURE_DATE}`,
      headers: { 'x-admin-key': ADMIN_KEY }
    });
    expect(res.statusCode).toBe(404);
    await app.close();
    await pool.end();
  });

  it('devuelve 401 sin clave de administrador configurada', async () => {
    const { app, pool } = await makeApp({
      ENABLE_DEBUG_LOGS: 'true',
      ADMIN_API_KEY: ADMIN_KEY
    });
    const res = await app.inject({
      method: 'GET',
      url: `/v1/debug/logs?date=${FIXTURE_DATE}`
    });
    expect(res.statusCode).toBe(401);
    await app.close();
    await pool.end();
  });

  it('devuelve el tail redactado con clave válida en desarrollo', async () => {
    const { app, pool } = await makeApp({
      ENABLE_DEBUG_LOGS: 'true',
      ADMIN_API_KEY: ADMIN_KEY
    });
    const res = await app.inject({
      method: 'GET',
      url: `/v1/debug/logs?date=${FIXTURE_DATE}&lines=10`,
      headers: { 'x-admin-key': ADMIN_KEY }
    });
    expect(res.statusCode).toBe(200);
    expect(res.headers['content-type']).toContain('text/plain');
    expect(res.body).toContain('linea util de diagnostico MeshRelayEngine');
    expect(res.body).not.toContain('juan@ejemplo.com');
    expect(res.body).not.toContain('4fb8dbf0');
    expect(res.body).not.toContain('10.349246');
    expect(res.body).not.toContain('eyJhbGciOi');
    expect(res.body).not.toContain('/9j/4AAQ');
    await app.close();
    await pool.end();
  });

  it('nunca expone el endpoint en producción', async () => {
    const { app, pool } = await makeApp({
      NODE_ENV: 'production',
      ENABLE_DEBUG_LOGS: 'true',
      ADMIN_API_KEY: ADMIN_KEY,
      AUTH_TOKEN_ACTIVE_KID: 'k1',
      AUTH_TOKEN_KEYS_JSON: JSON.stringify({ k1: 'x'.repeat(48) })
    });
    const res = await app.inject({
      method: 'GET',
      url: `/v1/debug/logs?date=${FIXTURE_DATE}`,
      headers: { 'x-admin-key': ADMIN_KEY }
    });
    expect(res.statusCode).toBe(404);
    await app.close();
    await pool.end();
  });
});
