import { createHash, createHmac, randomBytes, randomUUID, scryptSync, timingSafeEqual } from 'node:crypto';

/**
 * Especificación de Autenticación P0 endurecida según OWASP (BACKEND_LICENSING.md §§8, 10 — Decisiones D35 y D40):
 * - Algoritmo de hash de contraseñas: scrypt con N=2^17 (131072), r=8, p=1, keylen=64 bytes, salt=16 bytes CSPRNG.
 * - Token de acceso firmado (HMAC-SHA256): corta duración de 30 minutos (1 800 s).
 * - Token de refresco rotativo: vigencia de 30 días (2 592 000 s), almacenado en PostgreSQL únicamente como hash SHA-256 (`refresh_tokens.token_hash`), revocable en `/v1/auth/logout` y rotado en `/v1/auth/refresh`.
 * - Protección contra fuerza bruta sin DoS a terceros: bloqueo por par `(email, ip)` tras 5 intentos fallidos y límite global por `ip` tras 15 intentos fallidos en ventana de 15 minutos.
 */
export const AUTH_POLICY = {
  HASH_ALGORITHM: 'scrypt-N131072-r8-p1-k64',
  SCRYPT_N: 131072, // 2^17 según guía OWASP Password Storage Cheat Sheet
  SCRYPT_R: 8,
  SCRYPT_P: 1,
  SCRYPT_MAXMEM: 256 * 1024 * 1024, // 256 MB para permitir N=2^17, r=8
  KEY_LEN: 64,
  SALT_BYTES: 16,
  ACCESS_TOKEN_TTL_SECONDS: 30 * 60, // 30 minutos (rango 15–60 min)
  REFRESH_TOKEN_TTL_SECONDS: 30 * 24 * 60 * 60, // 30 días
  MAX_FAILED_ATTEMPTS: 5,
  MAX_FAILED_ATTEMPTS_PER_IP: 15,
  LOCKOUT_WINDOW_MS: 15 * 60 * 1000 // 15 minutos
} as const;

const TOKEN_SECRET = process.env.AUTH_TOKEN_SECRET ?? 'rbs-dev-hmac-secret-change-in-prod-32b';

export function hashPassword(plainPassword: string): string {
  const salt = randomBytes(AUTH_POLICY.SALT_BYTES);
  const derived = scryptSync(plainPassword, salt, AUTH_POLICY.KEY_LEN, {
    N: AUTH_POLICY.SCRYPT_N,
    r: AUTH_POLICY.SCRYPT_R,
    p: AUTH_POLICY.SCRYPT_P,
    maxmem: AUTH_POLICY.SCRYPT_MAXMEM
  });
  return `scrypt$${salt.toString('hex')}$${derived.toString('hex')}`;
}

export function verifyPassword(plainPassword: string, storedHash: string): boolean {
  const parts = storedHash.split('$');
  if (parts.length !== 3 || parts[0] !== 'scrypt') return false;
  const salt = Buffer.from(parts[1], 'hex');
  const expected = Buffer.from(parts[2], 'hex');
  if (salt.length !== AUTH_POLICY.SALT_BYTES || expected.length !== AUTH_POLICY.KEY_LEN) {
    return false;
  }
  const actual = scryptSync(plainPassword, salt, AUTH_POLICY.KEY_LEN, {
    N: AUTH_POLICY.SCRYPT_N,
    r: AUTH_POLICY.SCRYPT_R,
    p: AUTH_POLICY.SCRYPT_P,
    maxmem: AUTH_POLICY.SCRYPT_MAXMEM
  });
  return timingSafeEqual(actual, expected);
}

/**
 * Devuelve el hash SHA-256 hexadecimal de un refreshToken para persistirlo en BD sin guardar nunca el token en claro.
 */
export function hashRefreshToken(rawToken: string): string {
  return createHash('sha256').update(rawToken, 'utf8').digest('hex');
}

export interface SignedSessionTokenPayload {
  sub: string; // user UUID
  jti: string; // identificador único de emisión para rotación
  iat: number;
  exp: number;
  kind: 'access' | 'refresh';
}

