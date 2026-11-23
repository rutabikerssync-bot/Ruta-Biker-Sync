import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';

/**
 * Corte 2A de redundancia BLE ↔ 4G: chat y señales efímeros por rodada.
 *
 * No persiste en PostgreSQL, Redis ni disco: el búfer vive en memoria y se purga
 * por TTL, por límite de tamaño y al cerrar la rodada (Regla R12). BLE sigue siendo
 * el canal primario; este transporte solo añade mensajes cuando el convoy se estira.
 */

const MAX_ITEMS_PER_RIDE = 200;
const MAX_TTL_SECONDS = 120;
const BROADCAST_TARGET = 0xffff;

const RideIdSchema = z.string().uuid();
const ShortIdSchema = z.number().int().min(1).max(32_768);
const TargetShortIdSchema = z.number().int().min(0).max(65_535);

const ChatPayloadSchema = z.object({
  kind: z.literal('chat'),
  messageId: z.string().uuid(),
  senderShortId: ShortIdSchema,
  targetShortId: TargetShortIdSchema.default(BROADCAST_TARGET),
  text: z.string().trim().min(1).max(500),
  ttlSeconds: z.number().int().min(5).max(MAX_TTL_SECONDS).default(60)
});

const SignalPayloadSchema = z.object({
  kind: z.literal('signal'),
  messageId: z.string().trim().min(8).max(120),
  senderShortId: ShortIdSchema,
  signalKind: z.number().int().min(1).max(5),
  sequence: z.number().int().min(0).max(2_147_483_647),
  ttlSeconds: z.number().int().min(5).max(MAX_TTL_SECONDS).default(60)
});

const CommsPayloadSchema = z.discriminatedUnion('kind', [ChatPayloadSchema, SignalPayloadSchema]);

export type RideCommsAccess = 'ok' | 'not_found' | 'forbidden';

export interface RideCommsItem {
  id: string;
  kind: 'chat' | 'signal';
  senderShortId: number;
  targetShortId: number | null;
  text: string | null;
  signalKind: number | null;
  sequence: number | null;
  source: '4g';
  createdAt: number;
  expiresAt: number;
}

interface RideCommsDeps {
  getUserId: (req: FastifyRequest, reply: FastifyReply) => string | null;
  checkAccess: (rideId: string, uid: string) => Promise<RideCommsAccess>;
  now?: () => number;
}

const buffers = new Map<string, RideCommsItem[]>();

function purgeExpired(rideId: string, now: number): RideCommsItem[] {
  const alive = (buffers.get(rideId) ?? []).filter((item) => item.expiresAt > now);
  if (alive.length > 0) {
    buffers.set(rideId, alive);
  } else {
    buffers.delete(rideId);
  }
  return alive;
}

/** Purga explícita al cerrar la rodada. No toca ninguna tabla. */
export function purgeRideComms(rideId: string): void {
  buffers.delete(rideId);
}

export function registerRideCommsRoutes(app: FastifyInstance, deps: RideCommsDeps): void {
  const now = deps.now ?? Date.now;

  app.post('/v1/rides/:id/comms', async (req, reply) => {
    const uid = deps.getUserId(req, reply);
    if (!uid) return;

    const { id: rideId } = req.params as { id: string };
    if (!RideIdSchema.safeParse(rideId).success) {
      return reply.code(400).send({ error: 'INVALID_RIDE_ID' });
    }

    const access = await deps.checkAccess(rideId, uid);
    if (access === 'not_found') {
      return reply.code(404).send({ error: 'RIDE_NOT_FOUND' });
    }
    if (access === 'forbidden') {
      return reply.code(403).send({ error: 'NOT_IN_RIDE' });
    }

    const parsed = CommsPayloadSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }

    const payload = parsed.data;
    const current = purgeExpired(rideId, now());
    const existing = current.find((item) => item.id === payload.messageId);
    if (existing) {
      return reply.code(200).send({ success: true, duplicate: true, item: existing });
    }

    const createdAt = now();
    const item: RideCommsItem = {
      id: payload.messageId,
      kind: payload.kind,
      senderShortId: payload.senderShortId,
      targetShortId: payload.kind === 'chat' ? payload.targetShortId : null,
      text: payload.kind === 'chat' ? payload.text : null,
      signalKind: payload.kind === 'signal' ? payload.signalKind : null,
      sequence: payload.kind === 'signal' ? payload.sequence : null,
      source: '4g',
      createdAt,
      expiresAt: createdAt + payload.ttlSeconds * 1_000
    };

    current.push(item);
    while (current.length > MAX_ITEMS_PER_RIDE) {
      current.shift();
    }
    buffers.set(rideId, current);

    return reply.code(200).send({ success: true, duplicate: false, item });
  });

  app.get('/v1/rides/:id/comms', async (req, reply) => {
    const uid = deps.getUserId(req, reply);
    if (!uid) return;

    const { id: rideId } = req.params as { id: string };
    if (!RideIdSchema.safeParse(rideId).success) {
      return reply.code(400).send({ error: 'INVALID_RIDE_ID' });
    }

    const access = await deps.checkAccess(rideId, uid);
    if (access === 'not_found') {
      return reply.code(404).send({ error: 'RIDE_NOT_FOUND' });
    }
    if (access === 'forbidden') {
      return reply.code(403).send({ error: 'NOT_IN_RIDE' });
    }

    const query = req.query as { since?: string; limit?: string };
    const since = Math.max(0, Number.parseInt(query.since ?? '0', 10) || 0);
    const limit = Math.min(MAX_ITEMS_PER_RIDE, Math.max(1, Number.parseInt(query.limit ?? '100', 10) || 100));

    const items = purgeExpired(rideId, now())
      .filter((item) => item.createdAt > since)
      .slice(0, limit);

    return reply.code(200).send({
      rideId,
      generatedAt: new Date(now()).toISOString(),
      source: '4g',
      items
    });
  });
}
