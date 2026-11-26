import 'dotenv/config';
import { createPgPool, runMigrations, type DbExecutor } from './db/pool.js';
import { buildApp } from './app.js';
import { startRgpdPurgeCron } from './jobs/rgpdPurgeJob.js';

const pool = createPgPool();
try {
  await pool.query('SELECT 1');
  await runMigrations(pool);
} catch (err: any) {
  console.error('❌ PostgreSQL no disponible; el backend no iniciará en modo degradado.', err?.message || err);
  await pool.end().catch(() => undefined);
  throw err;
}
const db: DbExecutor = pool;
startRgpdPurgeCron(pool);
console.log('✅ Base de datos PostgreSQL conectada y migraciones aplicadas.');
console.log('🧹 Cron de Purga RGPD (24h) iniciado.');

const app = await buildApp({ db, logger: false });
const port = Number(process.env.PORT ?? 3000);
await app.listen({ port, host: '0.0.0.0' });

console.log(`\n======================================================`);
console.log(`🚀 Servidor Ruta Biker Sync activo en http://localhost:${port}`);
console.log(`🏍️ Panel Backoffice activo en http://localhost:${port}/admin`);
console.log(`======================================================\n`);
