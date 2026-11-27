import { createHmac } from 'node:crypto';
import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import type { DbExecutor } from '../db/pool.js';
import {
  issueRideManifest,
  type CredentialKeyring,
  type RideManifestParticipant
} from './credentials.js';

const COMMUNITY_RIDE_ID = 'ride-live-01';
const COMMUNITY_GROUP_ID = 'community';
const COMMUNITY_TTL_SECONDS = 300; // 5 minutos

function deriveCommunitySecretHex(env: NodeJS.ProcessEnv): string {
  const explicit = env.COMMUNITY_RIDE_SECRET_HEX?.trim();
  if (explicit && /^[0-9a-fA-F]{64}$/.test(explicit)) {
    return explicit.toLowerCase();
  }
  const seed =
    env.AUTH_TOKEN_KEYS_JSON?.trim() ||
    env.AUTH_TOKEN_SECRET?.trim() ||
    'rbs-community-free-ride-seed';
  return createHmac('sha256', seed).update('community-free-ride-v1').digest('hex');
}

function parseBytea(val: unknown): Buffer | null {
  if (!val) return null;
  if (Buffer.isBuffer(val)) return val;
  if (val instanceof Uint8Array) return Buffer.from(val);
  if (typeof val === 'string') {
    const clean = val.startsWith('\\x') ? val.slice(2) : val;
    if (/^[0-9a-fA-F]+$/.test(clean) && clean.length % 2 === 0) {
      return Buffer.from(clean, 'hex');
    }
    return Buffer.from(val, 'base64');
  }
  return null;
}

export function registerCommunityManifestRoute(
  app: FastifyInstance,
  db: DbExecutor,
  credentialKeyring: CredentialKeyring,
  requireUserId: (req: FastifyRequest, reply: FastifyReply) => string | null,
  env: NodeJS.ProcessEnv = process.env
) {
  app.get('/v1/community/manifest', async (req: FastifyRequest, reply: FastifyReply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const meRes = await db.query<{ id: string; profile_visibility: string | null }>(
      `SELECT id, profile_visibility FROM users WHERE id = $1`,
      [uid]
    );
    const me = meRes.rows[0];
    if (!me) {
      return reply.code(404).send({ error: 'USER_NOT_FOUND' });
    }
    if ((me.profile_visibility ?? 'PUBLIC') !== 'PUBLIC') {
      return reply.code(403).send({
        error: 'COMMUNITY_VISIBILITY_REQUIRED',
        message: 'Activa visibilidad PUBLIC en tu perfil para participar en Ruta Libre comunitaria.'
      });
    }

    const publicUsersRes = await db.query<{
      id: string;
      public_key: unknown;
      sos_public_key: unknown;
    }>(
      `SELECT id, public_key, sos_public_key
       FROM users
       WHERE COALESCE(profile_visibility, 'PUBLIC') = 'PUBLIC'
         AND status = 'active'
       ORDER BY created_at ASC
       LIMIT 200`
    );

    const sosPublicKeys: Record<string, string> = {};
    const participants: RideManifestParticipant[] = publicUsersRes.rows.map((row) => {
      const pkBuf = parseBytea(row.public_key);
      const pkBase64 =
        pkBuf && pkBuf.length > 0
          ? pkBuf.toString('base64')
          : Buffer.from(String(row.id), 'utf8').toString('base64');
      const sosKey = parseBytea(row.sos_public_key);
      if (sosKey?.length === 32) {
        sosPublicKeys[String(row.id)] = sosKey.toString('base64');
      }
      return {
        uid: String(row.id),
        gid: COMMUNITY_GROUP_ID,
        statusKind: 'full',
        pk: pkBase64
      };
    });

    const signed = issueRideManifest(
      {
        rideId: COMMUNITY_RIDE_ID,
        hostGroupId: COMMUNITY_GROUP_ID,
        hostLeaderId: uid,
        participants,
        rideSecretKeyHex: deriveCommunitySecretHex(env),
        sosPublicKeys,
        ttlSeconds: COMMUNITY_TTL_SECONDS
      },
      credentialKeyring
    );

    return reply.code(200).send({
      ...signed,
      publicKeys: credentialKeyring.getPublicKeyMap()
    });
  });
}
