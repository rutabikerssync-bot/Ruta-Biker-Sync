import Fastify, {
  type FastifyInstance,
  type FastifyRequest,
  type FastifyReply,
  type FastifyServerOptions
} from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import type { DbExecutor } from './db/pool.js';
import {
  checkGroupNameCollision,
  normalizeGroupName
} from './lib/normalizer.js';
import { evaluateLogoUniqueness } from './lib/phash.js';
import {
  CLUB_ROLES,
  DEFAULT_ROLE_PERMISSIONS,
  MEMBER_STATUS_KINDS,
  P7_FORBID_GUEST_CLUB_ROLES,
  canMemberHoldClubRole,
  computePaymentSemaphore,
  requiredLeaderVotesForMajority
} from './lib/roles.js';
import {
  AUTH_POLICY,
  LoginAttemptTracker,
  ScryptConcurrencyLimiter,
  ScryptQueueOverflowError,
  createHopLimitTrustProxy,
  defaultScryptLimiter,
  hashPasswordAsync,
  hashRefreshToken,
  issueSessionToken,
  parseTrustProxyConfig,
  setSessionKeyring,
  validateProductionAuthConfig,
  verifyPasswordAsync,
  verifySessionToken,
  type TrustProxyConfig
} from './lib/auth.js';
import {
  CredentialKeyring,
  defaultCredentialKeyring,
  issueOfflineCredential,
  signRevocationList,
  type RevocationEntry
} from './lib/credentials.js';
import {
  FakePlayBillingVerifier,
  defaultFakePlayVerifier,
  evaluateSubscriptionState,
  isPlayP4ResultsDocumentPresent,
  reconcileGroupLeaderSubscriptions,
  type RtdnNotificationType,
  type SubscriptionKind,
  type SubscriptionState
} from './lib/subscriptions.js';

export interface BuildAppOptions {
  db: DbExecutor;
  logger?: boolean;
  trustProxy?: boolean | number | string | string[];
  scryptLimiter?: ScryptConcurrencyLimiter;
  forbidGuestClubRoles?: boolean;
  useFakeBilling?: boolean;
  playVerifier?: FakePlayBillingVerifier;
  credentialKeyring?: CredentialKeyring;
  env?: NodeJS.ProcessEnv;
}

const CountryCodeSchema = z
  .string()
  .length(2)
  .transform((v) => v.toUpperCase());

const LoginBodySchema = z.object({
  email: z.string().email(),
  password: z.string().min(8).max(128).optional(),
  displayName: z.string().min(2).max(60).optional(),
  countryCode: CountryCodeSchema.default('MX'),
  acceptedTermsAndConvivencia: z.boolean(),
  policyVersion: z.string().default('1.0'),
  activateLeaderDemo: z.boolean().optional()
});

const RefreshTokenBodySchema = z.object({
  refreshToken: z.string().min(16)
});

