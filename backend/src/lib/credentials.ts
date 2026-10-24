import {
  createPrivateKey,
  createPublicKey,
  generateKeyPairSync,
  randomUUID,
  sign,
  verify,
  type KeyObject
} from 'node:crypto';

/**
 * Emisión y verificación de credenciales firmadas con Ed25519 para validación offline en la mesh
 * (BACKEND_LICENSING.md §7 y RULES.md R7.1, R7.3):
 * - Formato JSON firmado con Ed25519 (`<payload_base64url>.<ed25519_sig_base64url>`).
 * - Llavero (`CredentialKeyring`) con rotación de `kid`: firma con la clave privada activa y expone
 *   todas las claves públicas vigentes (`spki` en Base64) para que los teléfonos validen credenciales
 *   emitidas antes y después de una rotación sin corte de servicio.
 * - Duración (`exp`): máximo 7 días (`CREDENTIAL_TTL_DAYS = 7`), pero NUNCA excede
 *   `current_period_end + gracia` (`effectiveAccessUntil`).
 * - Lista de revocación firmada con Ed25519 (`GET /v1/revocations`) para sincronizar antes de iniciar rodada.
 */

export const CREDENTIAL_POLICY = {
  VERSION: 1 as const,
  TTL_DAYS: Number(process.env.CREDENTIAL_TTL_DAYS ?? 7),
  TTL_SECONDS: Number(process.env.CREDENTIAL_TTL_DAYS ?? 7) * 24 * 60 * 60
};

export type CredentialRoleKind = 'leader' | 'full' | 'prospect' | 'guest';

export interface OfflineCredentialPayload {
  v: 1;
  jti: string;
  uid: string;
  gid: string;
  kind: CredentialRoleKind;
  pk: string; // clave pública del dispositivo en Base64
  iat: number; // segundos Unix
  exp: number; // segundos Unix (<= min(iat + 7d, effectiveAccessUntil))
  kid: string; // ID de clave de firma Ed25519
}

export interface SignedOfflineCredential {
  credential: string; // <payload_base64url>.<ed25519_sig_base64url>
  payload: OfflineCredentialPayload;
  signatureBase64Url: string;
}

export interface Ed25519KeyPairEntry {
  kid: string;
  privateKey?: KeyObject;
  publicKey: KeyObject;
  publicKeySpkiBase64: string;
}

export class CredentialKeyring {
  private activeKid: string;
  private readonly keys = new Map<string, Ed25519KeyPairEntry>();

  constructor(initialKid: string = process.env.CREDENTIAL_SIGNING_KEY_ID ?? 'ed25519-k1') {
    const pair = CredentialKeyring.generatePair(initialKid);
    this.keys.set(initialKid, pair);
    this.activeKid = initialKid;
  }

  static generatePair(kid: string): Ed25519KeyPairEntry {
    const { publicKey, privateKey } = generateKeyPairSync('ed25519');
    const spkiDer = publicKey.export({ type: 'spki', format: 'der' });
    return {
      kid,
      privateKey,
      publicKey,
      publicKeySpkiBase64: Buffer.from(spkiDer).toString('base64')
    };
  }

  static fromPemOrSpki(
    kid: string,
    publicKeySpkiBase64: string,
    privateKeyPkcs8Base64?: string
  ): Ed25519KeyPairEntry {
    const pubDer = Buffer.from(publicKeySpkiBase64, 'base64');
    const publicKey = createPublicKey({ key: pubDer, format: 'der', type: 'spki' });
    const privateKey = privateKeyPkcs8Base64
      ? createPrivateKey({
          key: Buffer.from(privateKeyPkcs8Base64, 'base64'),
          format: 'der',
          type: 'pkcs8'
        })
      : undefined;
    return {
      kid,
      privateKey,
      publicKey,
      publicKeySpkiBase64
    };
  }

  getActiveKid(): string {
    return this.activeKid;
  }

