import { describe, expect, it } from 'vitest';
import { newDb } from 'pg-mem';
import { buildApp } from '../src/app.js';

describe('Health endpoint', () => {
  it('returns status ok', async () => {
    const mem = newDb();
    const pgAdapter = mem.adapters.createPg();
    const pool = new pgAdapter.Pool();
    const app = await buildApp({ db: pool, logger: false });
    const res = await app.inject({ method: 'GET', url: '/health' });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ status: 'ok', db: 'ok' });

    const ready = await app.inject({ method: 'GET', url: '/readyz' });
    expect(ready.statusCode).toBe(200);
    expect(ready.json()).toEqual({ status: 'ready', db: 'ok' });
    await app.close();
    await pool.end();
  });
});
