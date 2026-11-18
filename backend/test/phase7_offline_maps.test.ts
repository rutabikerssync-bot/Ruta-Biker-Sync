import { beforeAll, afterAll, describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import { CANONICAL_MAP_REGIONS } from '../src/lib/offlineMaps.js';

function resolveTestDatabaseUrl(): string {
  return process.env.PG_TEST_URL || process.env.DATABASE_URL || 'postgres://rbs:rbs_dev_password@localhost:5433/rbs';
}

async function createTestDb(): Promise<{ db: DbExecutor; pool: any }> {
  const connectionString = resolveTestDatabaseUrl();
  const pool = createPgPool(connectionString);
  await pool.query('SELECT 1');
  const sql = readInitMigrationSql();
  await pool.query(sql);
  return { db: pool, pool };
}

describe('Fase 7.1.3 — Mapas Offline con PMTiles (Catálogo y Descarga con Range Requests)', () => {
  let app: FastifyInstance;
  let db: DbExecutor;
  let pool: any;
  let userId: string;

  beforeAll(async () => {
    const testDb = await createTestDb();
    db = testDb.db;
    pool = testDb.pool;

    userId = randomUUID();
    const userEmail = `rider_cartografo_${Date.now()}@rutabikersync.com`;
    await db.query(
      `INSERT INTO users (id, email, display_name, country_code, account_type, status)
       VALUES ($1, $2, 'Rider Cartógrafo', 'VE', 'member', 'active')`,
      [userId, userEmail]
    );

    app = await buildApp({ db });
    await app.ready();
  });

  afterAll(async () => {
    if (app) await app.close();
    if (pool) await pool.end();
  });

  it('1. GET /v1/maps/regions retorna 200 OK con el catálogo de 3 regiones canónicas si tiene x-user-id válido', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/maps/regions',
      headers: {
        'x-user-id': userId
      }
    });

    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.success).toBe(true);
    expect(Array.isArray(body.regions)).toBe(true);
    expect(body.regions.length).toBe(3);

    const ids = body.regions.map((r: any) => r.id);
    expect(ids).toContain('caracas-la-guaira');
    expect(ids).toContain('caracas-junquito-tovar');
    expect(ids).toContain('valencia-puerto-cabello');

    const laGuaira = body.regions.find((r: any) => r.id === 'caracas-la-guaira');
    expect(laGuaira.name).toBe('Caracas — La Guaira');
    expect(laGuaira.boundingBox).toEqual([10.50, -67.05, 10.62, -66.90]);
    expect(laGuaira.estimatedSizeMb).toBeGreaterThan(0);
    expect(laGuaira.version).toBe('1.0.0');
  });

  it('2. GET /v1/maps/regions rechaza con 401 UNAUTHORIZED si falta x-user-id', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/maps/regions'
    });

    expect(res.statusCode).toBe(401);
    const body = res.json();
    expect(body.error).toBe('UNAUTHORIZED');
  });

  it('3. GET /v1/maps/regions/:id/download sirve el binario .pmtiles con 200 OK, Content-Type y magic bytes válidos', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/maps/regions/caracas-la-guaira/download',
      headers: {
        'x-user-id': userId
      }
    });

    expect(res.statusCode).toBe(200);
    expect(res.headers['content-type']).toBe('application/vnd.pmtiles');
    expect(res.headers['content-disposition']).toContain('attachment; filename="caracas-la-guaira.pmtiles"');
    expect(res.headers['accept-ranges']).toBe('bytes');
    expect(Number(res.headers['content-length'])).toBeGreaterThan(1000);

    const rawBuffer = res.rawPayload;
    // Magic bytes de PMTiles v3: "PMTiles\u0003"
    const magic = rawBuffer.subarray(0, 8);
    expect(magic.toString('utf-8', 0, 7)).toBe('PMTiles');
    expect(magic[7]).toBe(3);
  });

  it('4. GET /v1/maps/regions/:id/download soporta Range requests retornando 206 Partial Content y Content-Range', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/maps/regions/caracas-la-guaira/download',
      headers: {
        'x-user-id': userId,
        range: 'bytes=0-1023'
      }
    });

    expect(res.statusCode).toBe(206);
    expect(res.headers['content-range']).toMatch(/^bytes 0-1023\/\d+$/);
    expect(res.headers['content-length']).toBe('1024');
    expect(res.rawPayload.length).toBe(1024);

    const magic = res.rawPayload.subarray(0, 8);
    expect(magic.toString('utf-8', 0, 7)).toBe('PMTiles');
    expect(magic[7]).toBe(3);
  });

  it('5. GET /v1/maps/regions/:id/download retorna 404 NOT_FOUND para una región inexistente', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/maps/regions/region-inexistente-marte/download',
      headers: {
        'x-user-id': userId
      }
    });

    expect(res.statusCode).toBe(404);
    const body = res.json();
    expect(body.error).toBe('NOT_FOUND');
  });

  it('6. POST /v1/maps/custom-route registra dinámicamente una ruta trazada por el biker con PMTiles', async () => {
    const customId = `route-altamira-guatopo-${Date.now()}`;
    const res = await app.inject({
      method: 'POST',
      url: '/v1/maps/custom-route',
      headers: {
        'x-user-id': userId
      },
      payload: {
        id: customId,
        name: 'Ruta Montaña: Altamira ➔ P.N. Guatopo',
        description: 'Ruta trazada por el Capitán en vivo con buffer de 10 km',
        boundingBox: [10.05, -66.55, 10.52, -66.30],
        estimatedSizeMb: 11.4
      }
    });

    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.success).toBe(true);
    expect(body.region.id).toBe(customId);
    expect(body.region.name).toBe('Ruta Montaña: Altamira ➔ P.N. Guatopo');
    expect(body.region.boundingBox).toEqual([10.05, -66.55, 10.52, -66.30]);
    expect(body.region.fileName).toBe(`${customId}.pmtiles`);
  });

  it('7. GET /v1/maps/regions incluye la ruta dinámica creada y permite a los confirmados descargarla', async () => {
    const customId = `route-dinamica-${Date.now()}`;
    await app.inject({
      method: 'POST',
      url: '/v1/maps/custom-route',
      headers: { 'x-user-id': userId },
      payload: {
        id: customId,
        name: 'Rodada Costera Dinámica',
        boundingBox: [10.50, -67.10, 10.65, -66.80],
        estimatedSizeMb: 9.8
      }
    });

    const listRes = await app.inject({
      method: 'GET',
      url: '/v1/maps/regions',
      headers: { 'x-user-id': userId }
    });
    expect(listRes.statusCode).toBe(200);
    const regions = listRes.json().regions;
    const found = regions.find((r: any) => r.id === customId);
    expect(found).toBeDefined();
    expect(found.name).toBe('Rodada Costera Dinámica');

    // Descarga por un participante confirmado
    const downloadRes = await app.inject({
      method: 'GET',
      url: `/v1/maps/regions/${customId}/download`,
      headers: {
        'x-user-id': userId,
        range: 'bytes=0-511'
      }
    });
    expect(downloadRes.statusCode).toBe(206);
    expect(downloadRes.headers['content-range']).toMatch(/^bytes 0-511\/\d+$/);
    const magic = downloadRes.rawPayload.subarray(0, 8);
    expect(magic.toString('utf-8', 0, 7)).toBe('PMTiles');
    expect(magic[7]).toBe(3);
  });
});