  /**
   * Rota la clave activa de firma generando o registrando un nuevo `kid`, conservando las claves
   * públicas anteriores en el llavero para que las credenciales emitidas previamente sigan siendo
   * verificables hasta su `exp` natural de 7 días.
   */
  rotateKey(newKid: string, customEntry?: Ed25519KeyPairEntry): Ed25519KeyPairEntry {
    const entry = customEntry ?? CredentialKeyring.generatePair(newKid);
    if (!entry.privateKey) {
      throw new Error(`ROTATE_KEY_MISSING_PRIVATE_KEY:${newKid}`);
    }
    this.keys.set(newKid, entry);
    this.activeKid = newKid;
    return entry;
  }

  getEntry(kid: string): Ed25519KeyPairEntry | undefined {
    return this.keys.get(kid);
  }

  getPublicKeyMap(): Record<string, string> {
    const out: Record<string, string> = {};
    for (const [kid, entry] of this.keys.entries()) {
      out[kid] = entry.publicKeySpkiBase64;
    }
    return out;
  }
}

export const defaultCredentialKeyring = new CredentialKeyring();

/**
 * Calcula el `exp` (segundos Unix) de una credencial offline:
 * - Por defecto `iat + 7 días`.
 * - Acotado estrictamente por `effectiveAccessUntil` (`current_period_end + gracia`).
 * - Si `effectiveAccessUntil <= nowSec`, lanza error porque no puede emitirse credencial sin acceso vigente.
 */
export function computeCredentialExpirySeconds(
  nowSec: number,
  effectiveAccessUntilSec: number,
  maxTtlSeconds: number = CREDENTIAL_POLICY.TTL_SECONDS
): number {
  if (effectiveAccessUntilSec <= nowSec) {
    throw new Error('SUBSCRIPTION_ACCESS_EXPIRED: No se puede emitir credencial con acceso vigente vencido');
  }
  return Math.min(nowSec + maxTtlSeconds, Math.floor(effectiveAccessUntilSec));
}

export function issueOfflineCredential(
  params: {
    uid: string;
    gid: string;
    kind: CredentialRoleKind;
    devicePublicKeyBase64: string;
    effectiveAccessUntilSec: number;
    nowSec?: number;
    jti?: string;
  },
  keyring: CredentialKeyring = defaultCredentialKeyring
): SignedOfflineCredential {
  const nowSec = params.nowSec ?? Math.floor(Date.now() / 1000);
  const exp = computeCredentialExpirySeconds(nowSec, params.effectiveAccessUntilSec);
  const activeKid = keyring.getActiveKid();
  const keyEntry = keyring.getEntry(activeKid);
  if (!keyEntry || !keyEntry.privateKey) {
    throw new Error(`MISSING_ACTIVE_ED25519_KEY:${activeKid}`);
  }

  const payload: OfflineCredentialPayload = {
    v: CREDENTIAL_POLICY.VERSION,
    jti: params.jti ?? randomUUID(),
    uid: params.uid,
    gid: params.gid,
    kind: params.kind,
    pk: params.devicePublicKeyBase64,
    iat: nowSec,
    exp,
    kid: activeKid
  };

  const payloadB64 = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url');
  const sigBuf = sign(null, Buffer.from(payloadB64, 'utf8'), keyEntry.privateKey);
  const signatureBase64Url = sigBuf.toString('base64url');

  return {
    credential: `${payloadB64}.${signatureBase64Url}`,
    payload,
    signatureBase64Url
  };
}

export interface CredentialVerificationResult {
  valid: boolean;
  reason?:
    | 'MALFORMED_TOKEN'
    | 'UNSUPPORTED_VERSION'
    | 'UNKNOWN_KID'
    | 'INVALID_SIGNATURE'
    | 'CREDENTIAL_EXPIRED'
    | 'GROUP_MISMATCH'
    | 'DEVICE_PK_MISMATCH'
    | 'REVOKED_USER_OR_CREDENTIAL';
  payload?: OfflineCredentialPayload;
}

