import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import type { DbExecutor } from '../src/db/pool.js';

const TEST_ADMIN_KEY = 'test-admin-key-rotada-2026';
const ADMIN_HEADERS = { 'x-admin-key': TEST_ADMIN_KEY };
const MISSING_ID = '00000000-0000-4000-8000-000000000001';

function emptyDb(): DbExecutor {
  return {
    async query() {
      return { rows: [], command: '', rowCount: 0, oid: 0, fields: [] };
    },
    async end() {}
  };
}

function failingDb(): DbExecutor {
  return {
    async query() {
      throw new Error('DB_DOWN');
    },
    async end() {}
  };
}

describe('Integridad de BD del backoffice', () => {
  let app: FastifyInstance;

  beforeAll(async () => {
    app = await buildApp({ db: emptyDb(), env: { ...process.env, ADMIN_API_KEY: TEST_ADMIN_KEY } });
    await app.ready();
  });

  afterAll(async () => {
    await app.close();
  });

  it('health y readyz reportan BD disponible', async () => {
    const health = await app.inject({ method: 'GET', url: '/health' });
    expect(health.statusCode).toBe(200);
    expect(health.json()).toEqual({ status: 'ok', db: 'ok' });

    const ready = await app.inject({ method: 'GET', url: '/readyz' });
    expect(ready.statusCode).toBe(200);
    expect(ready.json()).toEqual({ status: 'ready', db: 'ok' });
  });

  it('no devuelve datasets demo cuando la BD está vacía', async () => {
    const overview = await app.inject({
      method: 'GET',
      url: '/v1/admin/analytics/overview',
      headers: ADMIN_HEADERS
    });
    expect(overview.statusCode).toBe(200);
    const body = overview.json();
    expect(body.users.total).toBe(0);
    expect(body.groups.total).toBe(0);
    expect(body.rides.total).toBe(0);
    expect(body.meshOffline.totalHeartbeatsLogged).toBe(0);
    expect(JSON.stringify(body)).not.toContain('1420');
    expect(JSON.stringify(body)).not.toContain('128400');

    for (const [url, key] of [
      ['/v1/admin/groups', 'groups'],
      ['/v1/admin/users', 'users'],
      ['/v1/admin/support/reports', 'reports'],
      ['/v1/admin/support/tickets', 'tickets'],
      ['/v1/admin/audit/sos', 'logs']
    ] as const) {
      const res = await app.inject({ method: 'GET', url, headers: ADMIN_HEADERS });
      expect(res.statusCode).toBe(200);
      expect(res.json()[key]).toEqual([]);
    }
  });

  it('las acciones sobre registros inexistentes no reportan éxito', async () => {
    const groupStatus = await app.inject({
      method: 'PATCH',
      url: `/v1/admin/groups/${MISSING_ID}/status`,
      headers: ADMIN_HEADERS,
      payload: { status: 'suspended', reason: 'test' }
    });
    expect(groupStatus.statusCode).toBe(404);

    const userStatus = await app.inject({
      method: 'PATCH',
      url: `/v1/admin/users/${MISSING_ID}/status`,
      headers: ADMIN_HEADERS,
      payload: { status: 'suspended', reason: 'test' }
    });
    expect(userStatus.statusCode).toBe(404);
  });
});

describe('Salud con PostgreSQL caído', () => {
  let app: FastifyInstance;

  beforeAll(async () => {
    app = await buildApp({ db: failingDb() });
    await app.ready();
  });

  afterAll(async () => {
    await app.close();
  });

  it('health y readyz reportan degradación en vez de ok', async () => {
    const health = await app.inject({ method: 'GET', url: '/health' });
    expect(health.statusCode).toBe(503);
    expect(health.json()).toEqual({ status: 'degraded', db: 'error' });

    const ready = await app.inject({ method: 'GET', url: '/readyz' });
    expect(ready.statusCode).toBe(503);
    expect(ready.json()).toEqual({ status: 'not_ready', db: 'error' });
  });
});
