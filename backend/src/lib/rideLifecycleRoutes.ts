import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import type { Pool, PoolClient } from 'pg';
import { z } from 'zod';
import type { DbExecutor } from '../db/pool.js';
import { withUserDb } from '../db/withUserDb.js';

const uuidSchema = z.string().uuid();

type RouteLifecycleColumn =
  | 'outbound_started_at'
  | 'destination_reached_at'
  | 'return_started_at'
  | 'completed_at';

type RouteRecord = {
  id: string;
  group_id: string;
  starts_at: string;
  outbound_started_at: string | null;
  destination_reached_at: string | null;
  return_started_at: string | null;
  completed_at: string | null;
};

const routeColumns = `id, group_id, starts_at, outbound_started_at,
  destination_reached_at, return_started_at, completed_at`;

function routeId(req: FastifyRequest): string {
  return (req.params as { id: string }).id;
}

function userId(req: FastifyRequest, reply: FastifyReply): string | null {
  const uid = req.headers['x-user-id'];
  if (typeof uid !== 'string' || !uuidSchema.safeParse(uid).success) {
    void reply.code(401).send({ error: 'UNAUTHORIZED' });
    return null;
  }
  return uid;
}

/** Guardas de RSVP: solo estado y reloj del servidor, nunca reloj del teléfono. */
export async function validateRsvpLifecycle(
  client: PoolClient,
  routeId: string,
  current: string | undefined,
  target: string
): Promise<{ statusCode: number; error: string } | null> {
  if (current === 'no_go' && target === 'confirmed') {
    const { rows } = await client.query(
      `SELECT (starts_at - interval '1 hour' <= now() AND now() < starts_at) AS allowed
       FROM club_routes WHERE id = $1`,
      [routeId]
    );
    if (!rows[0]?.allowed) return { statusCode: 409, error: 'RSVP_LOCKED' };
  }
  if (target === 'home') {
    const { rows } = await client.query(
      `SELECT return_started_at FROM club_routes WHERE id = $1`,
      [routeId]
    );
    if (!rows[0]?.return_started_at) return { statusCode: 409, error: 'RETURN_NOT_STARTED' };
  }
  return null;
}

export function registerRideLifecycleRoutes(
  app: FastifyInstance,
  _db: DbExecutor,
  getRlsPool: () => Pool | null
) {
  async function loadLeaderRoute(
    client: PoolClient,
    routeId: string,
    uid: string
  ): Promise<RouteRecord | null | 'forbidden'> {
    const res = await client.query<RouteRecord>(
      `SELECT ${routeColumns} FROM club_routes WHERE id = $1`,
      [routeId]
    );
    const route = res.rows[0];
    if (!route) return null;
    const leader = await client.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [route.group_id, uid]
    );
    return leader.rows.length > 0 ? route : 'forbidden';
  }

  app.post('/v1/routes/:id/arrive', async (req, reply) => {
    const uid = userId(req, reply);
    if (!uid) return;
    const id = routeId(req);
    if (!uuidSchema.safeParse(id).success) {
      return reply.code(400).send({ error: 'INVALID_ROUTE_ID' });
    }
    const rls = getRlsPool();
    if (!rls) return reply.code(503).send({ error: 'DB_UNAVAILABLE' });

    return withUserDb(rls, uid, async (client) => {
      const own = await client.query(
        `SELECT status, on_site_at FROM club_route_rsvps
         WHERE route_id = $1 AND user_id = $2 FOR UPDATE`,
        [id, uid]
      );
      const row = own.rows[0];
      if (!row) return reply.code(404).send({ error: 'ROUTE_NOT_FOUND' });
      if (row.status !== 'confirmed') {
        return reply.code(409).send({ error: 'RSVP_NOT_CONFIRMED' });
      }

      const saved = await client.query(
        `UPDATE club_route_rsvps
         SET on_site_at = COALESCE(on_site_at, now()),
             updated_at = CASE WHEN on_site_at IS NULL THEN now() ELSE updated_at END
         WHERE route_id = $1 AND user_id = $2
         RETURNING route_id, user_id, status, on_site_at`,
        [id, uid]
      );
      return reply.code(row.on_site_at ? 200 : 201).send({
        rsvp: saved.rows[0],
        changed: !row.on_site_at
      });
    });
  });

  async function leaderAction(
    column: RouteLifecycleColumn,
    req: FastifyRequest,
    reply: FastifyReply
  ) {
    const uid = userId(req, reply);
    if (!uid) return;
    const id = routeId(req);
    if (!uuidSchema.safeParse(id).success) {
      return reply.code(400).send({ error: 'INVALID_ROUTE_ID' });
    }
    const rls = getRlsPool();
    if (!rls) return reply.code(503).send({ error: 'DB_UNAVAILABLE' });

    return withUserDb(rls, uid, async (client) => {
      const route = await loadLeaderRoute(client, id, uid);
      if (!route) return reply.code(404).send({ error: 'ROUTE_NOT_FOUND' });
      if (route === 'forbidden') return reply.code(403).send({ error: 'LEADER_REQUIRED' });
      if (route[column]) return reply.code(200).send({ route, changed: false });

      if (column === 'outbound_started_at') {
        const ready = await client.query(
          `SELECT count(*)::int AS confirmed,
                  count(*) FILTER (WHERE on_site_at IS NOT NULL)::int AS on_site
           FROM club_route_rsvps WHERE route_id = $1 AND status = 'confirmed'`,
          [id]
        );
        if (ready.rows[0].confirmed === 0 || ready.rows[0].confirmed !== ready.rows[0].on_site) {
          return reply.code(409).send({
            error: 'ROUTE_NOT_READY',
            confirmed: ready.rows[0].confirmed,
            onSite: ready.rows[0].on_site
          });
        }
      }

      const prerequisite: Partial<Record<RouteLifecycleColumn, keyof RouteRecord>> = {
        destination_reached_at: 'outbound_started_at',
        return_started_at: 'destination_reached_at',
        completed_at: 'return_started_at'
      };
      const required = prerequisite[column];
      if (required && !route[required]) {
        const error = column === 'return_started_at'
          ? 'DESTINATION_NOT_REACHED'
          : column === 'completed_at'
            ? 'RETURN_NOT_STARTED'
            : 'ROUTE_NOT_STARTED';
        return reply.code(409).send({ error });
      }

      const updated = await client.query(
        `UPDATE club_routes
         SET ${column} = now(), updated_at = now()
         WHERE id = $1
         RETURNING ${routeColumns}`,
        [id]
      );
      return reply.code(201).send({ route: updated.rows[0], changed: true });
    });
  }

  app.post('/v1/routes/:id/start', async (req, reply) =>
    leaderAction('outbound_started_at', req, reply)
  );
  app.post('/v1/routes/:id/arrive-destination', async (req, reply) =>
    leaderAction('destination_reached_at', req, reply)
  );
  app.post('/v1/routes/:id/return', async (req, reply) =>
    leaderAction('return_started_at', req, reply)
  );
  app.post('/v1/routes/:id/complete', async (req, reply) =>
    leaderAction('completed_at', req, reply)
  );
}
