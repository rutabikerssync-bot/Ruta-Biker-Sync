import {
  createHash,
  createHmac,
  randomBytes,
  randomUUID,
  scrypt,
  scryptSync,
  timingSafeEqual
} from 'node:crypto';

/**
 * Especificación de Autenticación P0 endurecida según OWASP (BACKEND_LICENSING.md §§8, 10 — Decisiones D35, D36, D40):
 * - Algoritmo de hash de contraseñas: scrypt con N=2^17 (131072), r=8, p=1, keylen=64 bytes, salt=16 bytes CSPRNG.
 * - Memoria por hash: 128 * N * r * p = 134 217 728 bytes (128 MiB). Se fija `SCRYPT_MAXMEM` explícito en
 *   268 435 456 bytes (256 MiB) y un semáforo de concurrencia (`MAX_CONCURRENT_SCRYPT_HASHES = 4`) para
 *   acotar el pico de RAM a 512 MiB ante ráfagas de inicios de sesión concurrentes.
 * - Token propio firmado con HMAC-SHA256:
 *   1. Algoritmo fijo obligatorio (`alg === 'HS256'`).
 *   2. Expiración obligatoria (`exp` numérico finito y `exp > nowSec`).
 *   3. Comparación de firma en tiempo constante (`timingSafeEqual` sobre digest binario de 32 bytes).
 *   4. Separación estricta de tipos (`kind: 'access' | 'refresh'`): un `refresh` no sirve como Bearer de API y un `access` no sirve en `/v1/auth/refresh`.
 *   5. Rotación de claves de firma mediante `kid` y llavero (`SessionKeyring`).
 * - Protección contra fuerza bruta compatible con CGNAT móvil: bloqueo por par `(email, ip)` tras 5 intentos fallidos
 *   y umbral por `ip` elevado a 100 intentos en 15 minutos con retardo progresivo (`getProgressiveDelayMs`).
 */
export const AUTH_POLICY = {
  HASH_ALGORITHM: 'scrypt-N131072-r8-p1-k64',
  TOKEN_ALGORITHM: 'HS256' as const,
  SCRYPT_N: 131072, // 2^17 según guía OWASP Password Storage Cheat Sheet (128 MiB por derivación)
  SCRYPT_R: 8,
  SCRYPT_P: 1,
  SCRYPT_MAXMEM: 256 * 1024 * 1024, // 268 435 456 bytes (256 MiB explícito)
  MAX_CONCURRENT_SCRYPT_HASHES: Number(process.env.MAX_CONCURRENT_SCRYPT_HASHES ?? 4),
  MAX_SCRYPT_QUEUE_SIZE: Number(process.env.MAX_SCRYPT_QUEUE_SIZE ?? 32),
  KEY_LEN: 64,
  SALT_BYTES: 16,
  ACCESS_TOKEN_TTL_SECONDS: 30 * 60, // 30 minutos
  REFRESH_TOKEN_TTL_SECONDS: 30 * 24 * 60 * 60, // 30 días
  MAX_FAILED_ATTEMPTS: 5, // Por par (correo, IP)
  MAX_FAILED_ATTEMPTS_PER_IP: 100, // Umbral por IP apto para redes móviles con CGNAT
  LOCKOUT_WINDOW_MS: 15 * 60 * 1000 // 15 minutos
} as const;

export const DEV_DEFAULT_HMAC_SECRET = 'rbs-dev-hmac-secret-change-in-prod-32b';

export interface SessionKeyring {
  activeKid: string;
  keys: Record<string, string>;
}

/**
 * En producción (`NODE_ENV === 'production'`), el backend se niega a arrancar si no existe
 * `AUTH_TOKEN_KEYS_JSON` válido con claves fuertes (>= 32 bytes y distintas del secreto de desarrollo).
 */
