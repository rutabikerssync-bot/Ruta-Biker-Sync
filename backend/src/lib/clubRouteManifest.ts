import { randomBytes } from 'node:crypto';
import type { DbExecutor } from '../db/pool.js';
import {
  issueRideManifest,
  type CredentialKeyring,
  type RideManifestParticipant,
  type SignedRideManifest
} from './credentials.js';

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

export type ClubRouteManifestResult =
  | { kind: 'not_found' }
  | { kind: 'forbidden' }
  | { kind: 'ok'; body: SignedRideManifest & { publicKeys: Record<string, string> } };

export async function resolveClubRouteManifest(
  db: DbExecutor,
  routeId: string,
  uid: string,
  credentialKeyring: CredentialKeyring
): Promise<ClubRouteManifestResult> {
  const routeRes = await db.query<{
    id: string;
    group_id: string;
    created_by: string;
    status: string;
    completed_at: string | null;
    manifest_ride_secret_key_hex: string | null;
  }>(
    `SELECT id, group_id, created_by, status, completed_at, manifest_ride_secret_key_hex
     FROM club_routes
     WHERE id = $1`,
    [routeId]
  );
  const route = routeRes.rows[0];
  if (!route) return { kind: 'not_found' };

  let rideSecretKeyHex =
    typeof route.manifest_ride_secret_key_hex === 'string' &&
    /^[0-9a-fA-F]{64}$/.test(route.manifest_ride_secret_key_hex)
      ? route.manifest_ride_secret_key_hex.toLowerCase()
      : null;

  if (!rideSecretKeyHex) {
    const generated = randomBytes(32).toString('hex');
    const up = await db.query<{ manifest_ride_secret_key_hex: string }>(
      `UPDATE club_routes
       SET manifest_ride_secret_key_hex = COALESCE(manifest_ride_secret_key_hex, $2),
           manifest_issued_at = COALESCE(manifest_issued_at, now())
       WHERE id = $1
       RETURNING manifest_ride_secret_key_hex`,
      [routeId, generated]
    );
    rideSecretKeyHex = up.rows[0]?.manifest_ride_secret_key_hex ?? generated;
  }

  const groupRes = await db.query<{ principal_leader: string | null }>(
    `SELECT principal_leader FROM groups WHERE id = $1`,
    [route.group_id]
  );
  const hostLeaderId = String(groupRes.rows[0]?.principal_leader ?? route.created_by);

  const partRes = await db.query<{
    user_id: string;
    is_leader: boolean;
    public_key: unknown;
    sos_public_key: unknown;
  }>(
    `WITH roster_uids AS (
       SELECT gl.user_id, true AS is_leader
       FROM group_leaders gl
       WHERE gl.group_id = $2
       UNION
       SELECT r.user_id, false AS is_leader
       FROM club_route_rsvps r
       WHERE r.route_id = $1 AND r.status IN ('confirmed', 'en_route')
       UNION
       SELECT m.user_id, false AS is_leader
       FROM memberships m
       WHERE m.group_id = $2 AND m.invite_state = 'accepted'
     ),
     merged AS (
       SELECT user_id, bool_or(is_leader) AS is_leader
       FROM roster_uids
       GROUP BY user_id
     )
     SELECT m.user_id, m.is_leader, u.public_key, u.sos_public_key
     FROM merged m
     JOIN users u ON u.id = m.user_id
     ORDER BY m.is_leader DESC, m.user_id ASC`,
    [routeId, route.group_id]
  );

  const isParticipant = partRes.rows.some((r) => String(r.user_id) === uid);
  if (!isParticipant && hostLeaderId !== uid) {
    return { kind: 'forbidden' };
  }

  const sosPublicKeys: Record<string, string> = {};
  const participants: RideManifestParticipant[] = partRes.rows.map((r) => {
    const pkBuf = parseBytea(r.public_key);
    const pkBase64 =
      pkBuf && pkBuf.length > 0
        ? pkBuf.toString('base64')
        : Buffer.from(String(r.user_id), 'utf8').toString('base64');
    const sosKey = parseBytea(r.sos_public_key);
    if (sosKey?.length === 32) {
      sosPublicKeys[String(r.user_id)] = sosKey.toString('base64');
    }
    return {
      uid: String(r.user_id),
      gid: String(route.group_id),
      statusKind: r.is_leader ? 'leader' : 'full',
      rideRole: r.is_leader ? 'capitan_rodada' : 'participante',
      pk: pkBase64
    };
  });

  const rideState =
    route.status === 'completed' || route.completed_at !== null ? 'COMPLETED' : 'ACTIVE';

  const signed = issueRideManifest(
    {
      rideId: String(route.id),
      hostGroupId: String(route.group_id),
      cohostGroupId: null,
      hostLeaderId,
      cohostLeaderId: null,
      routeHash: null,
      participants,
      rideSecretKeyHex,
      sosPublicKeys,
      rideState
    },
    credentialKeyring
  );

  await db.query(
    `UPDATE club_routes SET manifest_issued_at = COALESCE(manifest_issued_at, now()) WHERE id = $1`,
    [routeId]
  );

  return {
    kind: 'ok',
    body: {
      ...signed,
      publicKeys: credentialKeyring.getPublicKeyMap()
    }
  };
}