const UpdateProfileSchema = z.object({
  displayName: z.string().min(2).max(60).optional(),
  bikeModel: z.string().max(80).optional(),
  mapColor: z
    .string()
    .regex(/^#[0-9A-Fa-f]{6}$/)
    .optional(),
  avatarWebpBase64: z.string().max(16_384).optional(), // ~3 KB WebP 64x64
  publicKeyBase64: z.string().max(256).optional()
});

const CreateGroupSchema = z.object({
  countryCode: CountryCodeSchema,
  name: z.string().min(3).max(60),
  logoUrl: z.string().url().optional(),
  logoPhashHex: z
    .string()
    .regex(/^[0-9a-fA-F]{16}$/, 'logo_phash debe ser una huella de 64 bits (16 hex chars)')
});

const AddLeaderSchema = z.object({
  userId: z.string().uuid()
});

const CreateInvitationSchema = z.object({
  invitedUserId: z.string().uuid(),
  statusKind: z.enum(MEMBER_STATUS_KINDS),
  clubRole: z.enum(CLUB_ROLES).nullable().optional()
});

const AcceptInvitationSchema = z.object({
  consents: z.object({
    membership: z.boolean(),
    location: z.boolean(),
    emergencyPackage: z.boolean(),
    notifications: z.boolean()
  }),
  policyVersion: z.string().default('1.0')
});

const PatchMemberSchema = z.object({
  statusKind: z.enum(MEMBER_STATUS_KINDS).optional(),
  clubRole: z.enum(CLUB_ROLES).nullable().optional()
});

const TransferGroupSchema = z.object({
  toUserId: z.string().uuid()
});

const ImitationReportSchema = z.object({
  claimedGroupId: z.string().uuid().optional(),
  reason: z.string().min(5).max(500)
});

const ProposeVoteSchema = z.object({
  proposal: z.enum(['remove_member', 'remove_leader', 'declare_emergency', 'change_identity']),
  targetId: z.string().uuid().optional()
});

const BallotSchema = z.object({
  approve: z.boolean()
});

const VerifySubscriptionSchema = z.object({
  productId: z.string().min(3),
  purchaseToken: z.string().min(8),
  assignedGroupId: z.string().uuid().optional()
});

const RtdnWebhookSchema = z.object({
  messageId: z.string().min(3),
  purchaseToken: z.string().min(8),
  productId: z.string().min(3),
  notificationType: z.enum([
    'SUBSCRIPTION_PURCHASED',
    'SUBSCRIPTION_RENEWED',
    'SUBSCRIPTION_RECOVERED',
    'SUBSCRIPTION_IN_GRACE_PERIOD',
    'SUBSCRIPTION_ON_HOLD',
    'SUBSCRIPTION_CANCELED',
    'SUBSCRIPTION_EXPIRED',
    'SUBSCRIPTION_REVOKED'
  ]),
  eventTimestampIso: z.string().datetime().optional()
});

/**
 * Usa exclusivamente `req.ip` calculado por Fastify según `trustProxy` (saltos o IPs de confianza).
 * Si `trustProxy` es `false` (o si hay más saltos que los permitidos), un atacante no puede falsificar
 * `X-Forwarded-For` para eludir el bloqueo de intentos fallidos.
 */
function getClientIp(req: FastifyRequest): string {
  return req.ip || '127.0.0.1';
}

function requireUserId(req: FastifyRequest, reply: FastifyReply): string | null {
  const authHeader = req.headers.authorization;
  if (typeof authHeader === 'string' && authHeader.startsWith('Bearer ')) {
    const rawToken = authHeader.slice(7).trim();
    const verified = verifySessionToken(rawToken, undefined, 'access');
    if (verified) {
      return verified.sub;
    }
    reply.code(401).send({ error: 'UNAUTHORIZED', message: 'Token Bearer inválido o caducado' });
    return null;
  }
  const header = req.headers['x-user-id'];
  const uid = Array.isArray(header) ? header[0] : header;
  if (!uid || !z.string().uuid().safeParse(uid).success) {
    reply.code(401).send({ error: 'UNAUTHORIZED', message: 'Falta cabecera Authorization Bearer o x-user-id válida' });
    return null;
  }
  return uid;
}

async function appendAudit(
  db: DbExecutor,
  actor: string | null,
  action: string,
  targetType: string,
  targetId: string | null,
  detail: Record<string, unknown>
): Promise<void> {
  await db.query(
    `INSERT INTO audit_log (actor, action, target_type, target_id, detail)
     VALUES ($1, $2, $3, $4, $5)`,
    [actor, action, targetType, targetId, JSON.stringify(detail)]
  );
}

function parseBytea(raw: unknown): Buffer | null {
  if (!raw) return null;
  if (Buffer.isBuffer(raw)) {
    const asUtf8 = raw.toString('utf8');
    if (asUtf8.startsWith('\\x') && /^[0-9a-fA-F]+$/.test(asUtf8.slice(2))) {
      return Buffer.from(asUtf8.slice(2), 'hex');
    }
    return raw;
  }
  if (raw instanceof Uint8Array) {
    return Buffer.from(raw);
  }
  if (typeof raw === 'string') {
    if (raw.startsWith('\\x') && /^[0-9a-fA-F]+$/.test(raw.slice(2))) {
      return Buffer.from(raw.slice(2), 'hex');
    }
    if (/^[0-9a-fA-F]{16}$/.test(raw)) {
      return Buffer.from(raw, 'hex');
    }
    return Buffer.from(raw, 'binary');
  }
  return null;
}

export async function buildApp({
  db,
  logger = false,
  trustProxy,
  scryptLimiter = defaultScryptLimiter,
  forbidGuestClubRoles = P7_FORBID_GUEST_CLUB_ROLES,
  useFakeBilling = process.env.USE_FAKE_BILLING !== 'false',
  playVerifier = defaultFakePlayVerifier,
  credentialKeyring = defaultCredentialKeyring,
  env = process.env
}: BuildAppOptions): Promise<FastifyInstance> {
  // En producción, exigir AUTH_TOKEN_KEYS_JSON explícito y rechazar arranque con claves por defecto
  const verifiedKeyring = validateProductionAuthConfig(env);
  if (env.NODE_ENV === 'production') {
    setSessionKeyring(verifiedKeyring);
  }

  const resolvedTrustProxy: TrustProxyConfig =
    typeof trustProxy === 'number'
      ? createHopLimitTrustProxy(trustProxy)
      : (trustProxy ?? parseTrustProxyConfig(env));
  const fastifyOpts: FastifyServerOptions = {
    logger,
    trustProxy: resolvedTrustProxy
  };
  const app = Fastify(fastifyOpts);
  const loginTracker = new LoginAttemptTracker();

  await app.register(helmet);
  await app.register(cors, { origin: true });
  await app.register(rateLimit, { max: 200, timeWindow: '1 minute' });

  app.get('/health', async () => ({ status: 'ok' }));

  // ---------------------------------------------------------------------------
  // 1. Autenticación, rotación de refresh token y cierre de sesión (RULES R1.1, R1.2, R13.6, R15.1; D35, D37, D40)
  // ---------------------------------------------------------------------------
  const handleLoginOrRegister = async (req: FastifyRequest, reply: FastifyReply) => {
    const parsed = LoginBodySchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const {
      email,
      password,
      displayName,
      countryCode,
      acceptedTermsAndConvivencia,
      policyVersion,
      activateLeaderDemo
    } = parsed.data;

    const clientIp = getClientIp(req);
    if (loginTracker.isLockedOut(email, clientIp)) {
      return reply.code(429).send({
        error: 'TOO_MANY_AUTH_ATTEMPTS',
        message: 'Demasiados intentos fallidos desde esta dirección IP para esta cuenta; bloqueado por 15 minutos (D35)'
      });
    }

    if (!acceptedTermsAndConvivencia) {
      return reply.code(400).send({
        error: 'TERMS_REQUIRED',
        message: 'Debes aceptar las reglas de convivencia y términos (RULES R13.6)'
      });
    }

    const existing = await db.query(
      `SELECT id, email, password_hash, display_name, country_code, account_type, bike_model, map_color, status
       FROM users WHERE LOWER(email) = LOWER($1)`,
      [email]
    );

    let user = existing.rows[0];
    try {
      if (!user) {
        const id = randomUUID();
        const accountType = activateLeaderDemo ? 'leader' : 'member';
        const pwdHash = password ? await hashPasswordAsync(password, scryptLimiter) : null;
        const ins = await db.query(
          `INSERT INTO users (id, email, password_hash, display_name, country_code, account_type, map_color, status)
           VALUES ($1, $2, $3, $4, $5, $6, $7, 'active')
           RETURNING id, email, password_hash, display_name, country_code, account_type, bike_model, map_color, status`,
          [id, email.toLowerCase(), pwdHash, displayName ?? email.split('@')[0], countryCode, accountType, '#FFB300']
        );
        user = ins.rows[0];

        await db.query(
          `INSERT INTO rank_progress (user_id, valid_rides) VALUES ($1, 0)`,
          [user.id]
        );

        await db.query(
          `INSERT INTO consents (id, user_id, group_id, kind, granted, policy_version)
           VALUES ($1, $2, NULL, 'terms', true, $3)`,
          [randomUUID(), user.id, policyVersion]
        );

        if (activateLeaderDemo) {
          const periodEnd = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
          await db.query(
            `INSERT INTO subscriptions (
               id, user_id, kind, play_product_id, play_purchase_token,
               state, current_period_end, auto_renewing, last_verified_at
             ) VALUES ($1, $2, 'leader', 'leader_monthly', $3, 'active', $4, true, now())`,
            [randomUUID(), user.id, `demo-leader-token-${user.id}`, periodEnd]
          );
        }

        await appendAudit(db, user.id, 'USER_REGISTERED', 'user', user.id, {
          accountType: user.account_type,
          countryCode: user.country_code,
          hashAlgorithm: pwdHash ? AUTH_POLICY.HASH_ALGORITHM : 'none'
        });
      } else {
        const validPwd =
          !user.password_hash ||
          (Boolean(password) && (await verifyPasswordAsync(password!, user.password_hash, scryptLimiter)));
        if (!validPwd) {
          const attempts = loginTracker.recordFailure(email, clientIp);
          // El retardo progresivo se informa en el cuerpo JSON cerrando la respuesta de inmediato,
          // nunca reteniendo el socket HTTP abierto en el servidor.
          const backoffDelayMs = loginTracker.getProgressiveDelayMs(email, clientIp);
          return reply.code(401).send({
            error: 'INVALID_CREDENTIALS',
            remainingAttempts: Math.max(0, AUTH_POLICY.MAX_FAILED_ATTEMPTS - attempts),
            backoffDelayMs,
            message: 'Correo o contraseña incorrectos'
          });
        }
        loginTracker.reset(email, clientIp);

        if (activateLeaderDemo && user.account_type !== 'leader') {
          await db.query(`UPDATE users SET account_type = 'leader' WHERE id = $1`, [user.id]);
          user.account_type = 'leader';
          const periodEnd = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
          await db.query(
            `INSERT INTO subscriptions (
               id, user_id, kind, play_product_id, play_purchase_token,
               state, current_period_end, auto_renewing, last_verified_at
             ) VALUES ($1, $2, 'leader', 'leader_monthly', $3, 'active', $4, true, now())`,
            [randomUUID(), user.id, `demo-leader-token-${randomUUID()}`, periodEnd]
          );
        }
      }
    } catch (err) {
      if (err instanceof ScryptQueueOverflowError) {
        return reply
          .header('Retry-After', String(err.retryAfterSeconds))
          .code(503)
          .send({
            error: err.code,
            retryAfterSeconds: err.retryAfterSeconds,
            message: err.message
          });
      }
      throw err;
    }

    const access = issueSessionToken(user.id, 'access');
    const refresh = issueSessionToken(user.id, 'refresh');

    // Persistir únicamente el hash SHA-256 del refresh token en BD (D35, D40)
    await db.query(
      `INSERT INTO refresh_tokens (token_hash, user_id, expires_at)
       VALUES ($1, $2, $3)
       ON CONFLICT (token_hash) DO NOTHING`,
      [refresh.tokenHash, user.id, refresh.expiresAtIso]
    );

    return reply.code(200).send({
      token: user.id,
      accessToken: access.token,
      accessTokenExpiresAt: access.expiresAtIso,
      refreshToken: refresh.token,
      refreshTokenExpiresAt: refresh.expiresAtIso,
      authPolicy: {
        hashAlgorithm: AUTH_POLICY.HASH_ALGORITHM,
        accessTokenTtlSeconds: AUTH_POLICY.ACCESS_TOKEN_TTL_SECONDS,
        refreshTokenTtlSeconds: AUTH_POLICY.REFRESH_TOKEN_TTL_SECONDS,
        maxFailedAttempts: AUTH_POLICY.MAX_FAILED_ATTEMPTS,
        maxFailedAttemptsPerIp: AUTH_POLICY.MAX_FAILED_ATTEMPTS_PER_IP
      },
      user: {
        id: user.id,
        email: user.email,
        displayName: user.display_name,
        countryCode: user.country_code,
        accountType: user.account_type,
        bikeModel: user.bike_model ?? null,
        mapColor: user.map_color ?? '#FFB300',
        status: user.status
      }
    });
  };

  app.post('/v1/auth/login', handleLoginOrRegister);
  app.post('/v1/auth/register', handleLoginOrRegister);

  // Rotación de refresh token (OWASP: cada refresh invalida el token anterior y emite uno nuevo)
  app.post('/v1/auth/refresh', async (req, reply) => {
    const parsed = RefreshTokenBodySchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { refreshToken } = parsed.data;
    const payload = verifySessionToken(refreshToken, undefined, 'refresh');
    if (!payload) {
      return reply.code(401).send({ error: 'INVALID_REFRESH_TOKEN', message: 'Refresh token inválido o expirado' });
    }

    const tokenHash = hashRefreshToken(refreshToken);
    const rowRes = await db.query(
      `SELECT token_hash, user_id, expires_at, revoked_at, replaced_by_hash
       FROM refresh_tokens WHERE token_hash = $1`,
      [tokenHash]
    );
    const stored = rowRes.rows[0];
    if (!stored) {
      return reply.code(401).send({ error: 'REFRESH_TOKEN_NOT_FOUND' });
    }

    // Detección de robo/reuso de refresh token ya rotado: revoca todos los tokens activos del usuario
    if (stored.revoked_at) {
      await db.query(
        `UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`,
        [stored.user_id]
      );
      return reply.code(401).send({
        error: 'REFRESH_TOKEN_REUSED',
        message: 'Token de refresco ya utilizado o revocado; sesión invalidada por seguridad'
      });
    }

    if (new Date(stored.expires_at).getTime() <= Date.now()) {
      await db.query(`UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = $1`, [tokenHash]);
      return reply.code(401).send({ error: 'REFRESH_TOKEN_EXPIRED' });
    }

    const newAccess = issueSessionToken(String(stored.user_id), 'access');
    const newRefresh = issueSessionToken(String(stored.user_id), 'refresh');

    await db.query(
      `UPDATE refresh_tokens
       SET revoked_at = now(), replaced_by_hash = $1
       WHERE token_hash = $2`,
      [newRefresh.tokenHash, tokenHash]
    );

    await db.query(
      `INSERT INTO refresh_tokens (token_hash, user_id, expires_at)
       VALUES ($1, $2, $3)`,
      [newRefresh.tokenHash, stored.user_id, newRefresh.expiresAtIso]
    );

    return reply.code(200).send({
      accessToken: newAccess.token,
      accessTokenExpiresAt: newAccess.expiresAtIso,
      refreshToken: newRefresh.token,
      refreshTokenExpiresAt: newRefresh.expiresAtIso
    });
  });

  // Revocación explícita de refresh token al cerrar sesión
  app.post('/v1/auth/logout', async (req, reply) => {
    const parsed = RefreshTokenBodySchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const tokenHash = hashRefreshToken(parsed.data.refreshToken);
    const upd = await db.query(
      `UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = $1 AND revoked_at IS NULL`,
      [tokenHash]
    );
    return reply.code(200).send({
      status: 'logged_out',
      revoked: (upd.rowCount ?? 0) > 0
    });
  });

  // ---------------------------------------------------------------------------
  // 2. Perfil personal y avatar 64x64 WebP (RULES R1.3, DESIGN §7)
  // ---------------------------------------------------------------------------
  app.get('/v1/users/me', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const res = await db.query(
      `SELECT u.id, u.email, u.display_name, u.country_code, u.account_type,
              u.bike_model, u.map_color, u.avatar_webp, u.status,
              COALESCE(rp.valid_rides, 0) AS valid_rides
       FROM users u
       LEFT JOIN rank_progress rp ON rp.user_id = u.id
       WHERE u.id = $1`,
      [uid]
    );
    const u = res.rows[0];
    if (!u) return reply.code(404).send({ error: 'USER_NOT_FOUND' });

    const badgesRes = await db.query(
      `SELECT id, family, code, issuer_group, issuer_user, created_at
       FROM badges WHERE receiver = $1 ORDER BY created_at DESC`,
      [uid]
    );

    return {
      id: u.id,
      email: u.email,
      displayName: u.display_name,
      countryCode: u.country_code,
      accountType: u.account_type,
      bikeModel: u.bike_model ?? null,
      mapColor: u.map_color ?? '#FFB300',
      avatarWebpBase64: parseBytea(u.avatar_webp)?.toString('base64') ?? null,
      validRides: Number(u.valid_rides ?? 0),
      badges: badgesRes.rows
    };
  });

  const handlePatchProfile = async (req: FastifyRequest, reply: FastifyReply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const parsed = UpdateProfileSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { displayName, bikeModel, mapColor, avatarWebpBase64, publicKeyBase64 } = parsed.data;

    const avatarBuf = avatarWebpBase64 ? Buffer.from(avatarWebpBase64, 'base64') : undefined;
    if (avatarBuf && avatarBuf.length > 12_288) {
      return reply.code(400).send({
        error: 'AVATAR_TOO_LARGE',
        message: 'El avatar WebP 64x64 no debe superar 12 KB (DESIGN.md §7)'
      });
    }
    const pkBuf = publicKeyBase64 ? Buffer.from(publicKeyBase64, 'base64') : undefined;

    const cur = await db.query(
      `SELECT display_name, bike_model, map_color, avatar_webp, public_key FROM users WHERE id = $1`,
      [uid]
    );
    if (cur.rows.length === 0) return reply.code(404).send({ error: 'USER_NOT_FOUND' });
    const row = cur.rows[0];

    await db.query(
      `UPDATE users
       SET display_name = $1,
           bike_model = $2,
           map_color = $3,
           avatar_webp = $4,
           public_key = $5
       WHERE id = $6`,
      [
        displayName ?? row.display_name,
        bikeModel !== undefined ? bikeModel : row.bike_model,
        mapColor ?? row.map_color,
        avatarBuf !== undefined ? avatarBuf : row.avatar_webp,
        pkBuf !== undefined ? pkBuf : row.public_key,
        uid
      ]
    );

    return { updated: true };
  };

  app.patch('/v1/users/me', handlePatchProfile);

  // ---------------------------------------------------------------------------
  // 3. Crear grupo con unicidad de nombre (leet) y logo (pHash) por país (RULES R4, R5, R8.2)
  // ---------------------------------------------------------------------------
  app.post('/v1/groups', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const parsed = CreateGroupSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { countryCode, name, logoUrl, logoPhashHex } = parsed.data;

    const userRes = await db.query(
      `SELECT id, account_type, status FROM users WHERE id = $1`,
      [uid]
    );
    const user = userRes.rows[0];
    if (!user || user.status !== 'active') {
      return reply.code(403).send({ error: 'FORBIDDEN', message: 'Usuario inactivo o inexistente' });
    }
    if (user.account_type !== 'leader') {
      return reply.code(403).send({
        error: 'LEADER_REQUIRED',
        message: 'Solo una cuenta Líder activa ($15/mes) puede crear grupos (RULES R1.2, R4.1)'
      });
    }

    const nameNormalized = normalizeGroupName(name);
    if (nameNormalized.length < 3) {
      return reply.code(400).send({
        error: 'INVALID_GROUP_NAME',
        message: 'El nombre normalizado debe tener al menos 3 caracteres alfanuméricos'
      });
    }

    // Comprobar colisión de nombre normalizado, esqueleto leet o distancia de edición baja en el mismo país
    const sameCountryRes = await db.query(
      `SELECT id, name, name_normalized, logo_phash
       FROM groups
       WHERE country_code = $1 AND status <> 'deleted'`,
      [countryCode]
    );

    for (const existing of sameCountryRes.rows) {
      const nameCheck = checkGroupNameCollision(name, existing.name);
      if (nameCheck.collides) {
        return reply.code(409).send({
          error: 'GROUP_NAME_TAKEN_IN_COUNTRY',
          reason: nameCheck.reason,
          conflictingGroupName: existing.name,
          message: `Ya existe un grupo con nombre igual o muy similar en ${countryCode} (RULES R5.1)`
        });
      }
    }

    const candidatePhash = Buffer.from(logoPhashHex, 'hex');
    const logoCheck = evaluateLogoUniqueness(
      candidatePhash,
      sameCountryRes.rows.map((r) => ({
        id: String(r.id),
        logoPhash: parseBytea(r.logo_phash) ?? Buffer.alloc(0)
      }))
    );

    if (logoCheck.rejected) {
      return reply.code(409).send({
        error: 'GROUP_LOGO_TAKEN_IN_COUNTRY',
        closestGroupId: logoCheck.closestGroupId,
        hammingDistance: logoCheck.minHammingDistance,
        message: `El logo es demasiado similar (distancia Hamming=${logoCheck.minHammingDistance}) a otro grupo registrado en ${countryCode} (RULES R5.2)`
      });
    }

    const groupId = randomUUID();
    await db.query(
      `INSERT INTO groups (
         id, country_code, name, name_normalized, logo_url, logo_phash,
         principal_leader, role_permissions, status
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 'active')`,
      [
        groupId,
        countryCode,
        name.trim(),
        nameNormalized,
        logoUrl ?? null,
        candidatePhash,
        uid,
        JSON.stringify(DEFAULT_ROLE_PERMISSIONS)
      ]
    );

    await db.query(
      `INSERT INTO group_leaders (group_id, user_id, is_principal, club_role)
       VALUES ($1, $2, true, 'Presidente')`,
      [groupId, uid]
    );

    // R8.2: Fundador no es un rango: es una marca permanente del creador del grupo
    await db.query(
      `INSERT INTO badges (id, family, code, issuer_group, issuer_user, receiver)
       VALUES ($1, 'honor', 'founder', $2, $3, $3)`,
      [randomUUID(), groupId, uid]
    );

    await appendAudit(db, uid, 'GROUP_CREATED', 'group', groupId, {
      name: name.trim(),
      nameNormalized,
      countryCode,
      logoNeedsManualReview: logoCheck.needsManualReview
    });

    return reply.code(201).send({
      id: groupId,
      countryCode,
      name: name.trim(),
      nameNormalized,
      principalLeader: uid,
      logoNeedsManualReview: logoCheck.needsManualReview
    });
  });

  // ---------------------------------------------------------------------------
  // 4. Listar mis grupos y detalle de grupo (SCREENS D1, D3)
  // ---------------------------------------------------------------------------
  app.get('/v1/groups', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const ledRes = await db.query(
      `SELECT g.id, g.country_code, g.name, g.logo_url, g.principal_leader, g.status,
              gl.is_principal
       FROM group_leaders gl
       JOIN groups g ON g.id = gl.group_id
       WHERE gl.user_id = $1 AND g.status <> 'deleted'`,
      [uid]
    );

    const memberRes = await db.query(
      `SELECT g.id, g.country_code, g.name, g.logo_url, g.principal_leader, g.status,
              m.status_kind, m.club_role, m.paid_until
       FROM memberships m
       JOIN groups g ON g.id = m.group_id
       WHERE m.user_id = $1 AND m.invite_state = 'accepted' AND g.status <> 'deleted'`,
      [uid]
    );

    return {
      ledGroups: ledRes.rows.map((r) => ({
        id: r.id,
        countryCode: r.country_code,
        name: r.name,
        logoUrl: r.logo_url,
        principalLeader: r.principal_leader,
        status: r.status,
        myRole: r.is_principal ? 'principal_leader' : 'co_leader',
        paymentSemaphore: 'green' // R4.4: El líder no paga $2.50 en su propio grupo
      })),
      memberGroups: memberRes.rows.map((r) => ({
        id: r.id,
        countryCode: r.country_code,
        name: r.name,
        logoUrl: r.logo_url,
        principalLeader: r.principal_leader,
        status: r.status,
        statusKind: r.status_kind,
        clubRole: r.club_role,
        paymentSemaphore: computePaymentSemaphore(
          r.paid_until ? new Date(r.paid_until) : null,
          null
        )
      }))
    };
  });

  // ---------------------------------------------------------------------------
  // 5. Gestionar hasta 3 líderes por grupo (RULES R4.1, R4.3, R14.2, R14.3)
  // ---------------------------------------------------------------------------
  app.post('/v1/groups/:id/leaders', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };

    const parsed = AddLeaderSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { userId: candidateUserId } = parsed.data;

    const grpRes = await db.query(
      `SELECT id, principal_leader, status FROM groups WHERE id = $1`,
      [groupId]
    );
    const group = grpRes.rows[0];
    if (!group) return reply.code(404).send({ error: 'GROUP_NOT_FOUND' });

    // R4.3: Solo el líder principal (Fundador) puede nombrar a otros líderes
    if (group.principal_leader !== uid) {
      return reply.code(403).send({
        error: 'ONLY_PRINCIPAL_LEADER',
        message: 'Solo el líder principal puede nombrar a otros líderes (RULES R4.3)'
      });
    }

    const candRes = await db.query(
      `SELECT id, account_type, status FROM users WHERE id = $1`,
      [candidateUserId]
    );
    const candidate = candRes.rows[0];
    if (!candidate || candidate.status !== 'active' || candidate.account_type !== 'leader') {
      return reply.code(400).send({
        error: 'CANDIDATE_MUST_BE_ACTIVE_LEADER',
        message: 'El nuevo líder debe tener una cuenta de líder activa ($15/mes, RULES R4.1)'
      });
    }

    const currentLeadersRes = await db.query(
      `SELECT user_id FROM group_leaders WHERE group_id = $1`,
      [groupId]
    );
    if (currentLeadersRes.rows.length >= 3) {
      return reply.code(409).send({
        error: 'MAX_LEADERS_REACHED',
        message: 'Un grupo puede tener un máximo de 3 líderes (RULES R4.1)'
      });
    }

    await db.query(
      `INSERT INTO group_leaders (group_id, user_id, is_principal)
       VALUES ($1, $2, false)`,
      [groupId, candidateUserId]
    );

    await appendAudit(db, uid, 'LEADER_ADDED', 'group', groupId, {
      addedLeader: candidateUserId
    });

    return reply.code(201).send({ groupId, userId: candidateUserId, isPrincipal: false });
  });

  app.get('/v1/groups/:id', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };

    const grpRes = await db.query(
      `SELECT id, country_code, name, name_normalized, logo_url, principal_leader, role_permissions, status
       FROM groups WHERE id = $1 AND status <> 'deleted'`,
      [groupId]
    );
    const g = grpRes.rows[0];
    if (!g) return reply.code(404).send({ error: 'GROUP_NOT_FOUND' });

    const leadersRes = await db.query(
      `SELECT user_id, is_principal FROM group_leaders WHERE group_id = $1`,
      [groupId]
    );

    return {
      id: g.id,
      countryCode: g.country_code,
      name: g.name,
      nameNormalized: g.name_normalized,
      logoUrl: g.logo_url,
      principalLeader: g.principal_leader,
      status: g.status,
      rolePermissions: g.role_permissions,
      leaders: leadersRes.rows.map((l) => ({
        userId: l.user_id,
        isPrincipal: Boolean(l.is_principal)
      }))
    };
  });

  app.delete('/v1/groups/:id/leaders/:leaderUid', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId, leaderUid } = req.params as { id: string; leaderUid: string };

    const grpRes = await db.query(
      `SELECT id, principal_leader FROM groups WHERE id = $1`,
      [groupId]
    );
    const group = grpRes.rows[0];
    if (!group) return reply.code(404).send({ error: 'GROUP_NOT_FOUND' });

    // R14.3: El líder principal (Fundador) no puede ser removido; solo cede su puesto mediante transferencia
    if (group.principal_leader === leaderUid) {
      return reply.code(403).send({
        error: 'CANNOT_REMOVE_PRINCIPAL_LEADER',
        message: 'El líder principal (Fundador) solo cede su puesto mediante transferencia (RULES R14.3)'
      });
    }

    // Si no es renuncia propia, requiere que exista una votación aprobada remove_leader (RULES R14.2)
    if (uid !== leaderUid) {
      const approvedVote = await db.query(
        `SELECT id FROM leader_votes
         WHERE group_id = $1 AND proposal = 'remove_leader' AND target_id = $2 AND state = 'approved'`,
        [groupId, leaderUid]
      );
      if (approvedVote.rows.length === 0) {
        return reply.code(409).send({
          error: 'MAJORITY_VOTE_REQUIRED',
          message: 'Retirar a otro líder es una decisión sensible y requiere mayoría 2/3 en /v1/groups/:id/votes (RULES R14.2)'
        });
      }
    }

    await db.query(
      `DELETE FROM group_leaders WHERE group_id = $1 AND user_id = $2 AND is_principal = false`,
      [groupId, leaderUid]
    );

    await appendAudit(db, uid, 'LEADER_REMOVED', 'group', groupId, {
      removedLeader: leaderUid
    });

    return { groupId, removedLeaderId: leaderUid };
  });

  // ---------------------------------------------------------------------------
  // 6. Lista de miembros con semáforo de pagos (RULES R7.2, R3.3; BACKEND_LICENSING §8)
  // ---------------------------------------------------------------------------
  app.get('/v1/groups/:id/members', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };

    const leaderCheck = await db.query(
      `SELECT is_principal FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [groupId, uid]
    );
    const memberCheck = await db.query(
      `SELECT club_role, invite_state FROM memberships WHERE group_id = $1 AND user_id = $2`,
      [groupId, uid]
    );

    const isLeader = leaderCheck.rows.length > 0;
    const isTreasurer =
      memberCheck.rows[0]?.invite_state === 'accepted' &&
      memberCheck.rows[0]?.club_role === 'tesorero';
    const isAcceptedMember = memberCheck.rows[0]?.invite_state === 'accepted';

    if (!isLeader && !isAcceptedMember) {
      return reply.code(403).send({ error: 'FORBIDDEN' });
    }

    const canSeeTrafficLight = isLeader || isTreasurer;

    const leadersRes = await db.query(
      `SELECT gl.user_id, gl.is_principal, gl.added_at,
              u.display_name, u.bike_model, u.map_color,
              s.state AS sub_state, s.current_period_end, s.grace_until
       FROM group_leaders gl
       JOIN users u ON u.id = gl.user_id
       LEFT JOIN subscriptions s ON s.user_id = gl.user_id AND s.kind = 'leader'
       WHERE gl.group_id = $1
       ORDER BY gl.is_principal DESC, gl.added_at ASC`,
      [groupId]
    );

    const membersRes = await db.query(
      `SELECT m.id AS membership_id, m.user_id, m.status_kind, m.club_role,
              m.invite_state, m.accepted_at, m.paid_until,
              u.display_name, u.bike_model, u.map_color,
              COALESCE(rp.valid_rides, 0) AS valid_rides
       FROM memberships m
       JOIN users u ON u.id = m.user_id
       LEFT JOIN rank_progress rp ON rp.user_id = m.user_id
       WHERE m.group_id = $1 AND m.invite_state = 'accepted'
       ORDER BY m.accepted_at ASC`,
      [groupId]
    );

    return {
      leaders: leadersRes.rows.map((l) => ({
        userId: l.user_id,
        displayName: l.display_name,
        bikeModel: l.bike_model ?? null,
        mapColor: l.map_color ?? '#FFB300',
        isPrincipal: Boolean(l.is_principal),
        // R7.2: Solo estado del semáforo, jamás montos ni tarjeta
        paymentSemaphore: canSeeTrafficLight
          ? computePaymentSemaphore(
              l.current_period_end ? new Date(l.current_period_end) : new Date(Date.now() + 15 * 86400000),
              l.grace_until ? new Date(l.grace_until) : null
            )
          : undefined
      })),
      members: membersRes.rows.map((m) => ({
        membershipId: m.membership_id,
        userId: m.user_id,
        displayName: m.display_name,
        bikeModel: m.bike_model ?? null,
        mapColor: m.map_color ?? '#00E676',
        statusKind: m.status_kind,
        clubRole: m.club_role ?? null,
        validRides: Number(m.valid_rides ?? 0),
        paymentSemaphore: canSeeTrafficLight
          ? computePaymentSemaphore(
              m.paid_until ? new Date(m.paid_until) : null,
              null
            )
          : undefined
      }))
    };
  });

  // ---------------------------------------------------------------------------
  // 7. Invitaciones con consentimiento obligatorio (RULES R2.1, R2.2, R15.1)
  // ---------------------------------------------------------------------------
  app.post('/v1/groups/:id/invitations', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };

    const parsed = CreateInvitationSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { invitedUserId, statusKind, clubRole } = parsed.data;

    // P7 APROBADA (RULES.md §3 y §12, MEMORY.md D42): Los invitados (`guest`) no reciben cargos honoríficos
    if (!canMemberHoldClubRole(statusKind, clubRole, forbidGuestClubRoles)) {
      return reply.code(400).send({
        error: 'GUEST_CANNOT_HOLD_CLUB_ROLE',
        message: 'Los invitados (guest) no pueden recibir cargos honoríficos del club (RULES R3, P7 / D42)'
      });
    }

    const leaderRes = await db.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [groupId, uid]
    );
    if (leaderRes.rows.length === 0) {
      return reply.code(403).send({
        error: 'LEADER_REQUIRED',
        message: 'Solo un líder del grupo puede enviar invitaciones (RULES R2.1)'
      });
    }

    // Si se invita como full o prospect, comprobar que el usuario no sea ya full/prospect en otro grupo (RULES R2)
    if (statusKind === 'full' || statusKind === 'prospect') {
      const existingPrimary = await db.query(
        `SELECT group_id FROM memberships
         WHERE user_id = $1 AND status_kind IN ('full','prospect') AND invite_state = 'accepted'
           AND group_id <> $2`,
        [invitedUserId, groupId]
      );
      if (existingPrimary.rows.length > 0) {
        return reply.code(409).send({
          error: 'ALREADY_IN_PRIMARY_GROUP',
          message:
            'El usuario ya pertenece a otro grupo como Full member o Prospecto; solo puede ser Invitado en grupos adicionales (RULES R2)'
        });
      }
    }

    const invId = randomUUID();
    const expiresAt = new Date(Date.now() + 72 * 60 * 60 * 1000).toISOString(); // 72h según BACKEND_LICENSING §6.2
    await db.query(
      `INSERT INTO invitations (id, group_id, invited_user, invited_by, status_kind, state, expires_at)
       VALUES ($1, $2, $3, $4, $5, 'pending', $6)`,
      [invId, groupId, invitedUserId, uid, statusKind, expiresAt]
    );

    await appendAudit(db, uid, 'INVITATION_SENT', 'invitation', invId, {
      groupId,
      invitedUserId,
      statusKind
    });

    return reply.code(201).send({
      id: invId,
      groupId,
      invitedUserId,
      statusKind,
      monthlyCostUsd: 2.5,
      state: 'pending',
      expiresAt
    });
  });

  app.get('/v1/invitations/me', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const res = await db.query(
      `SELECT i.id, i.group_id, i.invited_by, i.status_kind, i.state, i.expires_at,
              g.name AS group_name, g.country_code, g.logo_url
       FROM invitations i
       JOIN groups g ON g.id = i.group_id
       WHERE i.invited_user = $1 AND i.state = 'pending'
       ORDER BY i.created_at DESC`,
      [uid]
    );

    return {
      invitations: res.rows.map((r) => ({
        id: r.id,
        groupId: r.group_id,
        groupName: r.group_name,
        countryCode: r.country_code,
        logoUrl: r.logo_url,
        statusKind: r.status_kind,
        monthlyCostUsd: 2.5,
        expiresAt: r.expires_at
      }))
    };
  });

  app.post('/v1/invitations/:id/accept', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: invId } = req.params as { id: string };

    const parsed = AcceptInvitationSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { consents, policyVersion } = parsed.data;

    // R2.1 y R15.1: El consentimiento de pertenencia y cuota ($2.50/mes) es obligatorio para entrar al grupo
    if (!consents.membership) {
      return reply.code(400).send({
        error: 'MEMBERSHIP_CONSENT_REQUIRED',
        message: 'Nadie entra a un grupo sin aceptar la pertenencia y cuota de $2.50/mes (RULES R2.1, R15.1a)'
      });
    }

    const invRes = await db.query(
      `SELECT id, group_id, invited_user, status_kind, state, expires_at
       FROM invitations WHERE id = $1`,
      [invId]
    );
    const inv = invRes.rows[0];
    if (!inv) return reply.code(404).send({ error: 'INVITATION_NOT_FOUND' });
    if (inv.invited_user !== uid) {
      return reply.code(403).send({ error: 'FORBIDDEN' });
    }
    if (inv.state !== 'pending') {
      return reply.code(409).send({ error: 'INVITATION_NOT_PENDING', state: inv.state });
    }
    if (new Date(inv.expires_at).getTime() <= Date.now()) {
      await db.query(`UPDATE invitations SET state = 'expired' WHERE id = $1`, [invId]);
      return reply.code(410).send({ error: 'INVITATION_EXPIRED' });
    }

    if (inv.status_kind === 'full' || inv.status_kind === 'prospect') {
      const existingPrimary = await db.query(
        `SELECT group_id FROM memberships
         WHERE user_id = $1 AND status_kind IN ('full','prospect') AND invite_state = 'accepted'
           AND group_id <> $2`,
        [uid, inv.group_id]
      );
      if (existingPrimary.rows.length > 0) {
        return reply.code(409).send({
          error: 'ALREADY_IN_PRIMARY_GROUP',
          message: 'Solo puedes pertenecer a un grupo como Full member o Prospecto (RULES R2)'
        });
      }
    }

    const membershipId = randomUUID();
    const paidUntil = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

    await db.query(`UPDATE invitations SET state = 'accepted' WHERE id = $1`, [invId]);
    await db.query(
      `INSERT INTO memberships (
         id, group_id, user_id, status_kind, club_role, invite_state, accepted_at, paid_until
       ) VALUES ($1, $2, $3, $4, NULL, 'accepted', now(), $5)`,
      [membershipId, inv.group_id, uid, inv.status_kind, paidUntil]
    );

    // Registrar los 4 consentimientos separados por grupo (RULES R15.1)
    const consentEntries: Array<[string, boolean]> = [
      ['membership', consents.membership],
      ['location', consents.location],
      ['emergency_package', consents.emergencyPackage],
      ['notifications', consents.notifications]
    ];
    for (const [kind, granted] of consentEntries) {
      await db.query(
        `INSERT INTO consents (id, user_id, group_id, kind, granted, policy_version)
         VALUES ($1, $2, $3, $4, $5, $6)`,
        [randomUUID(), uid, inv.group_id, kind, granted, policyVersion]
      );
    }

    await appendAudit(db, uid, 'INVITATION_ACCEPTED', 'membership', membershipId, {
      groupId: inv.group_id,
      statusKind: inv.status_kind
    });

    return reply.code(200).send({
      membershipId,
      groupId: inv.group_id,
      statusKind: inv.status_kind,
      inviteState: 'accepted',
      paidUntil
    });
  });

  app.post('/v1/invitations/:id/decline', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: invId } = req.params as { id: string };

    const invRes = await db.query(
      `SELECT id, invited_user, state FROM invitations WHERE id = $1`,
      [invId]
    );
    const inv = invRes.rows[0];
    if (!inv) return reply.code(404).send({ error: 'INVITATION_NOT_FOUND' });
    if (inv.invited_user !== uid) return reply.code(403).send({ error: 'FORBIDDEN' });

    await db.query(`UPDATE invitations SET state = 'declined' WHERE id = $1`, [invId]);
    return { id: invId, state: 'declined' };
  });

  // ---------------------------------------------------------------------------
  // 8. Cambiar estatus o cargo de un miembro (solo líderes: RULES R3.1, R3.2)
  // ---------------------------------------------------------------------------
  app.patch('/v1/groups/:id/members/:memberUid', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId, memberUid } = req.params as { id: string; memberUid: string };

    const parsed = PatchMemberSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { statusKind, clubRole } = parsed.data;

    const leaderRes = await db.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [groupId, uid]
    );
    if (leaderRes.rows.length === 0) {
      return reply.code(403).send({
        error: 'LEADER_REQUIRED',
        message: 'Solo los líderes crean, asignan o cambian cargos y estatus (RULES R3.2)'
      });
    }

    const memRes = await db.query(
      `SELECT id, status_kind, club_role FROM memberships
       WHERE group_id = $1 AND user_id = $2 AND invite_state = 'accepted'`,
      [groupId, memberUid]
    );
    const mem = memRes.rows[0];
    if (!mem) return reply.code(404).send({ error: 'MEMBERSHIP_NOT_FOUND' });

    if (statusKind && (statusKind === 'full' || statusKind === 'prospect')) {
      const otherPrimary = await db.query(
        `SELECT group_id FROM memberships
         WHERE user_id = $1 AND status_kind IN ('full','prospect') AND invite_state = 'accepted'
           AND group_id <> $2`,
        [memberUid, groupId]
      );
      if (otherPrimary.rows.length > 0) {
        return reply.code(409).send({
          error: 'ALREADY_IN_PRIMARY_GROUP',
          message: 'El miembro ya es Full o Prospecto en otro grupo (RULES R2)'
        });
      }
    }

    const nextStatus = statusKind ?? mem.status_kind;
    const nextRole = clubRole !== undefined ? clubRole : mem.club_role;

    // P7 APROBADA (RULES.md §3 y §12, MEMORY.md D42): Los invitados (`guest`) no reciben cargos honoríficos
    if (!canMemberHoldClubRole(nextStatus, nextRole, forbidGuestClubRoles)) {
      return reply.code(400).send({
        error: 'GUEST_CANNOT_HOLD_CLUB_ROLE',
        message: 'Los invitados (guest) no pueden recibir cargos honoríficos del club (RULES R3, P7 / D42)'
      });
    }

    await db.query(
      `UPDATE memberships SET status_kind = $1, club_role = $2 WHERE id = $3`,
      [nextStatus, nextRole, mem.id]
    );

    await appendAudit(db, uid, 'MEMBER_UPDATED', 'membership', mem.id, {
      groupId,
      memberUid,
      statusKind: nextStatus,
      clubRole: nextRole
    });

    return {
      membershipId: mem.id,
      userId: memberUid,
      statusKind: nextStatus,
      clubRole: nextRole
    };
  });

  // ---------------------------------------------------------------------------
  // 9. Transferencia de grupo con espera de 7 días y cancelación (RULES R4.3, R5.3)
  // ---------------------------------------------------------------------------
  app.post('/v1/groups/:id/transfer', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };

    const parsed = TransferGroupSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { toUserId } = parsed.data;

    const grpRes = await db.query(
      `SELECT id, principal_leader FROM groups WHERE id = $1`,
      [groupId]
    );
    const group = grpRes.rows[0];
    if (!group) return reply.code(404).send({ error: 'GROUP_NOT_FOUND' });
    if (group.principal_leader !== uid) {
      return reply.code(403).send({
        error: 'ONLY_PRINCIPAL_LEADER',
        message: 'Solo el líder principal puede iniciar la transferencia del grupo (RULES R4.3)'
      });
    }

    // R5.3: Solo puede transferirse a un miembro del grupo con cuenta de líder activa
    const inMemberships = await db.query(
      `SELECT id FROM memberships WHERE group_id = $1 AND user_id = $2 AND invite_state = 'accepted'`,
      [groupId, toUserId]
    );
    const inLeaders = await db.query(
      `SELECT user_id FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [groupId, toUserId]
    );
    if (inMemberships.rows.length === 0 && inLeaders.rows.length === 0) {
      return reply.code(400).send({
        error: 'RECIPIENT_NOT_IN_GROUP',
        message: 'El grupo solo puede transferirse entre miembros del grupo (RULES R5.3)'
      });
    }

    const toUserRes = await db.query(
      `SELECT account_type, status FROM users WHERE id = $1`,
      [toUserId]
    );
    const toUser = toUserRes.rows[0];
    if (!toUser || toUser.status !== 'active' || toUser.account_type !== 'leader') {
      return reply.code(400).send({
        error: 'RECIPIENT_NOT_ACTIVE_LEADER',
        message: 'El receptor debe tener cuenta de líder activa (RULES R5.3)'
      });
    }

    const transferId = randomUUID();
    const executesAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();
    await db.query(
      `INSERT INTO group_transfers (id, group_id, from_user, to_user, state, executes_at)
       VALUES ($1, $2, $3, $4, 'pending', $5)`,
      [transferId, groupId, uid, toUserId, executesAt]
    );

    await db.query(`UPDATE groups SET status = 'transfer_pending' WHERE id = $1`, [groupId]);

    await appendAudit(db, uid, 'GROUP_TRANSFER_INITIATED', 'group_transfer', transferId, {
      groupId,
      fromUser: uid,
      toUser: toUserId,
      executesAt
    });

    return reply.code(201).send({
      id: transferId,
      groupId,
      fromUser: uid,
      toUser: toUserId,
      state: 'pending',
      executesAt
    });
  });

  const handleCancelTransfer = async (req: FastifyRequest, reply: FastifyReply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const params = req.params as { id?: string; transferId?: string };
    const transferId = params.transferId ?? params.id;

    const trRes = await db.query(
      `SELECT id, group_id, from_user, to_user, state, executes_at
       FROM group_transfers WHERE id = $1`,
      [transferId]
    );
    const tr = trRes.rows[0];
    if (!tr) return reply.code(404).send({ error: 'TRANSFER_NOT_FOUND' });
    if (tr.from_user !== uid && tr.to_user !== uid) {
      return reply.code(403).send({ error: 'FORBIDDEN' });
    }
    if (tr.state !== 'pending' && tr.state !== 'accepted') {
      return reply.code(409).send({ error: 'TRANSFER_NOT_CANCELABLE' });
    }

    await db.query(`UPDATE group_transfers SET state = 'canceled' WHERE id = $1`, [transferId]);
    await db.query(`UPDATE groups SET status = 'active' WHERE id = $1`, [tr.group_id]);

    await appendAudit(db, uid, 'GROUP_TRANSFER_CANCELED', 'group_transfer', transferId ?? null, {
      groupId: tr.group_id
    });

    return { id: transferId, state: 'canceled' };
  };

  const handleAcceptTransfer = async (req: FastifyRequest, reply: FastifyReply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const params = req.params as { id?: string; transferId?: string };
    const transferId = params.transferId ?? params.id;

    const trRes = await db.query(
      `SELECT id, group_id, from_user, to_user, state, executes_at
       FROM group_transfers WHERE id = $1`,
      [transferId]
    );
    const tr = trRes.rows[0];
    if (!tr) return reply.code(404).send({ error: 'TRANSFER_NOT_FOUND' });
    if (tr.to_user !== uid) return reply.code(403).send({ error: 'FORBIDDEN' });
    if (tr.state !== 'pending') return reply.code(409).send({ error: 'TRANSFER_NOT_PENDING' });

    await db.query(`UPDATE group_transfers SET state = 'accepted' WHERE id = $1`, [transferId]);
    await appendAudit(db, uid, 'GROUP_TRANSFER_ACCEPTED', 'group_transfer', transferId ?? null, {
      groupId: tr.group_id
    });
    return { id: transferId, state: 'accepted', executesAt: tr.executes_at };
  };

  const handleExecuteTransfer = async (req: FastifyRequest, reply: FastifyReply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const params = req.params as { id?: string; transferId?: string };
    const transferId = params.transferId ?? params.id;

    const trRes = await db.query(
      `SELECT id, group_id, from_user, to_user, state, executes_at
       FROM group_transfers WHERE id = $1`,
      [transferId]
    );
    const tr = trRes.rows[0];
    if (!tr) return reply.code(404).send({ error: 'TRANSFER_NOT_FOUND' });
    if (tr.from_user !== uid && tr.to_user !== uid) return reply.code(403).send({ error: 'FORBIDDEN' });
    if (tr.state !== 'pending' && tr.state !== 'accepted') {
      return reply.code(409).send({ error: 'TRANSFER_NOT_EXECUTABLE' });
    }
    if (new Date(tr.executes_at).getTime() > Date.now()) {
      return reply.code(409).send({
        error: 'TRANSFER_HOLD_NOT_ELAPSED',
        executesAt: tr.executes_at,
        message: 'Aún no se cumplen los 7 días de espera (RULES R5.3)'
      });
    }

    await db.query(`UPDATE group_leaders SET is_principal = false WHERE group_id = $1`, [tr.group_id]);
    await db.query(
      `INSERT INTO group_leaders (group_id, user_id, is_principal)
       VALUES ($1, $2, true)
       ON CONFLICT (group_id, user_id) DO UPDATE SET is_principal = true`,
      [tr.group_id, tr.to_user]
    );
    await db.query(
      `UPDATE groups SET principal_leader = $1, status = 'active' WHERE id = $2`,
      [tr.to_user, tr.group_id]
    );
    await db.query(`UPDATE group_transfers SET state = 'executed' WHERE id = $1`, [transferId]);

    await appendAudit(db, uid, 'GROUP_TRANSFER_EXECUTED', 'group_transfer', transferId ?? null, {
      groupId: tr.group_id,
      newPrincipalLeader: tr.to_user
    });

    return { id: transferId, state: 'executed', newPrincipalLeader: tr.to_user };
  };

  app.post('/v1/transfers/:id/cancel', handleCancelTransfer);
  app.post('/v1/groups/:id/transfer/:transferId/cancel', handleCancelTransfer);
  app.post('/v1/transfers/:id/accept', handleAcceptTransfer);
  app.post('/v1/groups/:id/transfer/:transferId/accept', handleAcceptTransfer);
  app.post('/v1/transfers/:id/execute', handleExecuteTransfer);
  app.post('/v1/groups/:id/transfer/:transferId/execute', handleExecuteTransfer);

  // ---------------------------------------------------------------------------
  // 10. Votación de decisiones sensibles entre líderes (RULES R14.2, R14.4)
  // ---------------------------------------------------------------------------
  app.post('/v1/groups/:id/votes', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };

    const parsed = ProposeVoteSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { proposal, targetId } = parsed.data;

    const leaderRes = await db.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [groupId, uid]
    );
    if (leaderRes.rows.length === 0) {
      return reply.code(403).send({ error: 'LEADER_REQUIRED' });
    }

    // R14.4: Un líder no puede proponer ni votar su propia sanción
    if (targetId && targetId === uid && (proposal === 'remove_leader' || proposal === 'remove_member')) {
      return reply.code(400).send({
        error: 'CANNOT_VOTE_OWN_SANCTION',
        message: 'Un líder no puede votar su propia sanción (RULES R14.4)'
      });
    }

    const voteId = randomUUID();
    const expiresAt = new Date(Date.now() + 48 * 60 * 60 * 1000).toISOString();
    await db.query(
      `INSERT INTO leader_votes (id, group_id, proposal, target_id, proposed_by, state, expires_at)
       VALUES ($1, $2, $3, $4, $5, 'open', $6)`,
      [voteId, groupId, proposal, targetId ?? null, uid, expiresAt]
    );

    // El proponente emite automáticamente su voto a favor
    await db.query(
      `INSERT INTO leader_vote_ballots (vote_id, leader, approve) VALUES ($1, $2, true)`,
      [voteId, uid]
    );

    const status = await evaluateVoteOutcome(db, voteId, groupId, proposal, targetId ?? null);

    await appendAudit(db, uid, 'SENSITIVE_VOTE_PROPOSED', 'leader_vote', voteId, {
      groupId,
      proposal,
      targetId: targetId ?? null,
      state: status
    });

    return reply.code(201).send({ id: voteId, groupId, proposal, state: status });
  });

  app.post('/v1/votes/:id/ballot', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: voteId } = req.params as { id: string };

    const parsed = BallotSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { approve } = parsed.data;

    const vRes = await db.query(
      `SELECT id, group_id, proposal, target_id, state FROM leader_votes WHERE id = $1`,
      [voteId]
    );
    const vote = vRes.rows[0];
    if (!vote) return reply.code(404).send({ error: 'VOTE_NOT_FOUND' });
    if (vote.state !== 'open') {
      return reply.code(409).send({ error: 'VOTE_ALREADY_CLOSED', state: vote.state });
    }

    // R14.4: Un líder no puede votar su propia sanción
    if (vote.target_id && vote.target_id === uid) {
      return reply.code(403).send({
        error: 'CANNOT_VOTE_OWN_SANCTION',
        message: 'Un líder no puede votar su propia sanción (RULES R14.4)'
      });
    }

    const leaderRes = await db.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [vote.group_id, uid]
    );
    if (leaderRes.rows.length === 0) {
      return reply.code(403).send({ error: 'LEADER_REQUIRED' });
    }

    await db.query(
      `INSERT INTO leader_vote_ballots (vote_id, leader, approve)
       VALUES ($1, $2, $3)
       ON CONFLICT (vote_id, leader) DO UPDATE SET approve = EXCLUDED.approve`,
      [voteId, uid, approve]
    );

    const newState = await evaluateVoteOutcome(
      db,
      voteId,
      vote.group_id,
      vote.proposal,
      vote.target_id
    );

    await appendAudit(db, uid, 'SENSITIVE_VOTE_BALLOT', 'leader_vote', voteId, {
      approve,
      resultingState: newState
    });

    return { id: voteId, state: newState };
  });

  // ---------------------------------------------------------------------------
  // 11. Denuncia por imitación de nombre o logo (RULES R5.4)
  // ---------------------------------------------------------------------------
  app.post('/v1/groups/:id/imitation-reports', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: reportedGroupId } = req.params as { id: string };

    const parsed = ImitationReportSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { claimedGroupId, reason } = parsed.data;

    const reportId = randomUUID();
    await db.query(
      `INSERT INTO imitation_reports (id, reporter_user, claimed_group_id, reported_group_id, reason, state)
       VALUES ($1, $2, $3, $4, $5, 'pending_review')`,
      [reportId, uid, claimedGroupId ?? null, reportedGroupId, reason]
    );

    await appendAudit(db, uid, 'IMITATION_REPORT_FILED', 'imitation_report', reportId, {
      reportedGroupId,
      claimedGroupId: claimedGroupId ?? null
    });

    return reply.code(201).send({ id: reportId, state: 'pending_review' });
  });

  // ---------------------------------------------------------------------------
  // 12. Consentimientos del usuario y borrado de cuenta RGPD (RULES R15.1, R15.2, R15.3)
  // ---------------------------------------------------------------------------
  app.get('/v1/users/me/consents', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const res = await db.query(
      `SELECT id, group_id, kind, granted, policy_version, created_at, revoked_at
       FROM consents WHERE user_id = $1 ORDER BY created_at DESC`,
      [uid]
    );
    return { consents: res.rows };
  });

  app.delete('/v1/users/me', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    // Obtener grupos afectados para notificar a sus líderes (RULES R15.3)
    const groupsRes = await db.query(
      `SELECT DISTINCT group_id FROM memberships WHERE user_id = $1 AND invite_state = 'accepted'`,
      [uid]
    );
    const affectedGroupIds = groupsRes.rows.map((r) => String(r.group_id));

    await db.query(
      `UPDATE memberships SET invite_state = 'left' WHERE user_id = $1 AND invite_state = 'accepted'`,
      [uid]
    );
    await db.query(
      `UPDATE consents SET granted = false, revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`,
      [uid]
    );
    await db.query(
      `UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`,
      [uid]
    );
    await db.query(
      `UPDATE users
       SET status = 'deleted',
           display_name = 'Usuario Eliminado',
           email = $2,
           password_hash = NULL,
           avatar_webp = NULL,
           bike_model = NULL,
           public_key = NULL
       WHERE id = $1`,
      [uid, `deleted-${uid}@anon.rutabikersync.local`]
    );

    await appendAudit(db, uid, 'USER_DELETED_GDPR', 'user', uid, {
      notifiedLeaderGroups: affectedGroupIds
    });

    return {
      deleted: true,
      notifiedLeaderGroups: affectedGroupIds
    };
  });

  // ---------------------------------------------------------------------------
  // 13. Fase 2 — Suscripciones (6 estados), RTDN idempotente, Credenciales Ed25519 (kid) y Revocaciones
  //     (BACKEND_LICENSING.md §§5, 6, 7, 8, 9 y RULES R4.4, R4.5, R4.6, R7.1, R7.2, R7.3)
  // ---------------------------------------------------------------------------
  app.post('/v1/subscriptions/verify', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const parsed = VerifySubscriptionSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { productId, purchaseToken, assignedGroupId } = parsed.data;

    // Bloqueo deliberado si se desactiva el cliente simulado antes de tener docs/PLAY_P4_RESULTS.md
    if (!useFakeBilling && !isPlayP4ResultsDocumentPresent()) {
      return reply.code(503).send({
        error: 'PLAY_INTEGRATION_STANDBY_AWAITING_P4',
        message:
          'La verificación contra Google Play Developer API real está en standby hasta que exista docs/PLAY_P4_RESULTS.md (ver PLAY_TEST_SETUP.md)'
      });
    }

    let verified: ReturnType<FakePlayBillingVerifier['verifyPurchaseToken']>;
    try {
      verified = playVerifier.verifyPurchaseToken(productId, purchaseToken);
    } catch (err) {
      return reply.code(400).send({
        error: 'INVALID_PLAY_PRODUCT_ID',
        message: (err as Error).message
      });
    }

    const subId = randomUUID();
    const periodEndIso = verified.currentPeriodEnd.toISOString();
    const graceUntilIso = verified.graceUntil ? verified.graceUntil.toISOString() : null;

    await db.query(
      `INSERT INTO subscriptions (
         id, user_id, kind, play_product_id, play_purchase_token,
         state, current_period_end, grace_until, auto_renewing, assigned_group_id, last_verified_at
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, now())
       ON CONFLICT (play_purchase_token) DO UPDATE SET
         state = EXCLUDED.state,
         current_period_end = EXCLUDED.current_period_end,
         grace_until = EXCLUDED.grace_until,
         auto_renewing = EXCLUDED.auto_renewing,
         assigned_group_id = COALESCE(EXCLUDED.assigned_group_id, subscriptions.assigned_group_id),
         last_verified_at = now()`,
      [
        subId,
        uid,
        verified.kind,
        verified.productId,
        verified.purchaseToken,
        verified.state,
        periodEndIso,
        graceUntilIso,
        verified.autoRenewing,
        assignedGroupId ?? null
      ]
    );

    const evalStatus = evaluateSubscriptionState({
      state: verified.state,
      currentPeriodEnd: verified.currentPeriodEnd,
      graceUntil: verified.graceUntil,
      autoRenewing: verified.autoRenewing
    });

    if (verified.kind === 'leader' && evalStatus.accessGranted) {
      await db.query(`UPDATE users SET account_type = 'leader' WHERE id = $1`, [uid]);
      const ledGroups = await db.query(
        `SELECT group_id FROM group_leaders WHERE user_id = $1`,
        [uid]
      );
      for (const row of ledGroups.rows) {
        await reconcileGroupLeaderSubscriptions(db, String(row.group_id));
      }
    } else if (verified.kind === 'member_slot' && assignedGroupId && evalStatus.accessGranted) {
      await db.query(
        `UPDATE memberships SET paid_until = $1 WHERE group_id = $2 AND user_id = $3`,
        [periodEndIso, assignedGroupId, uid]
      );
    }

    await appendAudit(db, uid, 'SUBSCRIPTION_VERIFIED', 'subscription', subId, {
      productId: verified.productId,
      kind: verified.kind,
      state: evalStatus.effectiveState,
      assignedGroupId: assignedGroupId ?? null,
      simulated: useFakeBilling
    });

    return reply.code(200).send({
      productId: verified.productId,
      kind: verified.kind,
      state: evalStatus.effectiveState,
      accessGranted: evalStatus.accessGranted,
      alert: evalStatus.alert,
      currentPeriodEnd: periodEndIso,
      graceUntil: graceUntilIso,
      effectiveAccessUntil: evalStatus.effectiveAccessUntil?.toISOString() ?? null,
      paymentSemaphore: evalStatus.paymentSemaphore,
      alarms: evalStatus.alarms,
      assignedGroupId: assignedGroupId ?? null
    });
  });

  app.get('/v1/subscriptions/me', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const res = await db.query(
      `SELECT id, user_id, kind, play_product_id, play_purchase_token,
              state, current_period_end, grace_until, auto_renewing, assigned_group_id, last_verified_at
       FROM subscriptions
       WHERE user_id = $1
       ORDER BY last_verified_at DESC`,
      [uid]
    );

    const now = new Date();
    const subscriptions = res.rows.map((r) => {
      const periodEnd = new Date(r.current_period_end);
      const graceUntil = r.grace_until ? new Date(r.grace_until) : null;
      const evaluated = evaluateSubscriptionState(
        {
          state: r.state as SubscriptionState,
          currentPeriodEnd: periodEnd,
          graceUntil,
          autoRenewing: Boolean(r.auto_renewing)
        },
        now
      );
      return {
        id: r.id,
        kind: r.kind as SubscriptionKind,
        productId: r.play_product_id,
        state: evaluated.effectiveState,
        storedState: r.state,
        accessGranted: evaluated.accessGranted,
        alert: evaluated.alert,
        currentPeriodEnd: periodEnd.toISOString(),
        graceUntil: graceUntil ? graceUntil.toISOString() : null,
        effectiveAccessUntil: evaluated.effectiveAccessUntil?.toISOString() ?? null,
        autoRenewing: Boolean(r.auto_renewing),
        assignedGroupId: r.assigned_group_id ?? null,
        paymentSemaphore: evaluated.paymentSemaphore,
        alarms: evaluated.alarms,
        daysRemaining: evaluated.daysRemaining
      };
    });

    return { subscriptions };
  });

  app.post('/v1/play/rtdn', async (req, reply) => {
    const parsed = RtdnWebhookSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { messageId, purchaseToken, productId, notificationType, eventTimestampIso } =
      parsed.data;

    // 1. Idempotencia estricta sobre la tabla `play_events` (BACKEND_LICENSING.md §§4, 6.3)
    const existingEvt = await db.query(
      `SELECT message_id FROM play_events WHERE message_id = $1`,
      [messageId]
    );
    if (existingEvt.rows.length > 0) {
      return reply.code(200).send({
        messageId,
        status: 'duplicate_ignored',
        idempotent: true
      });
    }

    await db.query(
      `INSERT INTO play_events (message_id, payload) VALUES ($1, $2)`,
      [messageId, JSON.stringify(parsed.data)]
    );

    const subRes = await db.query(
      `SELECT id, user_id, kind, play_product_id, state, current_period_end, grace_until, auto_renewing, assigned_group_id
       FROM subscriptions WHERE play_purchase_token = $1`,
      [purchaseToken]
    );
    const subRow = subRes.rows[0];
    if (!subRow) {
      return reply.code(200).send({
        messageId,
        status: 'unknown_purchase_token_recorded',
        idempotent: false
      });
    }

    const now = eventTimestampIso ? new Date(eventTimestampIso) : new Date();
    playVerifier.seedPurchase({
      purchaseToken,
      productId: String(subRow.play_product_id),
      kind: subRow.kind as SubscriptionKind,
      state: subRow.state as SubscriptionState,
      currentPeriodEnd: new Date(subRow.current_period_end),
      graceUntil: subRow.grace_until ? new Date(subRow.grace_until) : null,
      autoRenewing: Boolean(subRow.auto_renewing)
    });

    const updated = playVerifier.applyRtdnTransition(
      purchaseToken,
      productId,
      notificationType as RtdnNotificationType,
      now
    );

    await db.query(
      `UPDATE subscriptions
       SET state = $1,
           current_period_end = $2,
           grace_until = $3,
           auto_renewing = $4,
           last_verified_at = now()
       WHERE id = $5`,
      [
        updated.state,
        updated.currentPeriodEnd.toISOString(),
        updated.graceUntil ? updated.graceUntil.toISOString() : null,
        updated.autoRenewing,
        subRow.id
      ]
    );

    const evalStatus = evaluateSubscriptionState(updated, now);

    // Actualizar paid_until de membresía si es un cupo de miembro asignado a un grupo
    if (subRow.kind === 'member_slot' && subRow.assigned_group_id) {
      const nextPaidUntil = evalStatus.accessGranted
        ? updated.currentPeriodEnd.toISOString()
        : now.toISOString();
      await db.query(
        `UPDATE memberships SET paid_until = $1 WHERE group_id = $2 AND user_id = $3`,
        [nextPaidUntil, subRow.assigned_group_id, subRow.user_id]
      );
    }

    // Si es suscripción de líder, evaluar reglas R4.5 (promoción a los 7 días) y R4.6 (suspensión si caen los 3 líderes)
    const reconciliations = [];
    if (subRow.kind === 'leader') {
      const ledGroups = await db.query(
        `SELECT group_id FROM group_leaders WHERE user_id = $1`,
        [subRow.user_id]
      );
      for (const row of ledGroups.rows) {
        const rec = await reconcileGroupLeaderSubscriptions(db, String(row.group_id), now);
        if (rec) reconciliations.push(rec);
      }
    }

    await appendAudit(db, String(subRow.user_id), 'RTDN_PROCESSED', 'subscription', String(subRow.id), {
      messageId,
      notificationType,
      resultingState: evalStatus.effectiveState,
      accessGranted: evalStatus.accessGranted,
      reconciliations
    });

    return reply.code(200).send({
      messageId,
      status: 'processed',
      idempotent: false,
      subscriptionId: subRow.id,
      state: evalStatus.effectiveState,
      accessGranted: evalStatus.accessGranted,
      paymentSemaphore: evalStatus.paymentSemaphore,
      reconciliations
    });
  });

  app.get('/v1/credentials', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const userRes = await db.query(
      `SELECT id, public_key, status FROM users WHERE id = $1`,
      [uid]
    );
    const user = userRes.rows[0];
    if (!user || user.status !== 'active') {
      return reply.code(403).send({ error: 'USER_INACTIVE' });
    }

    const devicePkBase64 =
      parseBytea(user.public_key)?.toString('base64') ??
      Buffer.from(`dev-pk-${uid}`, 'utf8').toString('base64');

    const now = new Date();
    const nowSec = Math.floor(now.getTime() / 1000);
    const credentials = [];

    // 1. Credenciales como líder en grupos activos donde su suscripción `leader` tenga acceso efectivo (R4.4)
    const leaderSubRes = await db.query(
      `SELECT state, current_period_end, grace_until, auto_renewing
       FROM subscriptions
       WHERE user_id = $1 AND kind = 'leader'
       ORDER BY current_period_end DESC LIMIT 1`,
      [uid]
    );
    const leaderSub = leaderSubRes.rows[0];
    if (leaderSub) {
      const evalLeader = evaluateSubscriptionState(
        {
          state: leaderSub.state as SubscriptionState,
          currentPeriodEnd: new Date(leaderSub.current_period_end),
          graceUntil: leaderSub.grace_until ? new Date(leaderSub.grace_until) : null,
          autoRenewing: Boolean(leaderSub.auto_renewing)
        },
        now
      );
      if (evalLeader.accessGranted && evalLeader.effectiveAccessUntil) {
        const ledGroups = await db.query(
          `SELECT g.id
           FROM group_leaders gl
           JOIN groups g ON g.id = gl.group_id
           WHERE gl.user_id = $1 AND g.status = 'active'`,
          [uid]
        );
        const accessUntilSec = Math.floor(evalLeader.effectiveAccessUntil.getTime() / 1000);
        for (const grp of ledGroups.rows) {
          const signed = issueOfflineCredential(
            {
              uid,
              gid: String(grp.id),
              kind: 'leader',
              devicePublicKeyBase64: devicePkBase64,
              effectiveAccessUntilSec: accessUntilSec,
              nowSec
            },
            credentialKeyring
          );
          credentials.push(signed);
        }
      }
    }

    // 2. Credenciales como miembro (full, prospect, guest) en grupos activos con pago/gracia vigente
    const memberGroupsRes = await db.query(
      `SELECT m.group_id, m.status_kind, m.paid_until,
              s.state AS sub_state, s.current_period_end, s.grace_until, s.auto_renewing
       FROM memberships m
       JOIN groups g ON g.id = m.group_id
       LEFT JOIN subscriptions s
         ON s.user_id = m.user_id AND s.kind = 'member_slot' AND s.assigned_group_id = m.group_id
       WHERE m.user_id = $1 AND m.invite_state = 'accepted' AND g.status = 'active'`,
      [uid]
    );

    for (const row of memberGroupsRes.rows) {
      let accessUntilSec: number | null = null;
      if (row.sub_state && row.current_period_end) {
        const evalSub = evaluateSubscriptionState(
          {
            state: row.sub_state as SubscriptionState,
            currentPeriodEnd: new Date(row.current_period_end),
            graceUntil: row.grace_until ? new Date(row.grace_until) : null,
            autoRenewing: Boolean(row.auto_renewing)
          },
          now
        );
        if (evalSub.accessGranted && evalSub.effectiveAccessUntil) {
          accessUntilSec = Math.floor(evalSub.effectiveAccessUntil.getTime() / 1000);
        }
      } else if (row.paid_until && new Date(row.paid_until).getTime() > now.getTime()) {
        accessUntilSec = Math.floor(new Date(row.paid_until).getTime() / 1000);
      }

      if (accessUntilSec && accessUntilSec > nowSec) {
        const signed = issueOfflineCredential(
          {
            uid,
            gid: String(row.group_id),
            kind: row.status_kind,
            devicePublicKeyBase64: devicePkBase64,
            effectiveAccessUntilSec: accessUntilSec,
            nowSec
          },
          credentialKeyring
        );
        credentials.push(signed);
      }
    }

    return {
      activeKid: credentialKeyring.getActiveKid(),
      publicKeys: credentialKeyring.getPublicKeyMap(),
      credentials
    };
  });

  app.get('/v1/revocations', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const query = req.query as { groupId?: string };
    const filterGroupId = query?.groupId;

    const revocations: RevocationEntry[] = [];
    const nowIso = new Date().toISOString();

    // 1. Suscripciones reembolsadas o sin acceso (`refunded`, `on_hold`, `expired`)
    const subRevRes = await db.query(
      `SELECT user_id, assigned_group_id, state, last_verified_at
       FROM subscriptions
       WHERE state IN ('refunded', 'on_hold', 'expired')
         AND ($1::uuid IS NULL OR assigned_group_id = $1::uuid OR kind = 'leader')`,
      [filterGroupId ?? null]
    );
    for (const r of subRevRes.rows) {
      revocations.push({
        userId: String(r.user_id),
        groupId: r.assigned_group_id ? String(r.assigned_group_id) : null,
        reason: r.state === 'refunded' ? 'refunded' : 'subscription_inactive',
        revokedAtIso: r.last_verified_at ? new Date(r.last_verified_at).toISOString() : nowIso
      });
    }

    // 2. Membresías retiradas o dadas de baja (`removed`, `left`)
    const memRevRes = await db.query(
      `SELECT user_id, group_id, invite_state
       FROM memberships
       WHERE invite_state IN ('removed', 'left')
         AND ($1::uuid IS NULL OR group_id = $1::uuid)`,
      [filterGroupId ?? null]
    );
    for (const r of memRevRes.rows) {
      revocations.push({
        userId: String(r.user_id),
        groupId: String(r.group_id),
        reason: r.invite_state === 'removed' ? 'membership_removed' : 'membership_left',
        revokedAtIso: nowIso
      });
    }

    const signedList = signRevocationList(
      revocations,
      Math.floor(Date.now() / 1000),
      credentialKeyring
    );

    return {
      ...signedList,
      publicKeys: credentialKeyring.getPublicKeyMap()
    };
  });

  // ---------------------------------------------------------------------------
  // 16. Fase 2 — Certificación, Jerarquía (RC/MG/Gang/MC) e Invitados por Rodada (D42-D46)
  // ---------------------------------------------------------------------------
  app.get('/v1/groups/:id/tier-eligibility', async (req, reply) => {
    const { id } = req.params as { id: string };
    try {
      const { evaluateTierEligibility } = await import('./modules/groups/tier.js');
      const result = await evaluateTierEligibility(id, db);
      return reply.send(result);
    } catch {
      return reply.code(404).send({ error: 'Grupo no encontrado' });
    }
  });

  app.get('/v1/founder-nucleus/:country', async (req, reply) => {
    const { country } = req.params as { country: string };
    const { evaluateFounderNucleus } = await import('./modules/groups/tier.js');
    const result = await evaluateFounderNucleus(country.toUpperCase(), db);
    return reply.send(result);
  });

  app.post('/v1/groups/:id/certifications', async (req, reply) => {
    const { id } = req.params as { id: string };
    const { certification_type, certified_by_group_id } = (req.body ?? {}) as {
      certification_type: 'mutual_founder' | 'standard_padrino' | 'platform_support';
      certified_by_group_id?: string;
    };

    if (!['mutual_founder', 'standard_padrino', 'platform_support'].includes(certification_type)) {
      return reply.code(400).send({ error: 'Tipo de certificación inválido' });
    }

    const { rows } = await db.query(
      `INSERT INTO group_certifications (group_id, certified_by_group_id, certification_type)
       VALUES ($1, $2, $3) RETURNING *`,
      [id, certified_by_group_id || null, certification_type]
    );
    await db.query(`UPDATE groups SET is_certified = true WHERE id = $1`, [id]);
    return reply.code(201).send(rows[0]);
  });

  app.get('/v1/groups/:id/certifications', async (req, reply) => {
    const { id } = req.params as { id: string };
    const { rows } = await db.query(
      `SELECT * FROM group_certifications WHERE group_id = $1 ORDER BY certified_at DESC`,
      [id]
    );
    return reply.send(rows);
  });

  app.get('/v1/padrinos/:country', async (req, reply) => {
    const { country } = req.params as { country: string };
    const { rows } = await db.query(
      `SELECT g.id, g.name, g.tier, g.created_at, g.legacy_since
       FROM groups g
       JOIN group_certifications gc ON gc.group_id = g.id AND gc.revoked_at IS NULL
       WHERE g.country_code = $1
         AND g.tier = 'MC'
         AND g.is_certified = true
         AND (g.legacy_since <= now() - interval '7 years' OR g.created_at <= now() - interval '7 years')
       ORDER BY g.legacy_since NULLS LAST, g.created_at ASC`,
      [country.toUpperCase()]
    );
    return reply.send(rows);
  });

  app.post('/v1/rides/:rideId/guest-invitations', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { rideId } = req.params as { rideId: string };
    const { userId, expiresInHours } = (req.body ?? {}) as {
      userId?: string;
      expiresInHours?: number;
    };
    if (!userId) {
      return reply.code(400).send({ error: 'USER_ID_REQUIRED' });
    }
    const hours = Math.max(1, Math.min(168, Number(expiresInHours ?? 168)));
    const { rows } = await db.query(
      `INSERT INTO ride_guest_invitations (id, ride_id, user_id, invited_by, expires_at)
       VALUES ($1, $2, $3, $4, now() + ($5 || ' hours')::interval)
       RETURNING *`,
      [randomUUID(), rideId, userId, uid, String(hours)]
    );
    return reply.code(201).send(rows[0]);
  });

  return app;
}

