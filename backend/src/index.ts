import 'dotenv/config';
import { createPgPool, runMigrations } from './db/pool.js';
import { buildApp } from './app.js';

const pool = createPgPool();
await runMigrations(pool);

const app = await buildApp({ db: pool, logger: true });
const port = Number(process.env.PORT ?? 3000);
await app.listen({ port, host: '0.0.0.0' });
