import pg from 'pg';
const pool = new pg.Pool({ connectionString: 'postgres://rbs:rbs_dev_password@127.0.0.1:5433/rbs' });
try {
  const res = await pool.query('SELECT 1 as connected');
  console.log('PostgreSQL from Windows:', res.rows[0]);
  await pool.end();
} catch (e) {
  console.error('PostgreSQL error from Windows:', e.message);
  process.exit(1);
}