export function validateProductionAuthConfig(env: NodeJS.ProcessEnv = process.env): SessionKeyring {
  const isProd = env.NODE_ENV === 'production';
  const activeKid = env.AUTH_TOKEN_ACTIVE_KID ?? 'k1';
  const rawJson = env.AUTH_TOKEN_KEYS_JSON?.trim();

  if (isProd) {
    if (!rawJson) {
      throw new Error(
        'FATAL_AUTH_CONFIG: En producción AUTH_TOKEN_KEYS_JSON es obligatorio y el servidor no puede usar una clave por defecto.'
      );
    }
    let parsed: Record<string, string>;
    try {
      parsed = JSON.parse(rawJson) as Record<string, string>;
    } catch {
      throw new Error('FATAL_AUTH_CONFIG: AUTH_TOKEN_KEYS_JSON no es un JSON válido.');
    }
    if (!parsed || typeof parsed !== 'object' || Object.keys(parsed).length === 0) {
      throw new Error('FATAL_AUTH_CONFIG: AUTH_TOKEN_KEYS_JSON debe contener al menos una clave.');
    }
    for (const [kid, secret] of Object.entries(parsed)) {
      if (
        typeof secret !== 'string' ||
        secret.length < 32 ||
        secret === DEV_DEFAULT_HMAC_SECRET ||
        secret.includes('rbs-dev-hmac-secret')
      ) {
        throw new Error(
          `FATAL_AUTH_CONFIG: La clave '${kid}' en AUTH_TOKEN_KEYS_JSON es insegura o usa el valor de desarrollo.`
        );
      }
    }
    if (!parsed[activeKid]) {
      throw new Error(
        `FATAL_AUTH_CONFIG: AUTH_TOKEN_ACTIVE_KID ('${activeKid}') no existe en AUTH_TOKEN_KEYS_JSON.`
      );
    }
    return { activeKid, keys: { ...parsed } };
  }

  const defaultSecret = env.AUTH_TOKEN_SECRET ?? DEV_DEFAULT_HMAC_SECRET;
  if (rawJson) {
    try {
      const parsed = JSON.parse(rawJson) as Record<string, string>;
      if (parsed && typeof parsed === 'object' && Object.keys(parsed).length > 0) {
        return {
          activeKid: parsed[activeKid] ? activeKid : Object.keys(parsed)[0],
          keys: parsed
        };
      }
    } catch {
      // Fallback al secreto único en desarrollo si el JSON es inválido
    }
  }
  return {
    activeKid,
    keys: { [activeKid]: defaultSecret }
  };
}

let currentKeyring: SessionKeyring = validateProductionAuthConfig();

export function getSessionKeyring(): SessionKeyring {
  return currentKeyring;
}

export function setSessionKeyring(keyring: SessionKeyring): void {
  if (!keyring.keys[keyring.activeKid]) {
    throw new Error(`ACTIVE_KID_NOT_IN_KEYRING:${keyring.activeKid}`);
  }
  currentKeyring = {
    activeKid: keyring.activeKid,
    keys: { ...keyring.keys }
  };
}

export class ScryptQueueOverflowError extends Error {
  public readonly code = 'SCRYPT_QUEUE_FULL';
  public readonly statusCode = 503;
  public readonly retryAfterSeconds = 2;

  constructor(maxQueueSize: number) {
    super(
      `Cola de derivación scrypt llena (máximo ${maxQueueSize} en espera). Intente de nuevo en unos segundos.`
    );
    this.name = 'ScryptQueueOverflowError';
  }
}

/**
 * Semáforo FIFO con cola acotada para limitar la cantidad de derivaciones `scrypt` (128 MiB c/u)
 * ejecutándose en paralelo. Si la cola alcanza `maxQueueSize`, lanza `ScryptQueueOverflowError` (503)
 * para no acumular solicitudes ni agotar descriptores de conexión.
 */
export class ScryptConcurrencyLimiter {
  private active = 0;
  private readonly queue: Array<() => void> = [];

  constructor(
    public readonly maxConcurrent: number = AUTH_POLICY.MAX_CONCURRENT_SCRYPT_HASHES,
    public readonly maxQueueSize: number = AUTH_POLICY.MAX_SCRYPT_QUEUE_SIZE
  ) {}

  get activeCount(): number {
    return this.active;
  }

  get queuedCount(): number {
    return this.queue.length;
  }

  async run<T>(task: () => Promise<T>): Promise<T> {
    if (this.active >= this.maxConcurrent) {
      if (this.queue.length >= this.maxQueueSize) {
        throw new ScryptQueueOverflowError(this.maxQueueSize);
      }
      await new Promise<void>((resolve) => this.queue.push(resolve));
    }
    this.active += 1;
    try {
      return await task();
    } finally {
      this.active -= 1;
      const next = this.queue.shift();
      if (next) next();
    }
  }
}

