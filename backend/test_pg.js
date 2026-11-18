import pg from 'pg';
const { Pool } = pg;

async function tryConnect(user, password, port, db) {
  try {
    const pool = new Pool({ connectionString: `postgres://${user}:${password}@127.0.0.1:${port}/${db}` });
    const res = await pool.query('SELECT 1');
    console.log(`SUCCESS: ${user} on ${port}/${db}`);
    await pool.end();
  } catch (e) {
    console.log(`FAILED: ${user} on ${port}/${db} -> ${e.message}`);
  }
}
async function run() {
  await tryConnect('postgres', 'postgres', 5432, 'postgres');
  await tryConnect('postgres', 'admin', 5432, 'postgres');
  await tryConnect('postgres', '123456', 5432, 'postgres');
  await tryConnect('postgres', '1234', 5432, 'postgres');
  await tryConnect('postgres', 'root', 5432, 'postgres');
  await tryConnect('rbs', 'rbs_dev_password', 5432, 'rbs');
  await tryConnect('postgres', 'rbs_dev_password', 5432, 'rbs');
}
run();
