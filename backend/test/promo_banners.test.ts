import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';

const TEST_ADMIN_KEY = 'test-admin-promo-banners-2026';

describe('Fase 1 Banners Promocionales — /v1/announcements/active y /v1/admin/promo-banners', () => {
  let db: DbExecutor;
  let app: FastifyInstance;
  let isDbOnline = false;

  beforeAll(async () => {
    const pool = createPgPool();
    await pool.query('SELECT 1');
    await pool.query(readInitMigrationSql());
    db = pool;
    isDbOnline = true;
    app = await buildApp({ db, env: { ...process.env, ADMIN_API_KEY: TEST_ADMIN_KEY } });
    await app.ready();
  });

  afterAll(async () => {
    if (app) await app.close();
    if (db && isDbOnline) await db.end();
  });

  it('rechaza acceso admin sin x-admin-key (HTTP 401)', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/admin/promo-banners'
    });
    expect(res.statusCode).toBe(401);
  });

  it('crea, edita, filtra vigentes en /v1/announcements/active y elimina banners', async () => {
    const createRes = await app.inject({
      method: 'POST',
      url: '/v1/admin/promo-banners',
      headers: { 'x-admin-key': TEST_ADMIN_KEY },
      payload: {
        title: 'Rodada Aniversario',
        subtitle: 'Del 26 al 30 de noviembre',
        badgeText: 'OFICIAL',
        accentColorHex: '#E5B869',
        ctaLabel: 'Ver ruta',
        frequency: 'once_per_campaign',
        priority: 10,
        active: true
      }
    });
    expect(createRes.statusCode).toBe(201);
    const createdId = createRes.json().id as string;

    const activeRes = await app.inject({
      method: 'GET',
      url: '/v1/announcements/active'
    });
    expect(activeRes.statusCode).toBe(200);
    expect(activeRes.json().banners.some((b: any) => b.id === createdId)).toBe(true);

    // Editar título y desactivar desde Soporte/Admin
    const patchRes = await app.inject({
      method: 'PATCH',
      url: `/v1/admin/promo-banners/${createdId}`,
      headers: { 'x-admin-key': TEST_ADMIN_KEY },
      payload: { title: 'Rodada Editada', active: false }
    });
    expect(patchRes.statusCode).toBe(200);
    expect(patchRes.json().title).toBe('Rodada Editada');
    expect(patchRes.json().active).toBe(false);

    const activeAfterDisable = await app.inject({
      method: 'GET',
      url: '/v1/announcements/active'
    });
    expect(activeAfterDisable.json().banners.some((b: any) => b.id === createdId)).toBe(false);

    const delRes = await app.inject({
      method: 'DELETE',
      url: `/v1/admin/promo-banners/${createdId}`,
      headers: { 'x-admin-key': TEST_ADMIN_KEY }
    });
    expect(delRes.statusCode).toBe(200);
    expect(delRes.json()).toEqual({ deleted: true, id: createdId });
  });
});
