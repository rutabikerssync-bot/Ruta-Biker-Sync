import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { pool, runMigrations } from '../src/db/pool.js';
import { randomUUID } from 'node:crypto';

// Utilidad para crear usuarios y grupos de prueba
const createUser = async (email: string, country = 'MX'): Promise<string> => {
  const id = randomUUID();
  await pool.query(
    `INSERT INTO users (id, email, display_name, country_code, account_type)
     VALUES ($1, $2, $3, $4, 'member')`,
    [id, email, email.split('@')[0], country]
  );
  return id;
};

const createGroup = async (name: string, leaderId: string, country = 'MX', tier = 'RC'): Promise<string> => {
  const id = randomUUID();
  await pool.query(
    `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader, tier, status)
     VALUES ($1, $2, $3, $4, $5, $6, $7, 'active')`,
    [id, country, name, name.toLowerCase(), Buffer.from('0000000000000000', 'hex'), leaderId, tier]
  );
  await pool.query(
    `INSERT INTO group_leaders (group_id, user_id, is_principal, club_role)
     VALUES ($1, $2, true, 'Presidente')`,
    [id, leaderId]
  );
  return id;
};

const createMembership = async (groupId: string, userId: string, kind = 'full'): Promise<string> => {
  const id = randomUUID();
  await pool.query(
    `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state, accepted_at)
     VALUES ($1, $2, $3, $4, 'accepted', now())`,
    [id, groupId, userId, kind]
  );
  return id;
};

