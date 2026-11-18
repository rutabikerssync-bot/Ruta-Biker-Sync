import pg from 'pg';
const { Pool } = pg;
const pool = new Pool({ connectionString: 'postgres://rbs:rbs_dev_password@localhost:5433/rbs' });

async function seed() {
  // 1. Usuarios
  await pool.query(`
    INSERT INTO users (id, email, display_name, country_code, account_type, status)
    VALUES
      ('acbad91d-7a4e-4fa1-a89b-a64ad8f4b7bc', 'omartinezpaz@gmail.com', 'El bufalo', 'VE', 'leader', 'active'),
      ('2d2c72d8-d567-4ebd-a9d7-509fde115d0c', 'ozmartinezpaz@gmail.com', 'The Developer', 'VE', 'member', 'active')
    ON CONFLICT (id) DO UPDATE SET
      display_name = EXCLUDED.display_name,
      country_code = EXCLUDED.country_code,
      account_type = EXCLUDED.account_type;
  `);

  // 2. Club BUFALO BEIKERS
  await pool.query(`
    INSERT INTO groups (
      id, name, name_normalized, country_code, principal_leader, tier,
      foundation_date, colors_description, club_siglas, tradition_type,
      directiva_scope, directiva_region, motto, city, status, logo_url, logo_phash
    )
    VALUES (
      '9b68b42e-ac31-43a9-9411-5b7088381097',
      'BUFALO BEIKERS',
      'BUFALOBEIKERS',
      'VE',
      'acbad91d-7a4e-4fa1-a89b-a64ad8f4b7bc',
      'RC',
      '2018-05-15',
      'Negro y Blanco',
      'RC',
      'Racing Club (Pista / Velocidad)',
      'Capítulo Autónomo Local',
      'Venezuela (Caracas, mp. Libertador)',
      'Hermandad, Respeto y Carretera',
      'Caracas',
      'active',
      NULL,
      '0000000000000000'
    )
    ON CONFLICT (id) DO UPDATE SET
      tier = 'RC',
      foundation_date = '2018-05-15',
      colors_description = 'Negro y Blanco',
      club_siglas = 'RC',
      tradition_type = 'Racing Club (Pista / Velocidad)',
      directiva_scope = 'Capítulo Autónomo Local',
      directiva_region = 'Venezuela (Caracas, mp. Libertador)',
      motto = 'Hermandad, Respeto y Carretera';
  `);

  // 3. Líder del Club
  await pool.query(`
    INSERT INTO group_leaders (group_id, user_id, is_principal, club_role)
    VALUES ('9b68b42e-ac31-43a9-9411-5b7088381097', 'acbad91d-7a4e-4fa1-a89b-a64ad8f4b7bc', true, 'Presidente')
    ON CONFLICT (group_id, user_id) DO UPDATE SET is_principal = true, club_role = 'Presidente';
  `);

  // 4. Membresía de The Developer (Prospecto, sin cargo directivo)
  await pool.query(`
    INSERT INTO memberships (
      id, group_id, user_id, status_kind, club_role, invite_state, accepted_at, paid_until
    )
    VALUES (
      'b712906f-400a-4af2-9462-6fe50a1f89a7',
      '9b68b42e-ac31-43a9-9411-5b7088381097',
      '2d2c72d8-d567-4ebd-a9d7-509fde115d0c',
      'prospect',
      NULL,
      'accepted',
      now(),
      now() + interval '30 days'
    )
    ON CONFLICT (id) DO UPDATE SET
      status_kind = 'prospect',
      club_role = NULL,
      invite_state = 'accepted';
  `);

  console.log('✅ Base de datos WSL sembrada con éxito: BUFALO BEIKERS (RC, Negro y Blanco, Fundado 15/05/2018), El bufalo (Presidente) y The Developer (Prospecto sin cargo)');
  await pool.end();
}

seed().catch(err => {
  console.error('Error seeding:', err);
  process.exit(1);
});