export const defaultScryptLimiter = new ScryptConcurrencyLimiter(
  AUTH_POLICY.MAX_CONCURRENT_SCRYPT_HASHES,
  AUTH_POLICY.MAX_SCRYPT_QUEUE_SIZE
);

function scryptAsync(password: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scrypt(
      password,
      salt,
      AUTH_POLICY.KEY_LEN,
      {
        N: AUTH_POLICY.SCRYPT_N,
        r: AUTH_POLICY.SCRYPT_R,
        p: AUTH_POLICY.SCRYPT_P,
        maxmem: AUTH_POLICY.SCRYPT_MAXMEM
      },
      (err, derivedKey) => {
        if (err) reject(err);
        else resolve(derivedKey);
      }
    );
  });
}

export async function hashPasswordAsync(
  plainPassword: string,
  limiter: ScryptConcurrencyLimiter = defaultScryptLimiter
): Promise<string> {
  return limiter.run(async () => {
    const salt = randomBytes(AUTH_POLICY.SALT_BYTES);
    const derived = await scryptAsync(plainPassword, salt);
    return `scrypt$${salt.toString('hex')}$${derived.toString('hex')}`;
  });
}

export async function verifyPasswordAsync(
  plainPassword: string,
  storedHash: string,
  limiter: ScryptConcurrencyLimiter = defaultScryptLimiter
): Promise<boolean> {
  const parts = storedHash.split('$');
  if (parts.length !== 3 || parts[0] !== 'scrypt') return false;
  const salt = Buffer.from(parts[1], 'hex');
  const expected = Buffer.from(parts[2], 'hex');
  if (salt.length !== AUTH_POLICY.SALT_BYTES || expected.length !== AUTH_POLICY.KEY_LEN) {
    return false;
  }
  return limiter.run(async () => {
    const actual = await scryptAsync(plainPassword, salt);
    return timingSafeEqual(actual, expected);
  });
}

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
  alg: 'HS256';
  kid: string;
  sub: string; // user UUID
  jti: string; // identificador único de emisión para rotación
  iat: number;
  exp: number;
  kind: 'access' | 'refresh';
}

export function issueSessionToken(
  userId: string,
  kind: 'access' | 'refresh' = 'access',
  nowSec: number = Math.floor(Date.now() / 1000),
  keyring: SessionKeyring = currentKeyring
): {
  token: string;
  tokenHash: string;
  expiresAtIso: string;
  ttlSeconds: number;
  jti: string;
  kid: string;
} {
  const secret = keyring.keys[keyring.activeKid];
  if (!secret) {
    throw new Error(`MISSING_SIGNING_KEY:${keyring.activeKid}`);
  }
  const ttlSeconds =
    kind === 'access'
      ? AUTH_POLICY.ACCESS_TOKEN_TTL_SECONDS
      : AUTH_POLICY.REFRESH_TOKEN_TTL_SECONDS;
  const jti = randomUUID();
  const payload: SignedSessionTokenPayload = {
    alg: AUTH_POLICY.TOKEN_ALGORITHM,
    kid: keyring.activeKid,
    sub: userId,
    jti,
    iat: nowSec,
    exp: nowSec + ttlSeconds,
    kind
  };
  const bodyB64 = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url');
  const sigB64 = createHmac('sha256', secret).update(bodyB64).digest('base64url');
  const token = `${bodyB64}.${sigB64}`;
  return {
    token,
    tokenHash: hashRefreshToken(token),
    expiresAtIso: new Date((nowSec + ttlSeconds) * 1000).toISOString(),
    ttlSeconds,
    jti,
    kid: keyring.activeKid
  };
}