async function evaluateVoteOutcome(
  db: DbExecutor,
  voteId: string,
  groupId: string,
  proposal: string,
  targetId: string | null
): Promise<string> {
  const leadersRes = await db.query(
    `SELECT user_id FROM group_leaders WHERE group_id = $1`,
    [groupId]
  );
  // Elegibles: todos los líderes del grupo menos el afectado si él mismo es líder (R14.4)
  const eligibleLeaders = leadersRes.rows
    .map((r) => String(r.user_id))
    .filter((id) => id !== targetId);
  const needed = requiredLeaderVotesForMajority(eligibleLeaders.length);

  const ballotsRes = await db.query(
    `SELECT leader, approve FROM leader_vote_ballots WHERE vote_id = $1`,
    [voteId]
  );
  const yesCount = ballotsRes.rows.filter((b) => Boolean(b.approve)).length;
  const noCount = ballotsRes.rows.filter((b) => !Boolean(b.approve)).length;

  let nextState = 'open';
  if (yesCount >= needed) {
    nextState = 'approved';
    await db.query(`UPDATE leader_votes SET state = 'approved' WHERE id = $1`, [voteId]);
    if (proposal === 'remove_member' && targetId) {
      await db.query(
        `UPDATE memberships SET invite_state = 'removed' WHERE group_id = $1 AND user_id = $2`,
        [groupId, targetId]
      );
    } else if (proposal === 'remove_leader' && targetId) {
      await db.query(
        `DELETE FROM group_leaders WHERE group_id = $1 AND user_id = $2 AND is_principal = false`,
        [groupId, targetId]
      );
    }
  } else if (eligibleLeaders.length - noCount < needed) {
    nextState = 'rejected';
    await db.query(`UPDATE leader_votes SET state = 'rejected' WHERE id = $1`, [voteId]);
  }
  return nextState;
}
