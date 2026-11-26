import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';

describe('Fase 1 Mi Club — Cargos Personalizados y Decisiones Secretas', () => {
  let db: DbExecutor;
  let app: FastifyInstance;
  let isDbOnline = false;

  const leaderId = randomUUID();
  const fullMemberId = randomUUID();
  const prospectId = randomUUID();
  const guestId = randomUUID();
  const groupId = randomUUID();

  beforeAll(async () => {
    try {
      const pool = createPgPool();
      await pool.query('SELECT 1');
      await pool.query(readInitMigrationSql());
      db = pool;
      isDbOnline = true;

      for (const [id, email, name] of [
        [leaderId, `leader-${leaderId}@test.rbs`, 'Líder Principal'],
        [fullMemberId, `full-${fullMemberId}@test.rbs`, 'Full Rider'],
        [prospectId, `prospect-${prospectId}@test.rbs`, 'Prospecto'],
        [guestId, `guest-${guestId}@test.rbs`, 'Invitado']
      ] as const) {
        await db.query(
          `INSERT INTO users (id, email, country_code, display_name, account_type)
           VALUES ($1, $2, 'VE', $3, 'leader') ON CONFLICT (id) DO NOTHING`,
          [id, email, name]
        );
      }

      await db.query(
        `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader)
         VALUES ($1, 'VE', $2, $3, $4, $5)`,
        [groupId, `Club Decisiones ${groupId.slice(0, 6)}`, `clubdecisiones${groupId.slice(0, 6)}`, Buffer.alloc(8, 1), leaderId]
      );
      await db.query(
        `INSERT INTO group_leaders (group_id, user_id, is_principal) VALUES ($1, $2, true)`,
        [groupId, leaderId]
      );
      await db.query(
        `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state)
         VALUES ($1, $2, $3, 'full', 'accepted'),
                ($4, $2, $5, 'prospect', 'accepted'),
                ($6, $2, $7, 'guest', 'accepted')`,
        [randomUUID(), groupId, fullMemberId, randomUUID(), prospectId, randomUUID(), guestId]
      );

      app = await buildApp({ db });
      await app.ready();
    } catch (err) {
      console.warn('[club_custom_roles_decisions] PostgreSQL offline, skipping integration:', err);
    }
  });

  afterAll(async () => {
    if (app) await app.close();
    if (db && isDbOnline) await db.end();
  });

  it('permite a la directiva crear cargo personalizado y prohíbe asignarlo a guest (P7)', async () => {
    if (!isDbOnline) return;

    const createRes = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/custom-roles`,
      headers: { 'x-user-id': leaderId },
      payload: { title: 'Fotógrafo de Ruta', badgeIcon: 'camera', canCreateRide: false }
    });
    expect(createRes.statusCode).toBe(201);
    const roleId = createRes.json().id as string;

    const assignGuest = await app.inject({
      method: 'PATCH',
      url: `/v1/groups/${groupId}/members/${guestId}/custom-role`,
      headers: { 'x-user-id': leaderId },
      payload: { customRoleId: roleId }
    });
    expect(assignGuest.statusCode).toBe(403);
    expect(assignGuest.json()).toHaveProperty('error', 'GUEST_CANNOT_HOLD_ROLE');

    const assignFull = await app.inject({
      method: 'PATCH',
      url: `/v1/groups/${groupId}/members/${fullMemberId}/custom-role`,
      headers: { 'x-user-id': leaderId },
      payload: { customRoleId: roleId }
    });
    expect(assignFull.statusCode).toBe(200);
  });

  it('oculta votaciones restringidas a no-elegibles, garantiza secreto y bloquea voto doble', async () => {
    if (!isDbOnline) return;

    // Decisión solo para DIRECTIVA y FOUNDERS
    const createRes = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/decisions`,
      headers: { 'x-user-id': leaderId },
      payload: {
        title: 'Regla interna de convoy',
        category: 'governance',
        options: ['Aprobar', 'Rechazar'],
        allowedAudiences: ['DIRECTIVA', 'FOUNDERS'],
        minQuorumPercent: 50
      }
    });
    expect(createRes.statusCode).toBe(201);
    const decisionId = createRes.json().id as string;

    // Full Member y Prospecto NO deben ver la decisión en su lista
    const fullList = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}/decisions`,
      headers: { 'x-user-id': fullMemberId }
    });
    expect(fullList.statusCode).toBe(200);
    expect(fullList.json().decisions).toHaveLength(0);

    // Full Member tampoco puede votar directo por endpoint
    const forbiddenVote = await app.inject({
      method: 'POST',
      url: `/v1/decisions/${decisionId}/cast`,
      headers: { 'x-user-id': fullMemberId },
      payload: { optionIndex: 0 }
    });
    expect(forbiddenVote.statusCode).toBe(403);

    // Líder vota secreto (opción 0)
    const cast1 = await app.inject({
      method: 'POST',
      url: `/v1/decisions/${decisionId}/cast`,
      headers: { 'x-user-id': leaderId },
      payload: { optionIndex: 0 }
    });
    expect(cast1.statusCode).toBe(200);

    // Segundo voto del mismo líder -> 409 ALREADY_VOTED
    const castDup = await app.inject({
      method: 'POST',
      url: `/v1/decisions/${decisionId}/cast`,
      headers: { 'x-user-id': leaderId },
      payload: { optionIndex: 1 }
    });
    expect(castDup.statusCode).toBe(409);

    // Mientras está abierta, tallies es null y hasVoted es true
    const openList = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}/decisions`,
      headers: { 'x-user-id': leaderId }
    });
    expect(openList.json().decisions[0].hasVoted).toBe(true);
    expect(openList.json().decisions[0].tallies).toBeNull();

    // Cerrar y verificar resultado agregado anónimo
    const closeRes = await app.inject({
      method: 'POST',
      url: `/v1/decisions/${decisionId}/close`,
      headers: { 'x-user-id': leaderId }
    });
    expect(closeRes.statusCode).toBe(200);

    const closedList = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}/decisions`,
      headers: { 'x-user-id': leaderId }
    });
    const closedDecision = closedList.json().decisions[0];
    expect(closedDecision.state).toBe('closed');
    expect(closedDecision.tallies).toEqual([1, 0]);
    expect(closedDecision.winningOptionIndex).toBe(0);
    expect(closedDecision.quorumMet).toBe(true);
  });

  it('permite votación abierta a FULL_MEMBERS y PROSPECTS manteniendo fuera a GUEST', async () => {
    if (!isDbOnline) return;

    const createRes = await app.inject({
      method: 'POST',
      url: `/v1/groups/${groupId}/decisions`,
      headers: { 'x-user-id': leaderId },
      payload: {
        title: 'Destino domingo: Colonia Tovar vs Galipán',
        category: 'route',
        options: ['Colonia Tovar', 'Galipán'],
        allowedAudiences: ['FULL_MEMBERS', 'PROSPECTS']
      }
    });
    expect(createRes.statusCode).toBe(201);
    const decisionId = createRes.json().id as string;

    const prospectVote = await app.inject({
      method: 'POST',
      url: `/v1/decisions/${decisionId}/cast`,
      headers: { 'x-user-id': prospectId },
      payload: { optionIndex: 1 }
    });
    expect(prospectVote.statusCode).toBe(200);

    const guestList = await app.inject({
      method: 'GET',
      url: `/v1/groups/${groupId}/decisions`,
      headers: { 'x-user-id': guestId }
    });
    expect(guestList.json().decisions).toHaveLength(0);
  });
});
