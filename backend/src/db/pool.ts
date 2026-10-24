import pg, { type Pool, type QueryResult, type QueryResultRow } from 'pg';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export interface DbExecutor {
  query<R extends QueryResultRow = any>(text: string, values?: unknown[]): Promise<QueryResult<R>>;
  end(): Promise<void>;
}

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function resolveDefaultConnectionString(): string {
  if (process.env.PG_TEST_URL || process.env.DATABASE_URL) {
    return (process.env.PG_TEST_URL ?? process.env.DATABASE_URL)!;
  }
  for (const filename of ['.env', '.env.example']) {
    const filePath = path.join(process.cwd(), filename);
    if (fs.existsSync(filePath)) {
      const content = fs.readFileSync(filePath, 'utf8');
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
  return 'postgres://rbs:rbs_dev_password@localhost:5433/rbs';
}

export function createPgPool(connectionString?: string): Pool {
  return new pg.Pool({
    connectionString: connectionString ?? resolveDefaultConnectionString()
  });
}

export const pool: Pool = createPgPool();

export function readInitMigrationSql(): string {
  const m1Path = path.join(__dirname, 'migrations', '0001_init.sql');
  const m2Path = path.join(__dirname, 'migrations', '0002_phase2_rules.sql');
  const m1 = fs.readFileSync(m1Path, 'utf8');
  const m2 = fs.existsSync(m2Path) ? fs.readFileSync(m2Path, 'utf8') : '';
  return m2 ? `${m1}\n${m2}` : m1;
}

export async function runMigrations(db: DbExecutor): Promise<void> {
  const sql = readInitMigrationSql();
  await db.query(sql);
}
