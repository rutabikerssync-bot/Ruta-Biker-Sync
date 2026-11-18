import { beforeAll, afterAll, describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import type { FastifyInstance } from 'fastify';

function resolveTestDatabaseUrl(): string | undefined {
  if (process.env.PG_TEST_URL || process.env.DATABASE_URL) {
    return process.env.PG_TEST_URL ?? process.env.DATABASE_URL;
  }
  for (const filename of ['.env', '.env.example']) {
    const filePath = join(process.cwd(), filename);
    if (existsSync(filePath)) {
      const content = readFileSync(filePath, 'utf8');
      const pgTestMatch = content.match(/^PG_TEST_URL=(.+)$/m);
      if (pgTestMatch?.[1]) {
        return pgTestMatch[1].trim();
      }
      const dbUrlMatch = content.match(/^DATABASE_URL=(.+)$/m);
      if (dbUrlMatch?.[1]) {
        return dbUrlMatch[1].trim();
      }
    }
  }
  return undefined;
}

async function createTestDb(): Promise<{ db: DbExecutor; usingRealPostgres: boolean }> {
  const connectionString = resolveTestDatabaseUrl();
  const pool = createPgPool(connectionString);
  await pool.query('SELECT 1');
  const sql = readInitMigrationSql();
  await pool.query(sql);
  await pool.query(`
    TRUNCATE TABLE
      support_reports,
      refresh_tokens,
      rate_limit_events,
      users
    RESTART IDENTITY CASCADE;
  `);
  return { db: pool, usingRealPostgres: true };
}

describe('Fase 7 — Perfil Enriquecido, Fotos BYTEA, Verificación de Versión y Soporte (D98)', () => {
  let app: FastifyInstance;
  let db: DbExecutor;
  let testUserId: string;

  const validWebpBuffer = Buffer.from('52494646240000005745425056503820180000003001009d012a0100010002003425a400037000fe00', 'hex');
  const validJpegBuffer = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x60, 0x00, 0x60, 0x00, 0x00, 0xff, 0xd9]);

  beforeAll(async () => {
    try {
      const dbSetup = await createTestDb();
      db = dbSetup.db;
      app = await buildApp({ db });
      await app.ready();

      // Crear usuario de prueba
      const reg = await app.inject({
        method: 'POST',
        url: '/v1/auth/login',
        payload: {
          email: 'biker.test@rutabikersync.com',
          displayName: 'Capitán Oscar',
          countryCode: 'VE',
          acceptedTermsAndConvivencia: true
        }
      });
      const regBody = JSON.parse(reg.body);
      testUserId = regBody.user.id;
    } catch (err: any) {
      console.warn('[phase7_profile_support] PostgreSQL test DB offline, test skipped:', err?.message || err);
    }
  });

  afterAll(async () => {
    if (app) await app.close();
    if (db) await (db.end ? db.end() : (db.close ? db.close() : undefined));
  });

  it('1. GET /v1/app/version retorna configuración canónica de versión para el Splash', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/app/version'
    });
    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.body);
    expect(body.minVersion).toBe('1.0.0');
    expect(body.minVersionCode).toBe(1);
    expect(body.latestVersion).toBe('1.0.0');
    expect(body.latestVersionCode).toBe(1);
    expect(body.forceUpdate).toBe(false);
    expect(body.updateUrl).toContain('play.google.com');
  });

  it('2. GET /v1/users/me retorna campos enriquecidos de perfil, visibilidad y motocicleta', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/users/me',
      headers: { 'x-user-id': testUserId }
    });
    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.body);
    expect(body.id).toBe(testUserId);
    expect(body.displayName).toBe('Capitán Oscar');
    expect(body.profileVisibility).toBe('PUBLIC');
    expect(body.motorcycleVisibility).toBe('PUBLIC');
    expect(body.motorcycleBrand).toBeNull();
  });

  it('3. PUT /v1/users/profile actualiza datos de la motocicleta y niveles de visibilidad', async () => {
    const res = await app.inject({
      method: 'PUT',
      url: '/v1/users/profile',
      headers: { 'x-user-id': testUserId },
      payload: {
        aliasBiker: 'Capitán Oscar',
        pinColorHex: '#34D399',
        motorcycleBrand: 'BMW',
        motorcycleModel: 'R1250GS Adventure',
        motorcycleDisplacement: 1254,
        motorcycleYear: 2024,
        motorcycleColor: 'Negro Triple Black',
        motorcycleSerial: 'WB10J9108PZG12345',
        profileVisibility: 'CLUB_ONLY',
        motorcycleVisibility: 'CLUB_ONLY'
      }
    });
    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.body);
    expect(body.updated).toBe(true);
    expect(body.profile.motorcycleBrand).toBe('BMW');
    expect(body.profile.motorcycleDisplacement).toBe(1254);
    expect(body.profile.profileVisibility).toBe('CLUB_ONLY');

    // Verificar en GET /v1/users/me
    const getRes = await app.inject({
      method: 'GET',
      url: '/v1/users/me',
      headers: { 'x-user-id': testUserId }
    });
    const getBody = JSON.parse(getRes.body);
    expect(getBody.motorcycleBrand).toBe('BMW');
    expect(getBody.motorcycleModel).toBe('R1250GS Adventure');
    expect(getBody.profileVisibility).toBe('CLUB_ONLY');
  });

  it('4. POST /v1/users/photo sube foto WebP de perfil y GET /v1/users/photo/:id la entrega', async () => {
    const uploadRes = await app.inject({
      method: 'POST',
      url: '/v1/users/photo',
      headers: { 'x-user-id': testUserId },
      payload: {
        photoBase64: validWebpBuffer.toString('base64')
      }
    });
    expect(uploadRes.statusCode).toBe(200);
    const uploadBody = JSON.parse(uploadRes.body);
    expect(uploadBody.success).toBe(true);
    expect(uploadBody.photoUrl).toBe(`/v1/users/photo/${testUserId}`);

    const getPhotoRes = await app.inject({
      method: 'GET',
      url: `/v1/users/photo/${testUserId}`
    });
    expect(getPhotoRes.statusCode).toBe(200);
    expect(getPhotoRes.headers['content-type']).toContain('image/webp');
    expect(getPhotoRes.rawPayload.length).toBe(validWebpBuffer.length);
  });

  it('5. POST /v1/users/photo rechaza fotos mayores a 2 MB con 400 FILE_TOO_LARGE', async () => {
    const oversizedBuffer = Buffer.alloc(2 * 1024 * 1024 + 10, 0);
    const res = await app.inject({
      method: 'POST',
      url: '/v1/users/photo',
      headers: { 'x-user-id': testUserId },
      payload: {
        photoBase64: oversizedBuffer.toString('base64')
      }
    });
    expect(res.statusCode).toBe(400);
    const body = JSON.parse(res.body);
    expect(body.error).toBe('FILE_TOO_LARGE');
  });

  it('6. POST /v1/users/motorcycle-photo sube foto JPEG de la moto y se sirve correctamente', async () => {
    const uploadRes = await app.inject({
      method: 'POST',
      url: '/v1/users/motorcycle-photo',
      headers: { 'x-user-id': testUserId },
      payload: {
        photoBase64: validJpegBuffer.toString('base64')
      }
    });
    expect(uploadRes.statusCode).toBe(200);
    const uploadBody = JSON.parse(uploadRes.body);
    expect(uploadBody.success).toBe(true);
    expect(uploadBody.motorcyclePhotoUrl).toBe(`/v1/users/motorcycle-photo/${testUserId}`);

    const getPhotoRes = await app.inject({
      method: 'GET',
      url: `/v1/users/motorcycle-photo/${testUserId}`
    });
    expect(getPhotoRes.statusCode).toBe(200);
    expect(getPhotoRes.headers['content-type']).toContain('image/jpeg');
  });

  it('7. POST /v1/support/report registra un reporte de bug con asunto y mensaje', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/v1/support/report',
      headers: { 'x-user-id': testUserId },
      payload: {
        subject: 'Fallo al sincronizar mapa offline',
        message: 'Al descargar la cartografía en segundo plano el indicador se detuvo al 85%.'
      }
    });
    expect(res.statusCode).toBe(201);
    const body = JSON.parse(res.body);
    expect(body.success).toBe(true);
    expect(body.reportId).toBeDefined();
    expect(body.status).toBe('open');
  });

  it('8. POST /v1/support/report con captura de pantalla adjunta almacena el blob y lo lista en GET', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/v1/support/report',
      headers: { 'x-user-id': testUserId },
      payload: {
        subject: 'Error de visualización en cabina',
        message: 'Se adjunta captura con el desfase de píxeles observado.',
        screenshotBase64: validWebpBuffer.toString('base64')
      }
    });
    expect(res.statusCode).toBe(201);
    const body = JSON.parse(res.body);
    expect(body.success).toBe(true);

    const listRes = await app.inject({
      method: 'GET',
      url: '/v1/support/reports',
      headers: { 'x-user-id': testUserId }
    });
    expect(listRes.statusCode).toBe(200);
    const listBody = JSON.parse(listRes.body);
    expect(listBody.reports.length).toBeGreaterThanOrEqual(2);
    expect(listBody.reports[0].has_screenshot).toBe(true);
  });
});
