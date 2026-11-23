import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import { issueSessionToken } from '../src/lib/auth.js';

function resolveTestDatabaseUrl(): string {
  return process.env.PG_TEST_URL || 'postgres://rbs:rbs_dev_password@127.0.0.1:5433/rbs_test';
}

describe('Redundancia híbrida — chat y señales efímeras por rodada (Corte 2A)', () => {
  let app: FastifyInstance;
  let db: DbExecutor;
  let pool: any;

  let leaderId: string;
  let memberId: string;
  let outsiderId: string;
  let groupId: string;
  let aclRideId: string;
  let ttlRideId: string;
  let emptyRideId: string;
  let limitRideId: string;
  let memberToken: string;
  let outsiderToken: string;

  async function createRide(rideId: string): Promise<void> {
    await db.query(
      `INSERT INTO rides (id, host_group, host_leader, title, state, starts_at)
       VALUES ($1, $2, $3, 'Rodada comms', 'active', now())`,
      [rideId, groupId, leaderId]
    );
    await db.query(
      `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind)
       VALUES ($1, $2, $3, 'full'), ($1, $4, $3, 'full')`,
      [rideId, leaderId, groupId, memberId]
    );
  }

  function chatPayload(rideSuffix = '', ttlSeconds = 60) {
    return {
      kind: 'chat',
      messageId: randomUUID(),
      senderShortId: 12982,
      targetShortId: 65535,
      text: `mensaje-4g-${rideSuffix || 'ac'}`,
      ttlSeconds
    };
  }

  beforeAll(async () => {
    const poolHandle = createPgPool(resolveTestDatabaseUrl());
    await poolHandle.query('SELECT 1');
    await poolHandle.query(readInitMigrationSql());
    await poolHandle.query(`
      TRUNCATE TABLE
        ephemeral_telemetry,
        sos_events,
        ride_participants,
        rides,
        group_leaders,
        memberships,
        invitations,
        groups,
        consents,
        users
      RESTART IDENTITY CASCADE
    `);
    pool = poolHandle;
    db = poolHandle;
    app = await buildApp({ db, logger: false });

    leaderId = randomUUID();
    memberId = randomUUID();
    outsiderId = randomUUID();
    groupId = randomUUID();
    aclRideId = randomUUID();
    ttlRideId = randomUUID();
    emptyRideId = randomUUID();
    limitRideId = randomUUID();

    await db.query(
      `INSERT INTO users (id, email, display_name, country_code, account_type, status)
       VALUES
         ($1, 'comms-leader@rutabiker.com', 'Líder Comms', 'VE', 'leader', 'active'),
         ($2, 'comms-member@rutabiker.com', 'Miembro Comms', 'VE', 'member', 'active'),
         ($3, 'comms-outsider@rutabiker.com', 'Foráneo Comms', 'VE', 'member', 'active')`,
      [leaderId, memberId, outsiderId]
    );

    await db.query(
      `INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader)
       VALUES ($1, 'VE', 'Club Comms', 'club comms', decode('0102030405060708', 'hex'), $2)`,
      [groupId, leaderId]
    );

    await db.query(
      `INSERT INTO memberships (id, group_id, user_id, status_kind, invite_state, accepted_at)
       VALUES (gen_random_uuid(), $1, $2, 'full', 'accepted', now()),
              (gen_random_uuid(), $1, $3, 'full', 'accepted', now())`,
      [groupId, leaderId, memberId]
    );

    await createRide(aclRideId);
    await createRide(ttlRideId);
    await createRide(emptyRideId);
    await createRide(limitRideId);

    memberToken = issueSessionToken(memberId, 'access').token;
    outsiderToken = issueSessionToken(outsiderId, 'access').token;
  });

  afterAll(async () => {
    if (app) await app.close();
    if (pool) await pool.end();
  });

  it('1. ACL: participante publica y lee; foráneo recibe 403', async () => {
    const payload = chatPayload('acl');
    const post = await app.inject({
      method: 'POST',
      url: `/v1/rides/${aclRideId}/comms`,
      headers: { authorization: `Bearer ${memberToken}` },
      payload
    });
    expect(post.statusCode).toBe(200);
    expect(post.json().duplicate).toBe(false);

    const read = await app.inject({
      method: 'GET',
      url: `/v1/rides/${aclRideId}/comms`,
      headers: { authorization: `Bearer ${memberToken}` }
    });
    expect(read.statusCode).toBe(200);
    expect(read.json().items.map((i: any) => i.id)).toContain(payload.messageId);

    const deniedPost = await app.inject({
      method: 'POST',
      url: `/v1/rides/${aclRideId}/comms`,
      headers: { authorization: `Bearer ${outsiderToken}` },
      payload: chatPayload('denegado')
    });
    expect(deniedPost.statusCode).toBe(403);

    const deniedRead = await app.inject({
      method: 'GET',
      url: `/v1/rides/${aclRideId}/comms`,
      headers: { authorization: `Bearer ${outsiderToken}` }
    });
    expect(deniedRead.statusCode).toBe(403);
  });

  it('2. TTL: un mensaje vencido deja de devolverse', async () => {
    const payload = chatPayload('ttl', 5);
    const post = await app.inject({
      method: 'POST',
      url: `/v1/rides/${ttlRideId}/comms`,
      headers: { authorization: `Bearer ${memberToken}` },
      payload
    });
    expect(post.statusCode).toBe(200);

    await new Promise((resolve) => setTimeout(resolve, 5_100));

    const read = await app.inject({
      method: 'GET',
      url: `/v1/rides/${ttlRideId}/comms`,
      headers: { authorization: `Bearer ${memberToken}` }
    });
    expect(read.statusCode).toBe(200);
    expect(read.json().items.map((i: any) => i.id)).not.toContain(payload.messageId);
  }, 15_000);

  it('3. Idempotencia: el mismo messageId no duplica el búfer', async () => {
    const payload = chatPayload('dedupe');
    const url = `/v1/rides/${aclRideId}/comms`;
    const headers = { authorization: `Bearer ${memberToken}` };

    expect((await app.inject({ method: 'POST', url, headers, payload })).statusCode).toBe(200);
    const second = await app.inject({ method: 'POST', url, headers, payload });
    expect(second.statusCode).toBe(200);
    expect(second.json().duplicate).toBe(true);

    const read = await app.inject({ method: 'GET', url, headers });
    expect(read.json().items.filter((i: any) => i.id === payload.messageId)).toHaveLength(1);
  });

  it('4. Rodada sin tráfico devuelve 200 con array vacío', async () => {
    const read = await app.inject({
      method: 'GET',
      url: `/v1/rides/${emptyRideId}/comms`,
      headers: { authorization: `Bearer ${memberToken}` }
    });
    expect(read.statusCode).toBe(200);
    expect(read.json().items).toEqual([]);
  });

  it('5. Límite: con más de 200 items se descartan los más viejos', async () => {
    const url = `/v1/rides/${limitRideId}/comms`;
    const headers = { authorization: `Bearer ${memberToken}` };
    const ids: string[] = [];

    for (let i = 0; i < 205; i++) {
      const payload = chatPayload(`limite-${i}`);
      ids.push(payload.messageId);
      const post = await app.inject({ method: 'POST', url, headers, payload });
      expect(post.statusCode).toBe(200);
    }

    const read = await app.inject({
      method: 'GET',
      url: `${url}?limit=200`,
      headers
    });
    expect(read.statusCode).toBe(200);
    const returned = read.json().items.map((i: any) => i.id as string);
    expect(returned).toHaveLength(200);
    expect(returned).not.toContain(ids[0]);
    expect(returned).toContain(ids[ids.length - 1]);
  });

  it('6. Señal rápida: el contrato acepta kind=signal con signalKind 1..5', async () => {
    const post = await app.inject({
      method: 'POST',
      url: `/v1/rides/${emptyRideId}/comms`,
      headers: { authorization: `Bearer ${memberToken}` },
      payload: {
        kind: 'signal',
        messageId: `signal-${emptyRideId}-1`,
        senderShortId: 12243,
        signalKind: 2,
        sequence: 7,
        ttlSeconds: 30
      }
    });
    expect(post.statusCode).toBe(200);
    expect(post.json().item.kind).toBe('signal');

    const invalid = await app.inject({
      method: 'POST',
      url: `/v1/rides/${emptyRideId}/comms`,
      headers: { authorization: `Bearer ${memberToken}` },
      payload: {
        kind: 'signal',
        messageId: `signal-${emptyRideId}-invalid`,
        senderShortId: 12243,
        signalKind: 99,
        sequence: 8
      }
    });
    expect(invalid.statusCode).toBe(400);
  });
});
