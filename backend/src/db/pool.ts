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
  // Runtime del servidor: priorizar DATABASE_URL. PG_TEST_URL queda para suites de tests
  // (que resuelven su propia conexión) y no debe desviar el backend de desarrollo.
  if (process.env.DATABASE_URL || process.env.PG_TEST_URL) {
    return (process.env.DATABASE_URL ?? process.env.PG_TEST_URL)!;
  }
  for (const filename of ['.env', '.env.example']) {
    const filePath = path.join(process.cwd(), filename);
    if (fs.existsSync(filePath)) {
      const content = fs.readFileSync(filePath, 'utf8');
      const dbUrlMatch = content.match(/^DATABASE_URL=(.+)$/m);
      if (dbUrlMatch?.[1]) {
        return dbUrlMatch[1].trim();
      }
      const pgTestMatch = content.match(/^PG_TEST_URL=(.+)$/m);
      if (pgTestMatch?.[1]) {
        return pgTestMatch[1].trim();
      }
    }
  }
  return 'postgres://rbs:rbs_dev_password@localhost:5433/rbs';
}

function resolveTestDefaultConnectionString(): string {
  if (process.env.PG_TEST_URL) return process.env.PG_TEST_URL;
  for (const filename of ['.env', '.env.example']) {
    const filePath = path.join(process.cwd(), filename);
    if (fs.existsSync(filePath)) {
      const content = fs.readFileSync(filePath, 'utf8');
      const pgTestMatch = content.match(/^PG_TEST_URL=(.+)$/m);
      if (pgTestMatch?.[1]) return pgTestMatch[1].trim();
    }
  }
  return 'postgres://rbs:rbs_dev_password@localhost:5433/rbs_test';
}

export function createPgPool(connectionString?: string): Pool {

  const resolvedConnectionString = connectionString
    ?? (process.env.VITEST ? resolveTestDefaultConnectionString() : resolveDefaultConnectionString());
  // Salvaguarda: las suites de tests no deben truncar nunca la BD principal.
  // Vitest define VITEST=true; si la URL resuelta apunta a `rbs`, fallamos en vez
  // de borrar datos reales (PG_TEST_URL debe apuntar a `rbs_test`).
  if (process.env.VITEST) {
    let dbName = '';
    try { dbName = new URL(resolvedConnectionString).pathname.replace(/^\//, ''); } catch { /* URL inválida: pg la validará */ }
    if (dbName === 'rbs') {
      throw new Error('[db.pool] VITEST no puede usar la BD principal "rbs"; configura PG_TEST_URL=.../rbs_test');
    }
  }

  const createdPool = new pg.Pool({

    connectionString: resolvedConnectionString,

    max: 50,                      // Máximo de conexiones simultáneas

    min: 5,                       // Conexiones mínimas (warm-up)

    idleTimeoutMillis: 30000,     // Cerrar conexiones inactivas a los 30s

    connectionTimeoutMillis: 5000 // Timeout de adquisición de conexión

  });



  // Evita que errores en clientes idle (p. ej. reinicio de Postgres) tumben el proceso

  // por un evento 'error' no manejado en el pool.

  createdPool.on('error', (err) => {

    console.error('[db.pool] idle client error (connection dropped)', err);

  });



  return createdPool;

}

export const pool: Pool = createPgPool();

export function readInitMigrationSql(): string {
  const migrationsDir = fs.existsSync(path.join(__dirname, 'migrations'))
    ? path.join(__dirname, 'migrations')
    : path.resolve(__dirname, '..', '..', 'src', 'db', 'migrations');
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
    '0018_routes_description_and_permissions.sql',
    '0019_open_qr_invitations.sql',
    '0020_ride_lifecycle.sql',
    '0021_ride_manifest_secret.sql',
    '0022_sos_public_keys.sql',
    '0023_notification_send_guard.sql',
    '0024_user_bike_garage.sql',
    '0025_club_custom_roles_and_decisions.sql',
    '0026_promo_banners.sql',
    '0027_club_routes_waypoints.sql',
    '0028_auto_complete_home_routes.sql'
  ];
  return migrationFiles
    .map((file) => path.join(migrationsDir, file))
    .filter((filePath) => fs.existsSync(filePath))
    .map((filePath) => fs.readFileSync(filePath, 'utf8'))
    .join('\n');
}

export async function runMigrations(db: DbExecutor): Promise<void> {
  const sql = readInitMigrationSql();
  await db.query(sql);
}
