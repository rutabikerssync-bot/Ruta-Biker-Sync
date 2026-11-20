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
    connectionString: connectionString ?? resolveDefaultConnectionString(),
    max: 50,                      // Máximo de conexiones simultáneas
    min: 5,                       // Conexiones mínimas (warm-up)
    idleTimeoutMillis: 30000,     // Cerrar conexiones inactivas a los 30s
    connectionTimeoutMillis: 5000 // Timeout de adquisición de conexión
  });
}

export const pool: Pool = createPgPool();

export function readInitMigrationSql(): string {
  const migrationFiles = [
    '0001_init.sql',
    '0002_phase2_rules.sql',
    '0003_phase3_rides_manifest.sql',
    '0004_phase6_gamification.sql',
    '0005_phase8_admin_support_analytics.sql',
    '0006_invitation_type.sql',
    '0007_vault_e2ee.sql',
    '0008_vault_recovery_rate_limit.sql',
    '0009_vault_attempts_set_null.sql',
    '0010_rate_limiting_auth_invites.sql',
    '0011_fcm_tokens.sql',
    '0012_sos_radar.sql',
    '0013_sos_mesh_bridge.sql',
    '0014_profile_enrichment.sql',
    '0015_add_mf_tier.sql',
    '0016_club_profile.sql',
    '0017_club_routes_and_profile_version.sql',
    '0018_routes_description_and_permissions.sql'
  ];
  return migrationFiles
    .map((file) => path.join(__dirname, 'migrations', file))
    .filter((filePath) => fs.existsSync(filePath))
    .map((filePath) => fs.readFileSync(filePath, 'utf8'))
    .join('\n');
}

export async function runMigrations(db: DbExecutor): Promise<void> {
  const sql = readInitMigrationSql();
  await db.query(sql);
}
