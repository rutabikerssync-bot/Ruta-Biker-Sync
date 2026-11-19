import type { Pool, PoolClient } from 'pg';

/**
 * Ejecuta `fn` con la identidad del usuario inyectada en la sesión de PostgreSQL.
 *
 * `app.user_id` se fija con `set_config(..., true)`: el valor es LOCAL a la
 * transacción, por lo que se elimina al hacer COMMIT/ROLLBACK y la conexión
 * vuelve al pool sin identidad residual. Esto evita fugas cross-tenant cuando
 * una misma conexión atiende varios requests.
 */
export async function withUserDb<T>(
  pool: Pool,
  uid: string,
  fn: (client: PoolClient) => Promise<T>
): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query('SELECT set_config($1, $2, true)', ['app.user_id', uid]);
    const out = await fn(client);
    await client.query('COMMIT');
    return out;
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}