export function issueSessionToken(
  userId: string,
  kind: 'access' | 'refresh' = 'access',
  nowSec: number = Math.floor(Date.now() / 1000)
): { token: string; tokenHash: string; expiresAtIso: string; ttlSeconds: number; jti: string } {
  const ttlSeconds =
    kind === 'access'
      ? AUTH_POLICY.ACCESS_TOKEN_TTL_SECONDS
      : AUTH_POLICY.REFRESH_TOKEN_TTL_SECONDS;
  const jti = randomUUID();
  const payload: SignedSessionTokenPayload = {
    sub: userId,
    jti,
    iat: nowSec,
    exp: nowSec + ttlSeconds,
    kind
  };
  const bodyB64 = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url');
  const sigB64 = createHmac('sha256', TOKEN_SECRET).update(bodyB64).digest('base64url');
  const token = `${bodyB64}.${sigB64}`;
  return {
    token,
    tokenHash: hashRefreshToken(token),
    expiresAtIso: new Date((nowSec + ttlSeconds) * 1000).toISOString(),
    ttlSeconds,
    jti
  };
}

export function verifySessionToken(
  token: string,
  nowSec: number = Math.floor(Date.now() / 1000)
): SignedSessionTokenPayload | null {
  const parts = token.split('.');
  if (parts.length !== 2) return null;
  const [bodyB64, sigB64] = parts;
  const expectedSig = createHmac('sha256', TOKEN_SECRET).update(bodyB64).digest('base64url');
  const a = Buffer.from(sigB64);
  const b = Buffer.from(expectedSig);
  if (a.length !== b.length || !timingSafeEqual(a, b)) return null;
  try {
    const parsed = JSON.parse(
      Buffer.from(bodyB64, 'base64url').toString('utf8')
    ) as SignedSessionTokenPayload;
    if (parsed.exp <= nowSec) return null;
    return parsed;
  } catch {
    return null;
  }
}

export class LoginAttemptTracker {
  private readonly accountIpAttempts = new Map<string, { count: number; firstAttemptMs: number }>();
  private readonly ipAttempts = new Map<string, { count: number; firstAttemptMs: number }>();

  private makeAccountIpKey(email: string, ip: string): string {
    return `${email.trim().toLowerCase()}|${ip.trim()}`;
  }

  isLockedOut(email: string, ip: string = '127.0.0.1', nowMs: number = Date.now()): boolean {
    const acctKey = this.makeAccountIpKey(email, ip);
    const acctEntry = this.accountIpAttempts.get(acctKey);
    if (acctEntry) {
      if (nowMs - acctEntry.firstAttemptMs > AUTH_POLICY.LOCKOUT_WINDOW_MS) {
        this.accountIpAttempts.delete(acctKey);
      } else if (acctEntry.count >= AUTH_POLICY.MAX_FAILED_ATTEMPTS) {
        return true;
      }
    }

    const ipKey = ip.trim();
    const ipEntry = this.ipAttempts.get(ipKey);
    if (ipEntry) {
      if (nowMs - ipEntry.firstAttemptMs > AUTH_POLICY.LOCKOUT_WINDOW_MS) {
        this.ipAttempts.delete(ipKey);
      } else if (ipEntry.count >= AUTH_POLICY.MAX_FAILED_ATTEMPTS_PER_IP) {
        return true;
      }
    }

    return false;
  }

  recordFailure(email: string, ip: string = '127.0.0.1', nowMs: number = Date.now()): number {
    const acctKey = this.makeAccountIpKey(email, ip);
    const acctEntry = this.accountIpAttempts.get(acctKey);
    let acctCount = 1;
    if (!acctEntry || nowMs - acctEntry.firstAttemptMs > AUTH_POLICY.LOCKOUT_WINDOW_MS) {
      this.accountIpAttempts.set(acctKey, { count: 1, firstAttemptMs: nowMs });
    } else {
      acctEntry.count += 1;
      acctCount = acctEntry.count;
    }

    const ipKey = ip.trim();
    const ipEntry = this.ipAttempts.get(ipKey);
    if (!ipEntry || nowMs - ipEntry.firstAttemptMs > AUTH_POLICY.LOCKOUT_WINDOW_MS) {
      this.ipAttempts.set(ipKey, { count: 1, firstAttemptMs: nowMs });
    } else {
      ipEntry.count += 1;
    }

    return acctCount;
  }

  reset(email: string, ip: string = '127.0.0.1'): void {
    this.accountIpAttempts.delete(this.makeAccountIpKey(email, ip));
  }
}