describe('Fase 2 — D42-D46 Reglas de negocio', () => {
  beforeAll(async () => {
    await runMigrations(pool);
    // Asegurar que las tablas existan
    await pool.query('SELECT 1 FROM group_certifications LIMIT 1').catch(() => {
      throw new Error('La migración 0002_phase2_rules.sql no está aplicada');
    });
  });

  afterAll(async () => {
    await pool.end();
  });

  // ─────────────────────────────────────────────────────────────────
  // D42: Un líder = un solo grupo
  // ─────────────────────────────────────────────────────────────────
  it('D42: Un usuario no puede ser líder de dos grupos', async () => {
    const leaderId = await createUser(`leader.d42.${Date.now()}@test.mx`);
    await createGroup(`Grupo D42 A ${Date.now()}`, leaderId);
    const group2Id = randomUUID();
    await pool.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader, tier, status)
       VALUES ($1, 'MX', $2, $3, $4, $5, 'RC', 'active')`,
      [group2Id, `Grupo D42 B ${Date.now()}`, `grupod42b${Date.now()}`, Buffer.from('0000000000000000', 'hex'), leaderId]
    );

    await expect(
      pool.query(
        `INSERT INTO group_leaders (group_id, user_id, is_principal, club_role)
         VALUES ($1, $2, false, 'Vicepresidente')`,
        [group2Id, leaderId]
      )
    ).rejects.toThrow(/D42: El usuario .* ya es líder de otro grupo/);
  });

  // ─────────────────────────────────────────────────────────────────
  // D43: Sin membresías cruzadas (full/prospect en dos grupos)
  // ─────────────────────────────────────────────────────────────────
  it('D43: Un usuario no puede tener membresía full en dos grupos', async () => {
    const leader1Id = await createUser(`leader1.d43.${Date.now()}@test.mx`);
    const leader2Id = await createUser(`leader2.d43.${Date.now()}@test.mx`);
    const group1 = await createGroup(`Grupo D43 A ${Date.now()}`, leader1Id);
    const group2 = await createGroup(`Grupo D43 B ${Date.now()}`, leader2Id);
    const memberId = await createUser(`member.d43.${Date.now()}@test.mx`);

    await createMembership(group1, memberId, 'full');

    await expect(
      createMembership(group2, memberId, 'full')
    ).rejects.toThrow(/D43: El usuario .* ya tiene una membresía activa/);
  });

  // ─────────────────────────────────────────────────────────────────
  // D46: Directiva mínima para activar grupo
  // ─────────────────────────────────────────────────────────────────
  it('D46: Un grupo no puede activarse sin directiva mínima', async () => {
    const leaderId = await createUser(`leader.d46.${Date.now()}@test.mx`);
    const groupId = randomUUID();
    await pool.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader, tier, status)
       VALUES ($1, 'MX', $2, $3, $4, $5, 'RC', 'suspended')`,
      [groupId, `Grupo D46 ${Date.now()}`, `grupod46${Date.now()}`, Buffer.from('0000000000000000', 'hex'), leaderId]
    );

    await expect(
      pool.query(
        `UPDATE groups SET status = 'active' WHERE id = $1`,
        [groupId]
      )
    ).rejects.toThrow(/D46: El grupo .* no puede activarse sin directiva mínima/);
  });

  // ─────────────────────────────────────────────────────────────────
  // D44: Insignia de Directiva solo a miembros
  // ─────────────────────────────────────────────────────────────────
  it('D44: Un externo no puede recibir una insignia de directiva', async () => {
    const leaderId = await createUser(`leader.d44.${Date.now()}@test.mx`);
    const outsiderId = await createUser(`outsider.d44.${Date.now()}@test.mx`);
    const groupId = await createGroup(`Grupo D44 ${Date.now()}`, leaderId);

    await expect(
      pool.query(
        `INSERT INTO badges (id, family, code, issuer_group, receiver)
         VALUES ($1, 'officer', 'Presidente', $2, $3)`,
        [randomUUID(), groupId, outsiderId]
      )
    ).rejects.toThrow(/(D44|RULES R8.6).*insignia.*(directiva|Mesa Directiva).*miembro/i);
  });

  // ─────────────────────────────────────────────────────────────────
  // Jerarquía: Padrino debe ser MC certificado
  // ─────────────────────────────────────────────────────────────────
  it('Jerarquía: Un padrino debe ser MC certificado', async () => {
    const leaderId = await createUser(`leader.sponsor.${Date.now()}@test.mx`);
    const groupId = await createGroup(`Grupo Sponsor ${Date.now()}`, leaderId, 'MX', 'MG');

    await expect(
      pool.query(
        `UPDATE groups SET sponsor_group_id = $1 WHERE id = $2`,
        [groupId, groupId]
      )
    ).rejects.toThrow(/El padrino debe ser un MC certificado/);
  });

  // ─────────────────────────────────────────────────────────────────
  // Certificación: Núcleo fundador (7 clubes)
  // ─────────────────────────────────────────────────────────────────
  it('Certificación: Se registra el núcleo fundador de 7 clubes', async () => {
    const country = 'CO';
    const leaderIds: string[] = [];
    const groupIds: string[] = [];

    for (let i = 0; i < 7; i++) {
      const leaderId = await createUser(`founder.${i}.${Date.now()}@test.co`, country);
      const groupId = await createGroup(`Founder CO ${i} ${Date.now()}`, leaderId, country, 'MC');
      leaderIds.push(leaderId);
      groupIds.push(groupId);
    }

    for (let i = 0; i < 7; i++) {
      await pool.query(
        `INSERT INTO group_certifications (group_id, certification_type)
         VALUES ($1, 'mutual_founder')`,
        [groupIds[i]]
      );
      await pool.query(
        `UPDATE groups SET is_certified = true WHERE id = $1`,
        [groupIds[i]]
      );
    }

    const { rows } = await pool.query(
      `SELECT COUNT(*)::int AS count FROM group_certifications gc
       JOIN groups g ON g.id = gc.group_id
       WHERE g.country_code = $1
         AND gc.certification_type = 'mutual_founder'
         AND gc.revoked_at IS NULL`,
      [country]
    );
    expect(rows[0].count).toBe(7);
  });

  // ─────────────────────────────────────────────────────────────────
  // ride_guest_invitations: existe y acepta invitados
  // ─────────────────────────────────────────────────────────────────
  it('ride_guest_invitations: Se puede invitar a un rider a una rodada', async () => {
    const leaderId = await createUser(`leader.guest.${Date.now()}@test.mx`);
    const guestId = await createUser(`guest.${Date.now()}@test.mx`);
    const groupId = await createGroup(`Grupo Guest ${Date.now()}`, leaderId);
    const rideId = randomUUID();

    await pool.query(
      `INSERT INTO rides (id, host_group, host_leader, state)
       VALUES ($1, $2, $3, 'planned')`,
      [rideId, groupId, leaderId]
    );

    const { rows } = await pool.query(
      `INSERT INTO ride_guest_invitations (id, ride_id, user_id, invited_by, expires_at)
       VALUES ($1, $2, $3, $4, now() + interval '7 days')
       RETURNING *`,
      [randomUUID(), rideId, guestId, leaderId]
    );

    expect(rows[0].state).toBe('pending');
  });
});
