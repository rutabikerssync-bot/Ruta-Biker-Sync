import fs from 'fs';
import pg from 'pg';
const { Pool } = pg;
const pool = new Pool({ connectionString: 'postgres://rbs:rbs_dev_password@localhost:5433/rbs' });

async function uploadLogo() {
  const fileBytes = fs.readFileSync('clean_logo.jpg');
  const base64Str = 'data:image/jpeg;base64,' + fileBytes.toString('base64');
  console.log('Base64 length:', base64Str.length);

  const res = await pool.query(
    `UPDATE groups
     SET logo_url = $1
     WHERE id = '9b68b42e-ac31-43a9-9411-5b7088381097'
     RETURNING id, name, length(logo_url) as logo_len`,
    [base64Str]
  );
  console.log('Updated group:', res.rows[0]);
  await pool.end();
}
uploadLogo().catch(err => {
  console.error('Error updating logo:', err);
  process.exit(1);
});