export function verifySessionToken(
  token: string,
  nowSec: number = Math.floor(Date.now() / 1000),
  expectedKind?: 'access' | 'refresh',
  keyring: SessionKeyring = currentKeyring
): SignedSessionTokenPayload | null {
  const parts = token.split('.');
  if (parts.length !== 2) return null;
  const [bodyB64, sigB64] = parts;
  if (!bodyB64 || !sigB64) return null;

  let parsed: SignedSessionTokenPayload;
  try {
    parsed = JSON.parse(
      Buffer.from(bodyB64, 'base64url').toString('utf8')
    ) as SignedSessionTokenPayload;
  } catch {
    return null;
  }

  // 1. Algoritmo fijo obligatorio ('HS256')
  if (!parsed || parsed.alg !== AUTH_POLICY.TOKEN_ALGORITHM) return null;

  // 2. Separación estricta de tipos ('access' vs 'refresh')
  if (parsed.kind !== 'access' && parsed.kind !== 'refresh') return null;
  if (expectedKind && parsed.kind !== expectedKind) return null;

  // 3. Expiración obligatoria
  if (typeof parsed.exp !== 'number' || !Number.isFinite(parsed.exp) || parsed.exp <= nowSec) {
    return null;
  }

  // 4. Selección de clave por `kid` (soporta rotación de claves sin invalidar sesiones en vuelo)
  if (typeof parsed.kid !== 'string' || !parsed.kid) return null;
  const secret = keyring.keys[parsed.kid];
  if (!secret) return null;

  // 5. Comparación de firma HMAC-SHA256 en tiempo constante sobre digest binario de 32 bytes
  let actualSigBuf: Buffer;
  try {
    actualSigBuf = Buffer.from(sigB64, 'base64url');
  } catch {
    return null;
  }
  const expectedSigBuf = createHmac('sha256', secret).update(bodyB64).digest();
  if (actualSigBuf.length !== expectedSigBuf.length) return null;
  if (!timingSafeEqual(actualSigBuf, expectedSigBuf)) return null;

  return parsed;
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

  /**
   * Calcula un retardo progresivo en milisegundos según los fallos acumulados para frenar ataques
   * distribuidos o tras CGNAT sin bloquear prematuramente a toda una IP móvil compartida.
   */
  getProgressiveDelayMs(email: string, ip: string = '127.0.0.1', nowMs: number = Date.now()): number {
    const acctEntry = this.accountIpAttempts.get(this.makeAccountIpKey(email, ip));
    const acctCount =
      acctEntry && nowMs - acctEntry.firstAttemptMs <= AUTH_POLICY.LOCKOUT_WINDOW_MS
        ? acctEntry.count
        : 0;
    const ipEntry = this.ipAttempts.get(ip.trim());
    const ipCount =
      ipEntry && nowMs - ipEntry.firstAttemptMs <= AUTH_POLICY.LOCKOUT_WINDOW_MS
        ? ipEntry.count
        : 0;

    if (acctCount <= 1 && ipCount <= 5) return 0;
    const step = Math.min(4, Math.max(acctCount - 1, Math.floor(ipCount / 10)));
    return Math.min(2000, 250 * Math.pow(2, step - 1));
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

export type TrustProxyConfig =
  | boolean
  | string
  | string[]
  | ((address: string, hop: number) => boolean);

/**
 * Convierte un número máximo de saltos en la función `TrustProxyFunction` nativa de Fastify v5.
 */
export function createHopLimitTrustProxy(
  maxHops: number
): (address: string, hop: number) => boolean {
  return (_address: string, hop: number) => hop <= maxHops;
}

/**
 * Configura `trustProxy` en Fastify usando número explícito de saltos (`TRUST_PROXY_HOPS`) o
 * lista de IPs/CIDR de confianza (`TRUST_PROXY_IPS`), evitando `true` global que permitiría
 * falsificar `X-Forwarded-For` si el proceso queda expuesto directamente.
 */
export function parseTrustProxyConfig(
  env: NodeJS.ProcessEnv = process.env
): TrustProxyConfig {
  const ipsRaw = env.TRUST_PROXY_IPS?.trim();
  if (ipsRaw) {
    const ips = ipsRaw
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    if (ips.length > 0) return ips;
  }

  const hopsRaw = env.TRUST_PROXY_HOPS?.trim();
  if (hopsRaw) {
    const hops = Number(hopsRaw);
    if (Number.isInteger(hops) && hops >= 1) {
      return createHopLimitTrustProxy(hops);
    }
  }

  const legacyRaw = env.TRUST_PROXY?.trim();
  if (legacyRaw) {
    const asNum = Number(legacyRaw);
    if (Number.isInteger(asNum) && asNum >= 1) return createHopLimitTrustProxy(asNum);
    if (legacyRaw === 'true') {
      // Nunca confiar en una cadena ilimitada de proxies: 1 salto como máximo si se activa sin lista de IPs
      return createHopLimitTrustProxy(1);
    }
    if (legacyRaw !== 'false' && legacyRaw !== '0') {
      return legacyRaw
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean);
    }
  }

  return false;
}

