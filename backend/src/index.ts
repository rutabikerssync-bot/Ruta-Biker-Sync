import 'dotenv/config';
import { createPgPool, runMigrations, type DbExecutor } from './db/pool.js';
import { buildApp } from './app.js';
import { startRgpdPurgeCron } from './jobs/rgpdPurgeJob.js';

let db: DbExecutor;
try {
  const pool = createPgPool();
  await pool.query('SELECT 1');
  await runMigrations(pool);
  db = pool;
  startRgpdPurgeCron(pool);
  console.log('✅ Base de datos PostgreSQL conectada y migraciones aplicadas.');
  console.log('🧹 Cron de Purga RGPD (24h) iniciado.');
} catch (err: any) {
  console.warn('⚠️ PostgreSQL no disponible en localhost:5433 (' + (err?.message || 'ECONNREFUSED') + ').');
  console.log('⚡ Iniciando servidor en modo autónomo / desarrollo para servir el Backoffice y la API.');
  db = {
    async query(text: string, values?: unknown[]) {
      return { rows: [], command: '', rowCount: 0, oid: 0, fields: [] };
    },
    async end() {}
  };
}

const app = await buildApp({ db, logger: false });
const port = Number(process.env.PORT ?? 3000);
await app.listen({ port, host: '0.0.0.0' });

console.log(`\n======================================================`);
console.log(`🚀 Servidor Ruta Biker Sync activo en http://localhost:${port}`);
console.log(`🏍️ Panel Backoffice activo en http://localhost:${port}/admin`);
console.log(`======================================================\n`);
