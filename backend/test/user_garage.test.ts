import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import type { DbExecutor } from '../src/db/pool.js';

function createGarageMockDb(): DbExecutor {
  const store = new Map<string, { garage_state: Record<string, unknown>; updated_at: Date }>();
  return {
    async query(text: string, values?: unknown[]) {
      if (text.includes('SELECT garage_state, updated_at') && text.includes('FROM user_bike_garage')) {
        const uid = String(values?.[0] ?? '');
        const row = store.get(uid);
        return {
          rows: row ? [row] : [],
          command: 'SELECT',
          rowCount: row ? 1 : 0,
          oid: 0,
          fields: []
        };
      }
      if (text.includes('INSERT INTO user_bike_garage')) {
        const uid = String(values?.[0] ?? '');
        const rawJson = String(values?.[1] ?? '{}');
        const parsed = JSON.parse(rawJson) as Record<string, unknown>;
        const record = { garage_state: parsed, updated_at: new Date('2026-11-26T00:00:00Z') };
        store.set(uid, record);
        return {
          rows: [record],
          command: 'INSERT',
          rowCount: 1,
          oid: 0,
          fields: []
        };
      }
      return { rows: [], command: '', rowCount: 0, oid: 0, fields: [] };
    },
    async end() {}
  };
}

describe('Fase 3 Garage — /v1/users/me/garage', () => {
  let app: FastifyInstance;

  beforeAll(async () => {
    app = await buildApp({ db: createGarageMockDb() });
    await app.ready();
  });

  afterAll(async () => {
    await app.close();
  });

  it('rechaza GET /v1/users/me/garage sin autenticación (HTTP 401)', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/users/me/garage'
    });
    expect(res.statusCode).toBe(401);
  });

  it('devuelve garageState null cuando el usuario aún no ha guardado estado', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/users/me/garage',
      headers: { 'x-user-id': '00000000-0000-4000-8000-000000000101' }
    });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ garageState: null, updatedAt: null });
  });

  it('persiste y recupera el estado del Garage mediante PUT y GET', async () => {
    const userId = '00000000-0000-4000-8000-000000000202';
    const sampleState = {
      odometerKm: 12450,
      coolingType: 'LIQUID',
      engineOil: { viscosity: '10W-40', jasoSpec: 'JASO_MA2' }
    };

    const putRes = await app.inject({
      method: 'PUT',
      url: '/v1/users/me/garage',
      headers: { 'x-user-id': userId },
      payload: { garageState: sampleState }
    });
    expect(putRes.statusCode).toBe(200);
    const putBody = putRes.json();
    expect(putBody.garageState).toEqual(sampleState);
    expect(typeof putBody.updatedAt).toBe('string');

    const getRes = await app.inject({
      method: 'GET',
      url: '/v1/users/me/garage',
      headers: { 'x-user-id': userId }
    });
    expect(getRes.statusCode).toBe(200);
    expect(getRes.json().garageState).toEqual(sampleState);
  });

  it('rechaza PUT /v1/users/me/garage con payload inválido (HTTP 400)', async () => {
    const res = await app.inject({
      method: 'PUT',
      url: '/v1/users/me/garage',
      headers: { 'x-user-id': '00000000-0000-4000-8000-000000000202' },
      payload: { garageState: 'no-es-un-objeto' }
    });
    expect(res.statusCode).toBe(400);
  });
});
