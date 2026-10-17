import pg, { type Pool, type QueryResult, type QueryResultRow } from 'pg';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export interface DbExecutor {
  query<R extends QueryResultRow = any>(text: string, values?: unknown[]): Promise<QueryResult<R>>;
  end(): Promise<void>;
}

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export function createPgPool(connectionString?: string): Pool {
  return new pg.Pool({
    connectionString:
      connectionString ??
      process.env.PG_TEST_URL ??
      process.env.DATABASE_URL ??
      'postgres://localhost:5432/rbs'
  });
}

export function readInitMigrationSql(): string {
  const migrationPath = path.join(__dirname, 'migrations', '0001_init.sql');
  return fs.readFileSync(migrationPath, 'utf8');
}

export async function runMigrations(db: DbExecutor): Promise<void> {
  const sql = readInitMigrationSql();
  await db.query(sql);
}