export function verifyOfflineCredential(
  credentialToken: string,
  options: {
    nowSec?: number;
    expectedGroupIds?: string[];
    expectedDevicePk?: string;
    revokedUserIds?: Set<string>;
    revokedJtis?: Set<string>;
    keyring?: CredentialKeyring;
  } = {}
): CredentialVerificationResult {
  const parts = credentialToken.split('.');
  if (parts.length !== 2 || !parts[0] || !parts[1]) {
    return { valid: false, reason: 'MALFORMED_TOKEN' };
  }
  const [payloadB64, sigB64] = parts;

  let payload: OfflineCredentialPayload;
  try {
    payload = JSON.parse(
      Buffer.from(payloadB64, 'base64url').toString('utf8')
    ) as OfflineCredentialPayload;
  } catch {
    return { valid: false, reason: 'MALFORMED_TOKEN' };
  }

  if (!payload || payload.v !== 1 || typeof payload.kid !== 'string') {
    return { valid: false, reason: 'UNSUPPORTED_VERSION' };
  }

  const keyring = options.keyring ?? defaultCredentialKeyring;
  const keyEntry = keyring.getEntry(payload.kid);
  if (!keyEntry) {
    return { valid: false, reason: 'UNKNOWN_KID', payload };
  }

  let sigBuf: Buffer;
  try {
    sigBuf = Buffer.from(sigB64, 'base64url');
  } catch {
    return { valid: false, reason: 'MALFORMED_TOKEN', payload };
  }

  const sigOk = verify(null, Buffer.from(payloadB64, 'utf8'), keyEntry.publicKey, sigBuf);
  if (!sigOk) {
    return { valid: false, reason: 'INVALID_SIGNATURE', payload };
  }

  const nowSec = options.nowSec ?? Math.floor(Date.now() / 1000);
  if (typeof payload.exp !== 'number' || payload.exp <= nowSec) {
    return { valid: false, reason: 'CREDENTIAL_EXPIRED', payload };
  }

  if (options.expectedGroupIds && !options.expectedGroupIds.includes(payload.gid)) {
    return { valid: false, reason: 'GROUP_MISMATCH', payload };
  }

  if (options.expectedDevicePk && payload.pk !== options.expectedDevicePk) {
    return { valid: false, reason: 'DEVICE_PK_MISMATCH', payload };
  }

  if (
    (options.revokedUserIds && options.revokedUserIds.has(payload.uid)) ||
    (options.revokedJtis && options.revokedJtis.has(payload.jti))
  ) {
    return { valid: false, reason: 'REVOKED_USER_OR_CREDENTIAL', payload };
  }

  return { valid: true, payload };
}

export interface RevocationEntry {
  userId: string;
  groupId: string | null;
  reason:
    | 'refunded'
    | 'membership_removed'
    | 'membership_left'
    | 'subscription_inactive'
    | 'user_deleted'
    | 'manual_ride_kick';
  revokedAtIso: string;
}

export interface SignedRevocationList {
  v: 1;
  kid: string;
  syncedAtSec: number;
  syncedAtIso: string;
  revocations: RevocationEntry[];
  signatureBase64Url: string;
}

export function signRevocationList(
  revocations: RevocationEntry[],
  nowSec: number = Math.floor(Date.now() / 1000),
  keyring: CredentialKeyring = defaultCredentialKeyring
): SignedRevocationList {
  const activeKid = keyring.getActiveKid();
  const keyEntry = keyring.getEntry(activeKid);
  if (!keyEntry || !keyEntry.privateKey) {
    throw new Error(`MISSING_ACTIVE_ED25519_KEY:${activeKid}`);
  }
  const syncedAtIso = new Date(nowSec * 1000).toISOString();
  const bodyObj = {
    v: 1 as const,
    kid: activeKid,
    syncedAtSec: nowSec,
    syncedAtIso,
    revocations
  };
  const canonical = Buffer.from(JSON.stringify(bodyObj), 'utf8');
  const sigBuf = sign(null, canonical, keyEntry.privateKey);
  return {
    ...bodyObj,
    signatureBase64Url: sigBuf.toString('base64url')
  };
}
