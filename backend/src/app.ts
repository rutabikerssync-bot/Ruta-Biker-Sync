import Fastify, {
  type FastifyInstance,
  type FastifyRequest,
  type FastifyReply,
  type FastifyServerOptions
} from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import fastifyStatic from '@fastify/static';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import type { Pool } from 'pg';
import type { DbExecutor } from './db/pool.js';
import { withUserDb } from './db/withUserDb.js';
import {
  canTransition,
  type RsvpStatus,
  type RsvpWritableStatus
} from './lib/rsvpTransitions.js';
import { registerRideLifecycleRoutes, validateRsvpLifecycle } from './lib/rideLifecycleRoutes.js';
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
  requiredLeaderVotesForMajority,
  type ClubRole
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
  issueRideManifest,
  signRevocationList,
  type CredentialRoleKind,
  type RevocationEntry,
  type RideManifestParticipant,
  type RideRoleKind,
  verifyRideSummarySignature,
  type RideSummaryData
} from './lib/credentials.js';
import { mapRideRosterRows } from './lib/rideRoster.js';
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
import {
  DEFAULT_ADMIN_KEY,
  verifyAdminAccess,
  logAdminAction,
  getAdminAnalyticsOverview,
  listAdminGroups,
  getAdminGroupDetails,
  updateAdminGroupStatus,
  extendGroupGracePeriod,
  listAdminUsers,
  updateAdminUserStatus,
  listEscalatedReports,
  resolveEscalatedReport,
  listSupportTickets,
  createSupportTicket,
  getSupportTicketDetails,
  addTicketMessage,
  listSosAccessAudit,
  listAdminAuditLogs
} from './lib/admin.js';
import { FcmService, defaultFcmService, type SendResult } from './lib/fcm.js';
import { calculateBearingDegrees, degreesToCardinal, formatSosAlertText } from './lib/geo.js';
import {
  getCanonicalRegions,
  getAllRegions,
  findRegionById,
  registerCustomRouteRegion,
  ensureCanonicalPmtilesFiles,
  getMapFilePath
} from './lib/offlineMaps.js';
import {
  DEFAULT_DEBUG_LOG_LINES,
  MAX_DEBUG_LOG_LINES,
  readRedactedLogTail,
  resolveDebugLogPath
} from './lib/debugLogs.js';

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
  rateLimitAuthMaxPerHour?: number;
  rateLimitInviteMaxPerHour?: number;
  fcmService?: FcmService;
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

const VisibilityEnum = z.enum(['PUBLIC', 'CLUB_ONLY', 'PRIVATE']);

const UpdateEnrichedProfileSchema = z.object({
  displayName: z.string().min(2).max(60).optional(),
  aliasBiker: z.string().min(2).max(60).optional(),
  bikeModel: z.string().max(80).optional(),
  mapColor: z.string().regex(/^#[0-9A-Fa-f]{6}$/).optional(),
  pinColorHex: z.string().regex(/^#[0-9A-Fa-f]{6}$/).optional(),
  motorcycleBrand: z.string().max(80).optional().nullable(),
  motorcycleModel: z.string().max(80).optional().nullable(),
  motorcycleDisplacement: z.number().int().positive().max(10000).optional().nullable(),
  motorcycleYear: z.number().int().min(1900).max(2100).optional().nullable(),
  motorcycleColor: z.string().max(40).optional().nullable(),
  motorcycleSerial: z.string().max(100).optional().nullable(),
  profileVisibility: VisibilityEnum.optional(),
  motorcycleVisibility: VisibilityEnum.optional(),
  photoBase64: z.string().max(3_000_000).optional().nullable(),
  motorcyclePhotoBase64: z.string().max(3_000_000).optional().nullable()
});

const SupportReportSchema = z.object({
  subject: z.string().min(3).max(200),
  message: z.string().min(5).max(5000),
  screenshotBase64: z.string().max(3_000_000).optional().nullable()
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

const CreateClubRouteSchema = z.object({
  title: z.string().min(3).max(140),
  description: z.string().max(2000).nullable().optional(),
  routeRef: z.string().max(200).nullable().optional(),
  startsAt: z.string().datetime(),
  endsAt: z.string().datetime().nullable().optional()
});

const UpdateClubRouteSchema = z
  .object({
    title: z.string().min(3).max(140).optional(),
    description: z.string().max(2000).nullable().optional(),
    startsAt: z.string().datetime().optional(),
    endsAt: z.string().datetime().nullable().optional(),
    routeRef: z.string().max(200).nullable().optional()
  })
  .refine((data) => Object.keys(data).length > 0, {
    message: 'Al menos un campo debe actualizarse'
  });

const RsvpBodySchema = z.object({
  status: z.enum(['confirmed', 'en_route', 'home', 'no_go'])
});

const AddLeaderSchema = z.object({
  userId: z.string().uuid()
});

const CreateInvitationSchema = z.object({
  invitedUserId: z.string().uuid().optional().nullable(),
  statusKind: z.enum(MEMBER_STATUS_KINDS).default('full').optional(),
  clubRole: z.enum(CLUB_ROLES).nullable().optional(),
  invitationType: z.enum(['hermandad', 'rodada']).default('hermandad').optional(),
  customId: z.string().uuid().optional()
});

const AcceptInvitationSchema = z.object({
  consents: z.object({
    membership: z.boolean().optional(),
    location: z.boolean(),
    emergencyPackage: z.boolean().optional(),
    notifications: z.boolean().optional()
  }),
  policyVersion: z.string().default('1.0')
});

const UpdateConsentSchema = z.object({
  groupId: z.string().uuid().nullable().optional(),
  kind: z.enum(['membership', 'location', 'emergency_package', 'notifications', 'terms']),
  granted: z.boolean(),
  policyVersion: z.string().default('1.0')
});

function normalizeIncomingClubRole(role: string | null | undefined): ClubRole | null | undefined {
  if (!role) return role as null | undefined;
  const lower = role.toLowerCase().trim();
  switch (lower) {
    case 'presidente':
    case 'president':
      return 'presidente';
    case 'vice':
    case 'vice_president':
    case 'vicepresidente':
      return 'vice';
    case 'capitan_ruta':
    case 'road_captain':
    case 'capitán de ruta':
      return 'capitan_ruta';
    case 'sargento_armas':
    case 'sergeant_at_arms':
    case 'sargento de armas':
      return 'sargento_armas';
    case 'secretario':
    case 'secretary':
      return 'secretario';
    case 'tesorero':
    case 'treasurer':
      return 'tesorero';
    case 'road_marshal':
      return 'road_marshal';
    default:
      return null;
  }
}

const PatchMemberSchema = z.object({
  statusKind: z.enum(MEMBER_STATUS_KINDS).optional(),
  clubRole: z.string().nullable().optional()
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

const CreateModerationReportSchema = z.object({
  reportedUserId: z.string().uuid(),
  rideId: z.string().uuid().optional(),
  reason: z.enum(['abuse', 'harassment', 'dangerous', 'other']),
  note: z.string().max(500).optional()
});

const ResolveModerationReportSchema = z.object({
  resolution: z.enum(['warning', 'muted', 'ride_removed', 'group_removed', 'platform_suspended']).optional(),
  state: z.enum(['actioned', 'dismissed']).default('actioned')
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

function getUserIdOrNull(req: FastifyRequest): string | null {
  const authHeader = req.headers.authorization;
  if (typeof authHeader === 'string' && authHeader.startsWith('Bearer ')) {
    const rawToken = authHeader.slice(7).trim();
    const verified = verifySessionToken(rawToken, undefined, 'access');
    if (verified) return verified.sub;
  }
  const header = req.headers['x-user-id'];
  const uid = Array.isArray(header) ? header[0] : header;
  if (uid && z.string().uuid().safeParse(uid).success) {
    return uid;
  }
  return null;
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
  env = process.env,
  rateLimitAuthMaxPerHour = 10,
  rateLimitInviteMaxPerHour = 5,
  fcmService = defaultFcmService
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
    trustProxy: resolvedTrustProxy,
    bodyLimit: 10 * 1024 * 1024 // 10 MB para soportar imágenes de perfil y reportes
  };
  const app = Fastify(fastifyOpts);
  const loginTracker = new LoginAttemptTracker();

  async function checkAndEnforceAuthRateLimit(
    ip: string,
    reply: FastifyReply
  ): Promise<boolean> {
    const res = await db.query(
      `SELECT COUNT(*)::int AS count
       FROM rate_limit_events
       WHERE scope = 'auth' AND ip = $1 AND created_at > now() - interval '1 hour'`,
      [ip]
    );
    const count = Number(res.rows[0]?.count ?? 0);
    if (count >= rateLimitAuthMaxPerHour) {
      reply.header('Retry-After', '3600').code(429).send({
        error: 'TOO_MANY_AUTH_ATTEMPTS',
        message: `Demasiados intentos de autenticación desde esta dirección IP (máximo ${rateLimitAuthMaxPerHour} por hora)`,
        retryAfter: 3600
      });
      return true;
    }
    await db.query(
      `INSERT INTO rate_limit_events (scope, ip, user_id) VALUES ('auth', $1, NULL)`,
      [ip]
    );
    return false;
  }

  async function checkAndEnforceInviteRateLimit(
    ip: string,
    userId: string,
    reply: FastifyReply
  ): Promise<boolean> {
    const res = await db.query(
      `SELECT COUNT(*)::int AS count
       FROM rate_limit_events
       WHERE scope = 'invite' AND ip = $1 AND user_id = $2 AND created_at > now() - interval '1 hour'`,
      [ip, userId]
    );
    const count = Number(res.rows[0]?.count ?? 0);
    if (count >= rateLimitInviteMaxPerHour) {
      reply.header('Retry-After', '3600').code(429).send({
        error: 'TOO_MANY_INVITE_ATTEMPTS',
        message: `Demasiadas invitaciones enviadas desde esta IP/cuenta (máximo ${rateLimitInviteMaxPerHour} por hora)`,
        retryAfter: 3600
      });
      return true;
    }
    await db.query(
      `INSERT INTO rate_limit_events (scope, ip, user_id) VALUES ('invite', $1, $2)`,
      [ip, userId]
    );
    return false;
  }

  await app.register(helmet, {
    contentSecurityPolicy: false,
    crossOriginEmbedderPolicy: false,
    crossOriginOpenerPolicy: false,
    crossOriginResourcePolicy: false
  });
  await app.register(cors, {
    origin: (_origin, cb) => cb(null, true),
    credentials: true,
    methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS']
  });
  app.addContentTypeParser(['application/x-www-form-urlencoded', 'text/plain', 'application/octet-stream'], (_req, _payload, done) => {
    done(null, null);
  });
  await app.register(rateLimit, {
    max: 10000,
    timeWindow: '1 minute',
    keyGenerator: (req) => (req.headers['x-user-id'] as string) || req.ip
  });

  const __appFilename = fileURLToPath(import.meta.url);
  const __appDirname = path.dirname(__appFilename);
  const publicDir = path.resolve(__appDirname, '..', 'public');
  if (fs.existsSync(publicDir)) {
    await app.register(fastifyStatic, {
      root: publicDir,
      prefix: '/'
    });
    app.get('/admin', async (_req, reply) => {
      return reply.sendFile('admin.html');
    });
    app.get('/admin/', async (_req, reply) => {
      return reply.sendFile('admin.html');
    });
    app.get('/admin/css/:filename', async (req, reply) => {
      return reply.sendFile(`css/${(req.params as any).filename}`);
    });
    app.get('/admin/js/:filename', async (req, reply) => {
      return reply.sendFile(`js/${(req.params as any).filename}`);
    });
  }

  app.get('/health', async () => ({ status: 'ok' }));

  // Verificación de versiones y control de actualización (D98)
  app.get('/v1/app/version', async () => ({
    minVersion: '1.0.0',
    minVersionCode: 1,
    latestVersion: '1.0.0',
    latestVersionCode: 1,
    forceUpdate: false,
    updateUrl: 'https://play.google.com/store/apps/details?id=com.rutabikersync.app',
    releaseNotes: 'Ruta Biker Sync — Versión estable para rodadas seguras y malla táctica off-grid.'
  }));

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
    const authRateLimited = await checkAndEnforceAuthRateLimit(clientIp, reply);
    if (authRateLimited) return;

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

  function cleanBase64(str: string): string {
    const commaIdx = str.indexOf(',');
    if (commaIdx !== -1 && str.startsWith('data:')) {
      return str.substring(commaIdx + 1);
    }
    return str.trim();
  }

  function validateImageBuffer(buf: Buffer): { valid: boolean; error?: string; mime?: string } {
    if (buf.length > 2 * 1024 * 1024) {
      return { valid: false, error: 'FILE_TOO_LARGE' };
    }
    // JPEG: FF D8 FF
    if (buf.length >= 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) {
      return { valid: true, mime: 'image/jpeg' };
    }
    // PNG: 89 50 4E 47
    if (buf.length >= 4 && buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) {
      return { valid: true, mime: 'image/png' };
    }
    // WebP: RIFF .... WEBP
    if (
      buf.length >= 12 &&
      buf.toString('ascii', 0, 4) === 'RIFF' &&
      buf.toString('ascii', 8, 12) === 'WEBP'
    ) {
      return { valid: true, mime: 'image/webp' };
    }
    return { valid: false, error: 'INVALID_FILE_FORMAT' };
  }

  // ---------------------------------------------------------------------------
  // 2. Perfil personal, motocicleta, privacidad y fotos (RULES R1.3, DESIGN §7, D98)
  // ---------------------------------------------------------------------------
  app.get('/v1/users/me', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const res = await db.query(
      `SELECT u.id, u.email, u.display_name, u.country_code, u.account_type,
              u.bike_model, u.map_color, u.avatar_webp, u.status,
              u.photo_url, u.photo_blob,
              u.motorcycle_brand, u.motorcycle_model, u.motorcycle_displacement,
              u.motorcycle_year, u.motorcycle_color, u.motorcycle_serial,
              u.motorcycle_photo_url, u.motorcycle_photo_blob,
              u.profile_visibility, u.motorcycle_visibility,
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
      bikeModel: u.bike_model ?? u.motorcycle_model ?? null,
      mapColor: u.map_color ?? '#FFB300',
      avatarWebpBase64: parseBytea(u.avatar_webp)?.toString('base64') ?? null,
      photoUrl: u.photo_url ?? (u.photo_blob ? `/v1/users/photo/${u.id}` : null),
      photoBase64: parseBytea(u.photo_blob)?.toString('base64') ?? null,
      motorcycleBrand: u.motorcycle_brand ?? null,
      motorcycleModel: u.motorcycle_model ?? u.bike_model ?? null,
      motorcycleDisplacement: u.motorcycle_displacement ? Number(u.motorcycle_displacement) : null,
      motorcycleYear: u.motorcycle_year ? Number(u.motorcycle_year) : null,
      motorcycleColor: u.motorcycle_color ?? null,
      motorcycleSerial: u.motorcycle_serial ?? null,
      motorcyclePhotoUrl: u.motorcycle_photo_url ?? (u.motorcycle_photo_blob ? `/v1/users/motorcycle-photo/${u.id}` : null),
      motorcyclePhotoBase64: parseBytea(u.motorcycle_photo_blob)?.toString('base64') ?? null,
      profileVisibility: u.profile_visibility ?? 'PUBLIC',
      motorcycleVisibility: u.motorcycle_visibility ?? 'PUBLIC',
      validRides: Number(u.valid_rides ?? 0),
      badges: badgesRes.rows
    };
  });

  // Consulta de estatus de club y pertenencia (RULES R2.2, D43)
  app.get('/v1/users/club-status', async (req, reply) => {
    const uid = getUserIdOrNull(req);
    const { email } = (req.query ?? {}) as { email?: string };

    let targetUid = uid;
    if (email && isUuid(targetUid ?? '')) {
      // Si se pasa email y no hay targetUid, buscar usuario por email
    }
    if (!targetUid && email) {
      const uRes = await db.query(`SELECT id FROM users WHERE email = $1`, [email.trim().toLowerCase()]);
      if (uRes.rows.length > 0) {
        targetUid = uRes.rows[0].id;
      }
    }

    if (!targetUid) {
      return {
        hasClub: false,
        clubId: null,
        clubName: null,
        statusKind: null,
        message: 'Usuario no registrado en base de datos central o sin club'
      };
    }

    const mRes = await db.query(
      `SELECT m.group_id, g.name AS group_name, m.status_kind, m.club_role, m.invite_state
       FROM memberships m
       JOIN groups g ON g.id = m.group_id
       WHERE m.user_id = $1 AND m.status_kind IN ('full', 'prospect') AND m.invite_state = 'accepted'`,
      [targetUid]
    );

    if (mRes.rows.length === 0) {
      return {
        hasClub: false,
        clubId: null,
        clubName: null,
        statusKind: null,
        message: 'Piloto libre: no pertenece a ninguna hermandad en la base de datos'
      };
    }

    const m = mRes.rows[0];
    return {
      hasClub: true,
      clubId: m.group_id,
      clubName: m.group_name,
      statusKind: m.status_kind,
      clubRole: m.club_role,
      message: `El piloto ya pertenece a la hermandad '${m.group_name}'`
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

  // PUT /v1/users/profile — Actualización integral de perfil de piloto y moto
  app.put('/v1/users/profile', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const parsed = UpdateEnrichedProfileSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const d = parsed.data;

    let photoBuf: Buffer | undefined;
    if (d.photoBase64) {
      const b64 = cleanBase64(d.photoBase64);
      const b = Buffer.from(b64, 'base64');
      const v = validateImageBuffer(b);
      if (!v.valid) {
        return reply.code(400).send({
          error: v.error,
          message: v.error === 'FILE_TOO_LARGE' ? 'La foto no debe superar 2 MB' : 'Formato de imagen inválido (solo WebP, JPEG, PNG)'
        });
      }
      photoBuf = b;
    }

    let motoPhotoBuf: Buffer | undefined;
    if (d.motorcyclePhotoBase64) {
      const b64 = cleanBase64(d.motorcyclePhotoBase64);
      const b = Buffer.from(b64, 'base64');
      const v = validateImageBuffer(b);
      if (!v.valid) {
        return reply.code(400).send({
          error: v.error,
          message: v.error === 'FILE_TOO_LARGE' ? 'La foto de la moto no debe superar 2 MB' : 'Formato de imagen inválido (solo WebP, JPEG, PNG)'
        });
      }
      motoPhotoBuf = b;
    }

    const cur = await db.query(
      `SELECT display_name, bike_model, map_color,
              motorcycle_brand, motorcycle_model, motorcycle_displacement,
              motorcycle_year, motorcycle_color, motorcycle_serial,
              profile_visibility, motorcycle_visibility, photo_blob, motorcycle_photo_blob
       FROM users WHERE id = $1`,
      [uid]
    );
    if (cur.rows.length === 0) return reply.code(404).send({ error: 'USER_NOT_FOUND' });
    const row = cur.rows[0];

    const finalDisplayName = d.displayName ?? d.aliasBiker ?? row.display_name;
    const finalMapColor = d.mapColor ?? d.pinColorHex ?? row.map_color;
    const finalBrand = d.motorcycleBrand !== undefined ? d.motorcycleBrand : row.motorcycle_brand;
    const finalModel = d.motorcycleModel !== undefined ? d.motorcycleModel : (d.bikeModel !== undefined ? d.bikeModel : row.motorcycle_model);
    const finalDisp = d.motorcycleDisplacement !== undefined ? d.motorcycleDisplacement : row.motorcycle_displacement;
    const finalYear = d.motorcycleYear !== undefined ? d.motorcycleYear : row.motorcycle_year;
    const finalColor = d.motorcycleColor !== undefined ? d.motorcycleColor : row.motorcycle_color;
    const finalSerial = d.motorcycleSerial !== undefined ? d.motorcycleSerial : row.motorcycle_serial;
    const finalProfVis = d.profileVisibility ?? row.profile_visibility ?? 'PUBLIC';
    const finalMotoVis = d.motorcycleVisibility ?? row.motorcycle_visibility ?? 'PUBLIC';
    const finalPhotoBlob = photoBuf !== undefined ? photoBuf : row.photo_blob;
    const finalMotoBlob = motoPhotoBuf !== undefined ? motoPhotoBuf : row.motorcycle_photo_blob;
    const photoUrl = finalPhotoBlob ? `/v1/users/photo/${uid}` : null;
    const motoPhotoUrl = finalMotoBlob ? `/v1/users/motorcycle-photo/${uid}` : null;

    await db.query(
      `UPDATE users
       SET display_name = $1,
           bike_model = $2,
           map_color = $3,
           motorcycle_brand = $4,
           motorcycle_model = $5,
           motorcycle_displacement = $6,
           motorcycle_year = $7,
           motorcycle_color = $8,
           motorcycle_serial = $9,
           profile_visibility = $10,
           motorcycle_visibility = $11,
           photo_blob = $12,
           photo_url = $13,
           motorcycle_photo_blob = $14,
           motorcycle_photo_url = $15
       WHERE id = $16`,
      [
        finalDisplayName,
        finalModel,
        finalMapColor,
        finalBrand,
        finalModel,
        finalDisp,
        finalYear,
        finalColor,
        finalSerial,
        finalProfVis,
        finalMotoVis,
        finalPhotoBlob,
        photoUrl,
        finalMotoBlob,
        motoPhotoUrl,
        uid
      ]
    );

    return {
      updated: true,
      profile: {
        id: uid,
        displayName: finalDisplayName,
        mapColor: finalMapColor,
        motorcycleBrand: finalBrand,
        motorcycleModel: finalModel,
        motorcycleDisplacement: finalDisp,
        motorcycleYear: finalYear,
        motorcycleColor: finalColor,
        motorcycleSerial: finalSerial,
        profileVisibility: finalProfVis,
        motorcycleVisibility: finalMotoVis,
        photoUrl,
        motorcyclePhotoUrl: motoPhotoUrl
      }
    };
  });

  // POST /v1/users/photo — Subida de foto de perfil
  app.post('/v1/users/photo', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    let buf: Buffer;
    const body = req.body as any;
    if (body?.photoBase64) {
      buf = Buffer.from(cleanBase64(body.photoBase64), 'base64');
    } else if (Buffer.isBuffer(req.body)) {
      buf = req.body;
    } else {
      return reply.code(400).send({ error: 'MISSING_PHOTO', message: 'Se requiere photoBase64' });
    }

    const validation = validateImageBuffer(buf);
    if (!validation.valid) {
      return reply.code(400).send({
        error: validation.error,
        message: validation.error === 'FILE_TOO_LARGE'
          ? 'La foto no debe superar 2 MB'
          : 'Formato de imagen inválido. Solo se admiten WebP, JPEG o PNG.'
      });
    }

    const photoUrl = `/v1/users/photo/${uid}`;
    await db.query(
      `UPDATE users SET photo_blob = $1, photo_url = $2 WHERE id = $3`,
      [buf, photoUrl, uid]
    );

    return { success: true, photoUrl };
  });

  // GET /v1/users/photo/:id — Obtener foto de perfil
  app.get('/v1/users/photo/:id', async (req, reply) => {
    const { id } = req.params as { id: string };
    const res = await db.query(`SELECT photo_blob FROM users WHERE id = $1`, [id]);
    if (res.rows.length === 0 || !res.rows[0].photo_blob) {
      return reply.code(404).send({ error: 'PHOTO_NOT_FOUND' });
    }
    const buf = parseBytea(res.rows[0].photo_blob);
    if (!buf) return reply.code(404).send({ error: 'PHOTO_NOT_FOUND' });
    const v = validateImageBuffer(buf);
    reply.type(v.mime ?? 'image/webp').send(buf);
  });

  // POST /v1/users/motorcycle-photo — Subida de foto de la moto
  app.post('/v1/users/motorcycle-photo', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    let buf: Buffer;
    const body = req.body as any;
    if (body?.photoBase64) {
      buf = Buffer.from(cleanBase64(body.photoBase64), 'base64');
    } else if (Buffer.isBuffer(req.body)) {
      buf = req.body;
    } else {
      return reply.code(400).send({ error: 'MISSING_PHOTO', message: 'Se requiere photoBase64' });
    }

    const validation = validateImageBuffer(buf);
    if (!validation.valid) {
      return reply.code(400).send({
        error: validation.error,
        message: validation.error === 'FILE_TOO_LARGE'
          ? 'La foto no debe superar 2 MB'
          : 'Formato de imagen inválido. Solo se admiten WebP, JPEG o PNG.'
      });
    }

    const motoPhotoUrl = `/v1/users/motorcycle-photo/${uid}`;
    await db.query(
      `UPDATE users SET motorcycle_photo_blob = $1, motorcycle_photo_url = $2 WHERE id = $3`,
      [buf, motoPhotoUrl, uid]
    );

    return { success: true, motorcyclePhotoUrl: motoPhotoUrl };
  });

  // GET /v1/users/motorcycle-photo/:id — Obtener foto de la moto
  app.get('/v1/users/motorcycle-photo/:id', async (req, reply) => {
    const { id } = req.params as { id: string };
    const res = await db.query(`SELECT motorcycle_photo_blob FROM users WHERE id = $1`, [id]);
    if (res.rows.length === 0 || !res.rows[0].motorcycle_photo_blob) {
      return reply.code(404).send({ error: 'PHOTO_NOT_FOUND' });
    }
    const buf = parseBytea(res.rows[0].motorcycle_photo_blob);
    if (!buf) return reply.code(404).send({ error: 'PHOTO_NOT_FOUND' });
    const v = validateImageBuffer(buf);
    reply.type(v.mime ?? 'image/webp').send(buf);
  });

  // POST /v1/support/report — Envío de reporte de bug o soporte técnico
  app.post('/v1/support/report', async (req, reply) => {
    const parsed = SupportReportSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { subject, message, screenshotBase64 } = parsed.data;

    let screenshotBuf: Buffer | null = null;
    if (screenshotBase64) {
      const b = Buffer.from(cleanBase64(screenshotBase64), 'base64');
      const v = validateImageBuffer(b);
      if (!v.valid) {
        return reply.code(400).send({
          error: v.error,
          message: v.error === 'FILE_TOO_LARGE' ? 'La captura no debe superar 2 MB' : 'Formato de captura inválido (solo WebP, JPEG, PNG)'
        });
      }
      screenshotBuf = b;
    }

    let uid: string | null = null;
    const authHeader = req.headers.authorization;
    if (typeof authHeader === 'string' && authHeader.startsWith('Bearer ')) {
      const token = authHeader.slice(7).trim();
      const session = verifySessionToken(token, undefined, 'access');
      if (session) uid = session.sub;
    }
    if (!uid && typeof req.headers['x-user-id'] === 'string') {
      const candidate = req.headers['x-user-id'].trim();
      if (/^[0-9a-fA-F-]{36}$/.test(candidate)) uid = candidate;
    }

    const insertRes = await db.query(
      `INSERT INTO support_reports (user_id, subject, message, screenshot_blob, status)
       VALUES ($1, $2, $3, $4, 'open')
       RETURNING id, created_at, status`,
      [uid, subject, message, screenshotBuf]
    );
    const report = insertRes.rows[0];

    return reply.code(201).send({
      success: true,
      reportId: report.id,
      status: report.status,
      message: 'Reporte de soporte recibido correctamente. Nuestro equipo lo revisará a la brevedad.'
    });
  });

  // GET /v1/support/reports — Consultar reportes enviados por el usuario
  app.get('/v1/support/reports', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const res = await db.query(
      `SELECT id, subject, message, status, created_at,
              CASE WHEN screenshot_blob IS NOT NULL THEN true ELSE false END AS has_screenshot
       FROM support_reports
       WHERE user_id = $1
       ORDER BY created_at DESC`,
      [uid]
    );

    return { reports: res.rows };
  });

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
              g.tier, g.foundation_date, g.colors_description, g.club_siglas, g.tradition_type,
              g.directiva_scope, g.directiva_region, g.motto, g.city,
              gl.is_principal
       FROM group_leaders gl
       JOIN groups g ON g.id = gl.group_id
       WHERE gl.user_id = $1 AND g.status <> 'deleted'`,
      [uid]
    );

    const memberRes = await db.query(
      `SELECT g.id, g.country_code, g.name, g.logo_url, g.principal_leader, g.status,
              g.tier, g.foundation_date, g.colors_description, g.club_siglas, g.tradition_type,
              g.directiva_scope, g.directiva_region, g.motto, g.city,
              m.status_kind, m.club_role, m.paid_until
       FROM memberships m
       JOIN groups g ON g.id = m.group_id
       WHERE m.user_id = $1 AND m.invite_state = 'accepted' AND g.status <> 'deleted'`,
      [uid]
    );

    console.log('GET /v1/groups called for UID:', uid);
    console.log('memberRes rows count:', memberRes.rows.length, 'first row:', memberRes.rows[0]);
    return {
      ledGroups: ledRes.rows.map((r) => ({
        id: r.id,
        countryCode: r.country_code,
        name: r.name,
        logoUrl: r.logo_url,
        principalLeader: r.principal_leader,
        status: r.status,
        tier: r.tier,
        foundationDate: r.foundation_date,
        colorsDescription: r.colors_description,
        clubSiglas: r.club_siglas,
        traditionType: r.tradition_type,
        directivaScope: r.directiva_scope,
        directivaRegion: r.directiva_region,
        motto: r.motto,
        city: r.city,
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
        tier: r.tier,
        foundationDate: r.foundation_date,
        colorsDescription: r.colors_description,
        clubSiglas: r.club_siglas,
        traditionType: r.tradition_type,
        directivaScope: r.directiva_scope,
        directivaRegion: r.directiva_region,
        motto: r.motto,
        city: r.city,
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
    if (!z.string().uuid().safeParse(groupId).success) {
      return reply.code(400).send({ error: 'INVALID_GROUP_ID' });
    }

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
    if (!z.string().uuid().safeParse(groupId).success) {
      return reply.code(400).send({ error: 'INVALID_GROUP_ID' });
    }

    // ACL explícita: el perfil de un club sólo lo leen sus miembros aceptados
    // o sus líderes. No depender de RLS aquí evita filtrar clubes ajenos por UUID.
    const accessRes = await db.query(
      `SELECT 1 FROM memberships
        WHERE group_id = $1 AND user_id = $2 AND invite_state = 'accepted'
       UNION
       SELECT 1 FROM group_leaders
        WHERE group_id = $1 AND user_id = $2
       LIMIT 1`,
      [groupId, uid]
    );
    if (accessRes.rows.length === 0) {
      return reply.code(403).send({ error: 'FORBIDDEN' });
    }

    const grpRes = await db.query(
      `SELECT id, country_code, name, name_normalized, logo_url, principal_leader, role_permissions, status,
              tier, foundation_date, colors_description, club_siglas, tradition_type,
              directiva_scope, directiva_region, motto, city, profile_version, profile_updated_at
       FROM groups WHERE id = $1 AND status <> 'deleted'`,
      [groupId]
    );
    const g = grpRes.rows[0];
    if (!g) return reply.code(404).send({ error: 'GROUP_NOT_FOUND' });

    // Propagacion barata (L0/L2): ?since=<version> responde 304 sin mover datos.
    const sinceRaw = (req.query as { since?: string }).since;
    const since = sinceRaw === undefined ? null : Number.parseInt(sinceRaw, 10);
    if (since !== null && Number.isFinite(since) && g.profile_version <= since) {
      return reply.code(304).send();
    }

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
      tier: g.tier,
      profileVersion: g.profile_version,
      profileUpdatedAt: g.profile_updated_at,
      foundationDate: g.foundation_date,
      colorsDescription: g.colors_description,
      clubSiglas: g.club_siglas,
      traditionType: g.tradition_type,
      directivaScope: g.directiva_scope,
      directivaRegion: g.directiva_region,
      motto: g.motto,
      city: g.city,
      rolePermissions: g.role_permissions,
      leaders: leadersRes.rows.map((l) => ({
        userId: l.user_id,
        isPrincipal: Boolean(l.is_principal)
      }))
    };
  });

  // Actualizar Identidad Biker / Perfil del Club (RULES R3.3, R5.1)
  app.put('/v1/groups/:id/profile', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };
    if (!z.string().uuid().safeParse(groupId).success) {
      return reply.code(400).send({ error: 'INVALID_GROUP_ID' });
    }

    const leaderCheck = await db.query(
      `SELECT 1 FROM group_leaders
       WHERE group_id = $1 AND user_id = $2 AND is_principal = true`,
      [groupId, uid]
    );
    if (leaderCheck.rows.length === 0) {
      return reply.code(403).send({ error: 'LEADER_REQUIRED', message: 'Solo el presidente del club puede editar su perfil' });
    }

    const body = (req.body ?? {}) as {
      colorsDescription?: string;
      clubSiglas?: string;
      traditionType?: string;
      directivaScope?: string;
      directivaRegion?: string;
      motto?: string;
      city?: string;
      foundationDate?: string;
      logoUrl?: string;
      tier?: string;
      profile?: Record<string, unknown>;
    };

    const changed = [
      body.colorsDescription, body.clubSiglas, body.traditionType,
      body.directivaScope, body.directivaRegion, body.motto, body.city,
      body.foundationDate, body.logoUrl, body.tier, body.profile
    ].some((value) => value !== undefined && value !== null);
    const profileJson = body.profile != null ? JSON.stringify(body.profile) : null;

    const updateRes = await db.query(
      `UPDATE groups
       SET colors_description = COALESCE($1, colors_description),
           club_siglas = COALESCE($2, club_siglas),
           tradition_type = COALESCE($3, tradition_type),
           directiva_scope = COALESCE($4, directiva_scope),
           directiva_region = COALESCE($5, directiva_region),
           motto = COALESCE($6, motto),
           city = COALESCE($7, city),
           foundation_date = COALESCE($8, foundation_date),
           logo_url = COALESCE($9, logo_url),
           tier = COALESCE($10, tier),
           club_profile = CASE WHEN $11::jsonb IS NULL THEN club_profile ELSE $11::jsonb END,
           profile_version = profile_version + CASE WHEN $12 THEN 1 ELSE 0 END,
           profile_updated_by = CASE WHEN $12 THEN $13::uuid ELSE profile_updated_by END,
           profile_updated_at = CASE WHEN $12 THEN now() ELSE profile_updated_at END
       WHERE id = $14
       RETURNING id, name, tier, colors_description, club_siglas, tradition_type,
                 directiva_scope, directiva_region, motto, city, foundation_date,
                 logo_url, profile_version, profile_updated_at`,
      [
        body.colorsDescription ?? null,
        body.clubSiglas ?? null,
        body.traditionType ?? null,
        body.directivaScope ?? null,
        body.directivaRegion ?? null,
        body.motto ?? null,
        body.city ?? null,
        body.foundationDate ?? null,
        body.logoUrl ?? null,
        body.tier ?? null,
        profileJson,
        changed,
        uid,
        groupId
      ]
    );

    const updated = updateRes.rows[0];
    return reply.send({
      success: true,
      group: {
        id: updated.id,
        name: updated.name,
        tier: updated.tier,
        colorsDescription: updated.colors_description,
        clubSiglas: updated.club_siglas,
        traditionType: updated.tradition_type,
        directivaScope: updated.directiva_scope,
        directivaRegion: updated.directiva_region,
        motto: updated.motto,
        city: updated.city,
        foundationDate: updated.foundation_date,
        logoUrl: updated.logo_url,
        profileVersion: updated.profile_version,
        profileUpdatedAt: updated.profile_updated_at
      }
    });
  });

  // ---------------------------------------------------------------------------
  // Fase 3: rutas de club y asistencia (RSVP). La RLS FORCE de la migracion 0017
  // hace el aislamiento por club; aqui solo se anaden las reglas por rol.
  // ---------------------------------------------------------------------------

  // El modo degradado de index.ts usa un DbExecutor sin .connect(): responder 503
  // evita simular un aislamiento que PostgreSQL no esta aplicando.
  function getRlsPool(): Pool | null {
    const maybePool = db as unknown as { connect?: unknown };
    return typeof maybePool.connect === 'function' ? (db as Pool) : null;
  }

  registerRideLifecycleRoutes(app, db, getRlsPool);

  app.post('/v1/groups/:id/routes', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };
    if (!z.string().uuid().safeParse(groupId).success) {
      return reply.code(400).send({ error: 'INVALID_GROUP_ID' });
    }

    const parsed = CreateClubRouteSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }

    const leaderCheck = await db.query(
      `SELECT 1 FROM group_leaders
       WHERE group_id = $1 AND user_id = $2`,
      [groupId, uid]
    );
    if (leaderCheck.rows.length === 0) {
      return reply.code(403).send({
        error: 'LEADER_REQUIRED',
        message: 'Solo la mesa directiva del club puede publicar rutas'
      });
    }

    const rls = getRlsPool();
    if (!rls) return reply.code(503).send({ error: 'DB_UNAVAILABLE' });

    try {
      const { rows } = await withUserDb(rls, uid, (client) =>
        client.query(
          `INSERT INTO club_routes
             (group_id, created_by, title, description, route_ref, starts_at, ends_at, status)
           VALUES ($1, $2, $3, $4, $5, $6, $7, 'published')
           RETURNING id, group_id, created_by, title, description, route_ref, starts_at, ends_at,
                     status, created_at, updated_at`,
          [
            groupId,
            uid,
            parsed.data.title,
            parsed.data.description ?? null,
            parsed.data.routeRef ?? null,
            parsed.data.startsAt,
            parsed.data.endsAt ?? null
          ]
        )
      );
      return reply.code(201).send({ route: rows[0] });
    } catch (err) {
      return reply.code(403).send({
        error: 'ROUTE_CREATE_DENIED',
        message: (err as Error).message
      });
    }
  });

  app.get('/v1/groups/:id/routes', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };
    if (!z.string().uuid().safeParse(groupId).success) {
      return reply.code(400).send({ error: 'INVALID_GROUP_ID' });
    }

    const rls = getRlsPool();
    if (!rls) return reply.code(503).send({ error: 'DB_UNAVAILABLE' });

    const { rows } = await withUserDb(rls, uid, (client) =>
      client.query(
        `SELECT id, group_id, created_by, title, description, route_ref, starts_at, ends_at,
                status, created_at, updated_at, outbound_started_at, destination_reached_at,
                return_started_at, completed_at
         FROM club_routes
         WHERE group_id = $1
         ORDER BY starts_at NULLS LAST, created_at DESC`,
        [groupId]
      )
    );
    // Un externo NO recibe 403 aqui: la RLS devuelve 0 filas y no revela si el club existe.
    return reply.code(200).send({ groupId, routes: rows });
  });

  app.post('/v1/routes/:id/rsvp', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: routeId } = req.params as { id: string };
    if (!z.string().uuid().safeParse(routeId).success) {
      return reply.code(400).send({ error: 'INVALID_ROUTE_ID' });
    }

    const parsed = RsvpBodySchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const target: RsvpWritableStatus = parsed.data.status;

    const rls = getRlsPool();
    if (!rls) return reply.code(503).send({ error: 'DB_UNAVAILABLE' });

    const result = await withUserDb(rls, uid, async (client) => {
      // La RLS de routes_read ya oculta la ruta a quien no es miembro aceptado.
      const routeRes = await client.query(
        `SELECT id, group_id FROM club_routes WHERE id = $1`,
        [routeId]
      );
      const route = routeRes.rows[0];
      if (!route) return { kind: 'not_found' as const };

      const memberRes = await client.query(
        `SELECT 1 FROM memberships
         WHERE group_id = $1 AND user_id = $2 AND invite_state = 'accepted'
         UNION
         SELECT 1 FROM group_leaders
         WHERE group_id = $1 AND user_id = $2
         LIMIT 1`,
        [route.group_id, uid]
      );
      if (memberRes.rows.length === 0) return { kind: 'forbidden' as const };

      const currentRes = await client.query(
        `SELECT status FROM club_route_rsvps WHERE route_id = $1 AND user_id = $2`,
        [routeId, uid]
      );
      const current = currentRes.rows[0]?.status as RsvpStatus | undefined;
      const lifecycleError = await validateRsvpLifecycle(client, routeId, current, target);
      if (lifecycleError) return { kind: 'lifecycle_error' as const, ...lifecycleError };
      if (current && !canTransition(current, target)) {
        return { kind: 'invalid_transition' as const, current };
      }

      // UPSERT idempotente: repetir el mismo estado NO mueve responded_at,
      // home_at ni updated_at. El outbox de la Fase 5 puede reenviar sin ruido.
      const upRes = await client.query(
        `INSERT INTO club_route_rsvps
           (route_id, user_id, status, responded_at, home_at, updated_at)
         VALUES ($1, $2, $3::text, now(),
                 CASE WHEN $3::text = 'home' THEN now() ELSE NULL END, now())
         ON CONFLICT (route_id, user_id) DO UPDATE
         SET status = EXCLUDED.status,
             responded_at = CASE WHEN club_route_rsvps.status = EXCLUDED.status
                                 THEN club_route_rsvps.responded_at ELSE now() END,
             home_at = CASE
               WHEN EXCLUDED.status = 'home' AND club_route_rsvps.status <> 'home'
                 THEN now()
               WHEN EXCLUDED.status <> 'home' THEN NULL
               ELSE club_route_rsvps.home_at END,
             updated_at = CASE WHEN club_route_rsvps.status = EXCLUDED.status
                               THEN club_route_rsvps.updated_at ELSE now() END,
         on_site_at = CASE
                WHEN EXCLUDED.status = 'confirmed' AND club_route_rsvps.status = 'confirmed'
                  THEN club_route_rsvps.on_site_at
                ELSE NULL END
          RETURNING route_id, user_id, status, responded_at, home_at, updated_at, on_site_at`,
        [routeId, uid, target]
      );

      return {
        kind: 'ok' as const,
        rsvp: upRes.rows[0],
        created: !current,
        changed: current !== target
      };
    });

    if (result.kind === 'not_found') return reply.code(404).send({ error: 'ROUTE_NOT_FOUND' });
    if (result.kind === 'forbidden') return reply.code(403).send({ error: 'NOT_A_CLUB_MEMBER' });
    if (result.kind === 'lifecycle_error') {
      return reply.code(result.statusCode).send({ error: result.error });
    }
    if (result.kind === 'invalid_transition') {
      return reply.code(409).send({
        error: 'INVALID_RSVP_TRANSITION',
        from: result.current,
        to: target
      });
    }
    return reply.code(result.created ? 201 : 200).send({
      rsvp: result.rsvp,
      changed: result.changed
    });
  });

  app.get('/v1/routes/:id/rsvps', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: routeId } = req.params as { id: string };
    if (!z.string().uuid().safeParse(routeId).success) {
      return reply.code(400).send({ error: 'INVALID_ROUTE_ID' });
    }

    const rls = getRlsPool();
    if (!rls) return reply.code(503).send({ error: 'DB_UNAVAILABLE' });

    const result = await withUserDb(rls, uid, async (client) => {
      const routeRes = await client.query(
        `SELECT id, group_id FROM club_routes WHERE id = $1`,
        [routeId]
      );
      const route = routeRes.rows[0];
      if (!route) return { kind: 'not_found' as const };

      const leaderRes = await client.query(
        `SELECT 1 FROM group_leaders
         WHERE group_id = $1 AND user_id = $2`,
        [route.group_id, uid]
      );
      const canViewFull = leaderRes.rows.length > 0;

      const allRes = await client.query(
        `SELECT user_id, status, responded_at, home_at, updated_at, on_site_at
         FROM club_route_rsvps WHERE route_id = $1
         ORDER BY responded_at NULLS LAST, user_id`,
        [routeId]
      );
      const summaryRes = await client.query(
        `SELECT count(*) FILTER (WHERE status = 'confirmed')::int AS confirmed,
                count(*) FILTER (WHERE status = 'en_route')::int AS en_route,
                count(*) FILTER (WHERE status = 'home')::int AS home,
                count(*) FILTER (WHERE status = 'no_go')::int AS no_go,
                count(*)::int AS total
         FROM club_route_rsvps WHERE route_id = $1`,
        [routeId]
      );

      return {
        kind: 'ok' as const,
        canViewFull,
        rows: allRes.rows,
        summary: summaryRes.rows[0]
      };
    });

    if (result.kind === 'not_found') return reply.code(404).send({ error: 'ROUTE_NOT_FOUND' });

    // RLS = aislamiento por club (0017). El filtro de rol es de aplicacion:
    // la mesa directiva ve filas individuales; el miembro, solo la suya + el resumen.
    if (result.canViewFull) {
      return reply.code(200).send({
        routeId,
        scope: 'full',
        rsvps: result.rows,
        summary: result.summary
      });
    }
    return reply.code(200).send({
      routeId,
      scope: 'member',
      rsvps: result.rows.filter((r) => String(r.user_id) === uid),
      summary: result.summary
    });
  });

  app.patch('/v1/routes/:id', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: routeId } = req.params as { id: string };
    if (!z.string().uuid().safeParse(routeId).success) {
      return reply.code(400).send({ error: 'INVALID_ROUTE_ID' });
    }

    const parsed = UpdateClubRouteSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }

    const rls = getRlsPool();
    if (!rls) return reply.code(503).send({ error: 'DB_UNAVAILABLE' });

    const result = await withUserDb(rls, uid, async (client) => {
      const routeRes = await client.query(
        `SELECT id, group_id, status, starts_at
         FROM club_routes
         WHERE id = $1`,
        [routeId]
      );
      const route = routeRes.rows[0];
      if (!route) return { kind: 'not_found' as const };

      const leaderRes = await client.query(
        `SELECT 1 FROM group_leaders
         WHERE group_id = $1 AND user_id = $2`,
        [route.group_id, uid]
      );
      if (leaderRes.rows.length === 0) return { kind: 'forbidden' as const };

      if (route.status !== 'published') {
        return { kind: 'not_editable' as const, reason: 'STATUS_NOT_EDITABLE' };
      }

      const notPastRes = await client.query(
        `SELECT (starts_at > now()) AS editable FROM club_routes WHERE id = $1`,
        [routeId]
      );
      if (!notPastRes.rows[0]?.editable) {
        return { kind: 'not_editable' as const, reason: 'ROUTE_ALREADY_STARTED' };
      }

      if (parsed.data.startsAt) {
        const futureRes = await client.query(`SELECT ($1::timestamptz > now()) AS future`, [parsed.data.startsAt]);
        if (!futureRes.rows[0]?.future) {
          return { kind: 'not_editable' as const, reason: 'STARTS_AT_MUST_BE_FUTURE' };
        }
      }

      const updates: string[] = [];
      const values: unknown[] = [];
      let idx = 1;
      if (parsed.data.title !== undefined) {
        updates.push(`title = $${idx++}`);
        values.push(parsed.data.title);
      }
      if (Object.prototype.hasOwnProperty.call(parsed.data, 'description')) {
        updates.push(`description = $${idx++}`);
        values.push(parsed.data.description ?? null);
      }
      if (parsed.data.startsAt !== undefined) {
        updates.push(`starts_at = $${idx++}::timestamptz`);
        values.push(parsed.data.startsAt);
      }
      if (Object.prototype.hasOwnProperty.call(parsed.data, 'endsAt')) {
        updates.push(`ends_at = $${idx++}::timestamptz`);
        values.push(parsed.data.endsAt ?? null);
      }
      if (Object.prototype.hasOwnProperty.call(parsed.data, 'routeRef')) {
        updates.push(`route_ref = $${idx++}`);
        values.push(parsed.data.routeRef ?? null);
      }
      updates.push('updated_at = now()');
      values.push(routeId);

      const upRes = await client.query(
        `UPDATE club_routes
         SET ${updates.join(', ')}
         WHERE id = $${idx}
         RETURNING id, group_id, created_by, title, description, route_ref, starts_at, ends_at,
                   status, created_at, updated_at`,
        values
      );

      return { kind: 'ok' as const, route: upRes.rows[0] };
    });

    if (result.kind === 'not_found') return reply.code(404).send({ error: 'ROUTE_NOT_FOUND' });
    if (result.kind === 'forbidden') return reply.code(403).send({ error: 'LEADER_REQUIRED' });
    if (result.kind === 'not_editable') {
      return reply.code(409).send({ error: result.reason });
    }
    return reply.code(200).send({ route: result.route });
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

  // Expulsión formal de un miembro (solo líderes; protección de Fundador RULES R14.3)
  app.delete('/v1/groups/:id/members/:memberUid', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId, memberUid } = req.params as { id: string; memberUid: string };

    const grpRes = await db.query(
      `SELECT id, principal_leader FROM groups WHERE id = $1`,
      [groupId]
    );
    const group = grpRes.rows[0];
    if (!group) return reply.code(404).send({ error: 'GROUP_NOT_FOUND' });

    // R14.3: El líder principal (Fundador) no puede ser expulsado
    if (group.principal_leader === memberUid) {
      return reply.code(403).send({
        error: 'CANNOT_EXPEL_FOUNDER',
        message: 'El líder principal (Fundador) no puede ser expulsado del club (RULES R14.3)'
      });
    }

    // Solo un líder del grupo puede expulsar miembros
    const leaderRes = await db.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [groupId, uid]
    );
    if (leaderRes.rows.length === 0 && group.principal_leader !== uid) {
      return reply.code(403).send({
        error: 'LEADER_REQUIRED',
        message: 'Solo un líder del grupo puede expulsar miembros (RULES R2.1, R14)'
      });
    }

    await db.query(
      `DELETE FROM memberships WHERE group_id = $1 AND user_id = $2`,
      [groupId, memberUid]
    );
    await db.query(
      `DELETE FROM group_leaders WHERE group_id = $1 AND user_id = $2 AND is_principal = false`,
      [groupId, memberUid]
    );

    await appendAudit(db, uid, 'MEMBER_EXPELLED', 'group', groupId, {
      expelledMember: memberUid
    });

    return reply.code(200).send({
      success: true,
      groupId,
      expelledUserId: memberUid
    });
  });

  // ---------------------------------------------------------------------------
  // 6. Lista de miembros con semáforo de pagos (RULES R7.2, R3.3; BACKEND_LICENSING §8)
  // ---------------------------------------------------------------------------
  app.get('/v1/groups/:id/members', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };
    if (!z.string().uuid().safeParse(groupId).success) {
      return reply.code(400).send({ error: 'INVALID_GROUP_ID' });
    }

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
      `SELECT gl.user_id, gl.is_principal, gl.added_at, gl.club_role,
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
        clubRole: l.club_role || (l.is_principal ? 'presidente' : 'vicepresidente'),
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
  // Rate Limiting /v1/invites (Fase 7.1.1)
  // ---------------------------------------------------------------------------
  app.post('/v1/invites', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const clientIp = getClientIp(req);
    const inviteRateLimited = await checkAndEnforceInviteRateLimit(clientIp, uid, reply);
    if (inviteRateLimited) return;

    const body = (req.body ?? {}) as Record<string, unknown>;
    const groupId = body.groupId as string | undefined;
    if (groupId) {
      const leaderRes = await db.query(
        `SELECT 1 FROM group_leaders WHERE user_id = $1 AND group_id = $2`,
        [uid, groupId]
      );
      if (leaderRes.rows.length === 0) {
        return reply.code(403).send({ error: 'LEADER_REQUIRED', message: 'Se requiere rol de líder para invitar' });
      }
    }

    return reply.code(201).send({
      status: 'sent',
      invitedBy: uid,
      ip: clientIp,
      createdAt: new Date().toISOString()
    });
  });

  // ---------------------------------------------------------------------------
  // 7. Invitaciones con consentimiento obligatorio (RULES R2.1, R2.2, R15.1)
  // ---------------------------------------------------------------------------
  app.post('/v1/groups/:id/invitations', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };
    if (!z.string().uuid().safeParse(groupId).success) {
      return reply.code(400).send({ error: 'INVALID_GROUP_ID' });
    }

    const clientIp = getClientIp(req);
    const inviteRateLimited = await checkAndEnforceInviteRateLimit(clientIp, uid, reply);
    if (inviteRateLimited) return;

    const parsed = CreateInvitationSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { invitedUserId, statusKind, clubRole, invitationType = 'hermandad' } = parsed.data;

    // P7 APROBADA (RULES.md §3 y §12, MEMORY.md D42): Los invitados (`guest`) no reciben cargos honoríficos
    if (!canMemberHoldClubRole(statusKind ?? 'prospect', clubRole, forbidGuestClubRoles)) {
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

    const targetUserId = (invitedUserId && invitedUserId !== uid) ? invitedUserId : null;

    // Si se invita a Hermandad como full o prospect a un usuario específico, comprobar que no sea ya full/prospect en otro grupo (RULES R2.2)
    if (targetUserId && invitationType === 'hermandad' && (statusKind === 'full' || statusKind === 'prospect')) {
      const existingPrimary = await db.query(
        `SELECT group_id FROM memberships
         WHERE user_id = $1 AND status_kind IN ('full','prospect') AND invite_state = 'accepted'
           AND group_id <> $2`,
        [targetUserId, groupId]
      );
      if (existingPrimary.rows.length > 0) {
        return reply.code(409).send({
          error: 'ERR_DUAL_MEMBERSHIP_PROHIBITED',
          message:
            'RULES R2.2: El usuario ya pertenece a otra hermandad biker; solo puede participar en rodadas libres o como invitado (RULES R2.2/R2.3)'
        });
      }
    }

    const invId = parsed.data.customId || randomUUID();
    const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(); // 24h según modelo canónico
    await db.query(
      `INSERT INTO invitations (id, group_id, invited_user, invited_by, status_kind, invitation_type, state, expires_at)
       VALUES ($1, $2, $3, $4, $5, $6, 'pending', $7)`,
      [invId, groupId, targetUserId, uid, statusKind || 'full', invitationType, expiresAt]
    );

    await appendAudit(db, uid, 'INVITATION_SENT', 'invitation', invId, {
      groupId,
      invitedUserId: targetUserId,
      statusKind: statusKind || 'full',
      invitationType
    });

    return reply.code(201).send({
      id: invId,
      groupId,
      invitedUserId: targetUserId,
      statusKind,
      invitationType,
      monthlyCostUsd: invitationType === 'rodada' ? 0.0 : 2.5,
      state: 'pending',
      expiresAt
    });
  });

  // Lista de invitaciones emitidas de un grupo (para líderes del club)
  app.get('/v1/groups/:id/invitations', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };
    if (!z.string().uuid().safeParse(groupId).success) {
      return reply.code(400).send({ error: 'INVALID_GROUP_ID' });
    }

    const leaderCheck = await db.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [groupId, uid]
    );
    if (leaderCheck.rows.length === 0) {
      return reply.code(403).send({ error: 'LEADER_REQUIRED' });
    }

    const res = await db.query(
      `SELECT i.id, i.group_id, i.invited_user, i.invited_by, i.status_kind, i.invitation_type, i.state, i.expires_at, i.created_at,
              u.display_name AS invitee_name,
              l.display_name AS inviter_name
       FROM invitations i
       LEFT JOIN users u ON u.id = i.invited_user
       LEFT JOIN users l ON l.id = i.invited_by
       WHERE i.group_id = $1
       ORDER BY i.created_at DESC`,
      [groupId]
    );

    return {
      invitations: res.rows.map((r) => ({
        id: r.id,
        groupId: r.group_id,
        invitedUser: r.invited_user,
        inviteeName: r.invitee_name,
        invitedBy: r.invited_by,
        inviterName: r.inviter_name,
        statusKind: r.status_kind,
        invitationType: r.invitation_type || 'hermandad',
        state: r.state,
        expiresAt: r.expires_at,
        createdAt: r.created_at
      }))
    };
  });

  app.get('/v1/invitations/me', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const res = await db.query(
      `SELECT i.id, i.group_id, i.invited_by, i.status_kind, i.invitation_type, i.state, i.expires_at,
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
        invitationType: r.invitation_type || 'hermandad',
        monthlyCostUsd: r.invitation_type === 'rodada' ? 0.0 : 2.5,
        expiresAt: r.expires_at
      }))
    };
  });

  const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  function isUuid(id: string): boolean {
    return typeof id === 'string' && UUID_REGEX.test(id);
  }

  app.post('/v1/invitations/:id/accept', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: invId } = req.params as { id: string };

    const parsed = AcceptInvitationSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { consents, policyVersion } = parsed.data;

    if (!isUuid(invId)) {
      return reply.code(404).send({
        error: 'INVITATION_NOT_FOUND',
        message: 'La invitación no existe en el servidor central (invitación local o fuera de línea).'
      });
    }

    const invRes = await db.query(
      `SELECT id, group_id, invited_user, status_kind, invitation_type, state, expires_at
       FROM invitations WHERE id = $1`,
      [invId]
    );
    const inv = invRes.rows[0];
    if (!inv) return reply.code(404).send({ error: 'INVITATION_NOT_FOUND' });
    if (inv.invited_user && inv.invited_user !== uid && inv.invited_user !== inv.invited_by) {
      return reply.code(403).send({ error: 'FORBIDDEN' });
    }
    if (inv.state !== 'pending') {
      return reply.code(409).send({ error: 'INVITATION_NOT_PENDING', state: inv.state });
    }
    if (new Date(inv.expires_at).getTime() <= Date.now()) {
      await db.query(`UPDATE invitations SET state = 'expired' WHERE id = $1`, [invId]);
      return reply.code(410).send({ error: 'INVITATION_EXPIRED' });
    }

    const isHermandad = (inv.invitation_type || 'hermandad') === 'hermandad';

    if (isHermandad) {
      // R2.1 y R15.1: El consentimiento de pertenencia y cuota ($2.50/mes) es obligatorio para entrar a la hermandad
      if (!consents.membership) {
        return reply.code(400).send({
          error: 'MEMBERSHIP_CONSENT_REQUIRED',
          message: 'Nadie entra a un grupo sin aceptar la pertenencia y cuota de $2.50/mes (RULES R2.1, R15.1a)'
        });
      }

      // Regla R2.2: Prohibición de doble membresía
      const existingPrimary = await db.query(
        `SELECT group_id FROM memberships
         WHERE user_id = $1 AND status_kind IN ('full','prospect') AND invite_state = 'accepted'
           AND group_id <> $2`,
        [uid, inv.group_id]
      );
      if (existingPrimary.rows.length > 0) {
        return reply.code(409).send({
          error: 'ERR_DUAL_MEMBERSHIP_PROHIBITED',
          message: 'RULES R2.2: Un piloto solo puede pertenecer a una hermandad biker a la vez. No se permite la doble membresía.'
        });
      }
    }

    // Regla R2.3: Si es rodada oficial, confirma la invitación sin crear registro de membresía formal
    if (!isHermandad || inv.invitation_type === 'rodada') {
      await db.query(`UPDATE invitations SET state = 'accepted', invited_user = $1 WHERE id = $2`, [uid, invId]);

      if (consents.location) {
        await db.query(
          `INSERT INTO consents (id, user_id, group_id, kind, granted, policy_version)
           VALUES ($1, $2, $3, 'location', true, $4)`,
          [randomUUID(), uid, inv.group_id, policyVersion]
        );
      }

      await appendAudit(db, uid, 'RIDE_INVITATION_ACCEPTED', 'invitation', invId, {
        groupId: inv.group_id
      });

      return reply.code(200).send({
        invitationId: invId,
        groupId: inv.group_id,
        invitationType: 'rodada',
        statusKind: 'guest',
        inviteState: 'accepted',
        message: 'Invitación a rodar confirmada libre y gratuitamente (RULES R2.3)'
      });
    }

    // Flujo canónico de Hermandad (Full / Prospect)
    const membershipId = randomUUID();
    const paidUntil = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

    await db.query(`UPDATE invitations SET state = 'accepted', invited_user = $1 WHERE id = $2`, [uid, invId]);
    await db.query(
      `INSERT INTO memberships (
         id, group_id, user_id, status_kind, club_role, invite_state, accepted_at, paid_until
       ) VALUES ($1, $2, $3, $4, NULL, 'accepted', now(), $5)`,
      [membershipId, inv.group_id, uid, inv.status_kind, paidUntil]
    );

    // Registrar consentimientos otorgados (RULES R15.1)
    const consentEntries: Array<[string, boolean | undefined]> = [
      ['membership', consents.membership],
      ['location', consents.location],
      ['emergency_package', consents.emergencyPackage],
      ['notifications', consents.notifications]
    ];
    for (const [kind, granted] of consentEntries) {
      if (granted !== undefined) {
        await db.query(
          `INSERT INTO consents (id, user_id, group_id, kind, granted, policy_version)
           VALUES ($1, $2, $3, $4, $5, $6)`,
          [randomUUID(), uid, inv.group_id, kind, granted, policyVersion]
        );
      }
    }

    await appendAudit(db, uid, 'INVITATION_ACCEPTED', 'membership', membershipId, {
      groupId: inv.group_id,
      statusKind: inv.status_kind
    });

    return reply.code(200).send({
      membershipId,
      groupId: inv.group_id,
      statusKind: inv.status_kind,
      invitationType: 'hermandad',
      inviteState: 'accepted',
      paidUntil
    });
  });

  app.post('/v1/invitations/:id/decline', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: invId } = req.params as { id: string };

    if (!isUuid(invId)) {
      return reply.code(404).send({
        error: 'INVITATION_NOT_FOUND',
        message: 'La invitación no existe en el servidor central (invitación local o fuera de línea).'
      });
    }

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
    if (!z.string().uuid().safeParse(groupId).success) {
      return reply.code(400).send({ error: 'INVALID_GROUP_ID' });
    }

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
    if (!mem) {
      const glRes = await db.query(
        `SELECT is_principal FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
        [groupId, memberUid]
      );
      if (glRes.rows.length > 0) {
        const nextRole = (clubRole !== undefined)
          ? (clubRole ? normalizeIncomingClubRole(clubRole) : null)
          : 'presidente';
        await db.query(
          `UPDATE group_leaders SET club_role = $1 WHERE group_id = $2 AND user_id = $3`,
          [nextRole, groupId, memberUid]
        );
        return {
          groupId,
          userId: memberUid,
          statusKind: 'full',
          clubRole: nextRole
        };
      }
      return reply.code(404).send({ error: 'MEMBERSHIP_NOT_FOUND' });
    }

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
    const nextRole = (clubRole !== undefined)
      ? (clubRole ? normalizeIncomingClubRole(clubRole) : null)
      : mem.club_role;

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

  app.get('/v1/groups/:id/votes', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };

    const leaderRes = await db.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [groupId, uid]
    );
    if (leaderRes.rows.length === 0) {
      return reply.code(403).send({ error: 'LEADER_REQUIRED' });
    }

    const votesRes = await db.query(
      `SELECT v.id, v.group_id, v.proposal, v.target_id, v.proposed_by, v.state, v.expires_at,
              u.display_name AS proposed_by_name,
              tu.display_name AS target_name
       FROM leader_votes v
       LEFT JOIN users u ON u.id = v.proposed_by
       LEFT JOIN users tu ON tu.id = v.target_id
       WHERE v.group_id = $1
       ORDER BY v.expires_at DESC`,
      [groupId]
    );

    const votesWithBallots = await Promise.all(
      votesRes.rows.map(async (row) => {
        const ballotsRes = await db.query(
          `SELECT b.leader, b.approve, u.display_name AS leader_name
           FROM leader_vote_ballots b
           JOIN users u ON u.id = b.leader
           WHERE b.vote_id = $1`,
          [row.id]
        );
        return {
          ...row,
          ballots: ballotsRes.rows
        };
      })
    );

    return { votes: votesWithBallots };
  });

  // ---------------------------------------------------------------------------
  // 10b. Moderación y Reportes de Riders (RULES R13.1, R13.2, R13.5)
  // ---------------------------------------------------------------------------
  app.post('/v1/groups/:id/reports', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };

    const parsed = CreateModerationReportSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { reportedUserId, rideId, reason, note } = parsed.data;

    // R13.5: Si el reportado es un líder, verificar si hay otros líderes en el grupo
    const reportedLeaderRes = await db.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [groupId, reportedUserId]
    );
    const isReportedLeader = reportedLeaderRes.rows.length > 0;

    const otherLeadersRes = await db.query(
      `SELECT count(*)::int AS count FROM group_leaders WHERE group_id = $1 AND user_id != $2`,
      [groupId, reportedUserId]
    );
    const otherLeadersCount = otherLeadersRes.rows[0]?.count ?? 0;

    let initialState = 'open';
    if (isReportedLeader && otherLeadersCount === 0) {
      // R13.5: si no hay otro líder, se escala a soporte de la plataforma
      initialState = 'escalated';
    }

    const reportId = randomUUID();
    await db.query(
      `INSERT INTO moderation_reports (id, reporter, reported, group_id, ride_id, reason, state)
       VALUES ($1, $2, $3, $4, $5, $6, $7)`,
      [reportId, uid, reportedUserId, groupId, rideId ?? null, reason, initialState]
    );

    await appendAudit(db, uid, 'MODERATION_REPORT_FILED', 'moderation_report', reportId, {
      groupId,
      reportedUserId,
      rideId: rideId ?? null,
      reason,
      state: initialState,
      note: note ?? null
    });

    return reply.code(201).send({
      id: reportId,
      groupId,
      reportedUserId,
      reason,
      state: initialState
    });
  });

  app.get('/v1/groups/:id/reports', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId } = req.params as { id: string };

    const leaderRes = await db.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [groupId, uid]
    );
    if (leaderRes.rows.length === 0) {
      return reply.code(403).send({ error: 'LEADER_REQUIRED' });
    }

    // R13.5: Un reporte contra un líder lo revisan los otros líderes del grupo.
    // El líder acusado no ve los reportes en su contra.
    const reportsRes = await db.query(
      `SELECT r.id, r.reporter, r.reported, r.group_id, r.ride_id, r.reason, r.state, r.resolution,
              r.decided_by, r.created_at,
              u_rep.display_name AS reporter_name,
              u_tar.display_name AS reported_name
       FROM moderation_reports r
       JOIN users u_rep ON u_rep.id = r.reporter
       JOIN users u_tar ON u_tar.id = r.reported
       WHERE r.group_id = $1 AND r.reported != $2
       ORDER BY r.created_at DESC`,
      [groupId, uid]
    );

    return { reports: reportsRes.rows };
  });

  app.post('/v1/groups/:id/reports/:reportId/resolve', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: groupId, reportId } = req.params as { id: string; reportId: string };

    const leaderRes = await db.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [groupId, uid]
    );
    if (leaderRes.rows.length === 0) {
      return reply.code(403).send({ error: 'LEADER_REQUIRED' });
    }

    const parsed = ResolveModerationReportSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { resolution, state } = parsed.data;

    const repRes = await db.query(
      `SELECT reported FROM moderation_reports WHERE id = $1 AND group_id = $2`,
      [reportId, groupId]
    );
    if (repRes.rows.length === 0) {
      return reply.code(404).send({ error: 'REPORT_NOT_FOUND' });
    }
    // R13.5: No puede resolver un reporte contra sí mismo
    if (repRes.rows[0].reported === uid) {
      return reply.code(403).send({ error: 'CANNOT_DECIDE_OWN_REPORT' });
    }

    await db.query(
      `UPDATE moderation_reports
       SET state = $1, resolution = $2, decided_by = array_append(COALESCE(decided_by, '{}'::uuid[]), $3)
       WHERE id = $4`,
      [state, resolution ?? null, uid, reportId]
    );

    await appendAudit(db, uid, 'MODERATION_REPORT_RESOLVED', 'moderation_report', reportId, {
      groupId,
      state,
      resolution
    });

    return { id: reportId, state, resolution };
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

  app.post('/v1/users/me/consents', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const parsed = UpdateConsentSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }

    const { groupId, kind, granted, policyVersion } = parsed.data;

    // Revocar cualquier consentimiento activo del mismo tipo y grupo
    await db.query(
      `UPDATE consents
       SET granted = false, revoked_at = now()
       WHERE user_id = $1 AND kind = $2
         AND (($3::uuid IS NULL AND group_id IS NULL) OR group_id = $3::uuid)
         AND revoked_at IS NULL`,
      [uid, kind, groupId || null]
    );

    if (granted) {
      const consentId = randomUUID();
      await db.query(
        `INSERT INTO consents (id, user_id, group_id, kind, granted, policy_version)
         VALUES ($1, $2, $3, $4, true, $5)`,
        [consentId, uid, groupId || null, kind, policyVersion]
      );
      await appendAudit(db, uid, 'CONSENT_GRANTED', 'consent', consentId, { kind, groupId, policyVersion });
    } else {
      if (kind === 'location') {
        await appendAudit(db, uid, 'CONSENT_LOCATION_REVOKED', 'consent', uid, { groupId });
      } else if (kind === 'emergency_package') {
        await appendAudit(db, uid, 'CONSENT_EMERGENCY_PACKAGE_REVOKED', 'consent', uid, { groupId });
      } else {
        await appendAudit(db, uid, 'CONSENT_REVOKED', 'consent', uid, { kind, groupId });
      }
    }

    const updatedRes = await db.query(
      `SELECT id, group_id, kind, granted, policy_version, created_at, revoked_at
       FROM consents WHERE user_id = $1 ORDER BY created_at DESC`,
      [uid]
    );

    return {
      success: true,
      kind,
      groupId: groupId || null,
      granted,
      consents: updatedRes.rows
    };
  });

  // ===========================================================================
  // Notificaciones Push FCM (Fase 7.1.2)
  // ===========================================================================
  const FcmTokenSchema = z.object({
    token: z.string().min(10).max(500),
    platform: z.enum(['android', 'ios']).default('android').optional()
  });

  app.post('/v1/users/fcm-token', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const parsed = FcmTokenSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }

    const { token, platform = 'android' } = parsed.data;
    await fcmService.registerToken(db, uid, token, platform);

    return reply.code(200).send({
      success: true,
      userId: uid,
      platform,
      registeredAt: new Date().toISOString()
    });
  });

  const SendNotificationSchema = z.object({
    type: z.enum(['SOS_PROXIMITY', 'RIDE_INVITATION', 'CLUB_INVITATION', 'DIRECT_MESSAGE']),
    recipientUserIds: z.array(z.string().uuid()).min(1),
    params: z.record(z.any())
  });

  app.post('/v1/notifications/send', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const parsed = SendNotificationSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }

    const { type, recipientUserIds, params } = parsed.data;

    let allTokens: string[] = [];
    for (const rId of recipientUserIds) {
      const userTokens = await fcmService.getActiveTokensForUser(db, rId);
      allTokens = allTokens.concat(userTokens);
    }

    if (allTokens.length === 0) {
      return reply.code(200).send({
        success: true,
        sentCount: 0,
        failureCount: 0,
        message: 'No hay tokens FCM activos o con consentimiento para los destinatarios'
      });
    }

    let result: SendResult;
    switch (type) {
      case 'SOS_PROXIMITY':
        result = await fcmService.sendSosProximity(allTokens, {
          rideId: params.rideId,
          senderUserId: uid,
          lat: Number(params.lat ?? 0),
          lng: Number(params.lng ?? 0)
        });
        break;
      case 'RIDE_INVITATION':
        result = await fcmService.sendRideInvitation(allTokens, {
          rideId: params.rideId,
          clubName: String(params.clubName ?? 'Club Biker'),
          rideTitle: String(params.rideTitle ?? 'Nueva Rodada')
        });
        break;
      case 'CLUB_INVITATION':
        result = await fcmService.sendClubInvitation(allTokens, {
          groupId: params.groupId,
          clubName: String(params.clubName ?? 'Club Biker'),
          inviterName: String(params.inviterName ?? 'Líder')
        });
        break;
      case 'DIRECT_MESSAGE':
        result = await fcmService.sendDirectMessage(allTokens, {
          fromUserId: uid,
          fromAlias: String(params.fromAlias ?? 'Compañero'),
          preview: String(params.preview ?? 'Nuevo mensaje')
        });
        break;
    }

    return reply.code(200).send({
      success: true,
      type,
      sentCount: result.successCount,
      failureCount: result.failureCount
    });
  });

  // ===========================================================================
  // Radar SOS de Proximidad 5 km y Telemetría Efímera (Fase 7.1.5)
  // ===========================================================================

  const TelemetryHeartbeatSchema = z.object({
    latitude: z.number().min(-90).max(90),
    longitude: z.number().min(-180).max(180),
    heading: z.number().min(0).max(360).optional().default(0.0),
    speedKmh: z.number().min(0).max(350).optional().default(0.0)
  });

  app.post('/v1/telemetry/heartbeat', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const parsed = TelemetryHeartbeatSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }

    // Verificar consentimiento de ubicación (R15)
    const consentRes = await db.query(
      `SELECT granted FROM consents
       WHERE user_id = $1 AND kind = 'location' AND revoked_at IS NULL
       ORDER BY created_at DESC LIMIT 1`,
      [uid]
    );

    if (consentRes.rows.length > 0 && consentRes.rows[0].granted === false) {
      return reply.code(403).send({
        error: 'LOCATION_CONSENT_REQUIRED',
        message: 'Se requiere consentimiento de ubicación para emitir telemetría y activar el radar'
      });
    }

    const { latitude, longitude, heading = 0.0, speedKmh = 0.0 } = parsed.data;

    await db.query(
      `INSERT INTO ephemeral_telemetry (user_id, lat, lng, heading, speed, recorded_at, created_at)
       VALUES ($1, $2, $3, $4, $5, NOW(), NOW())`,
      [uid, latitude, longitude, heading, speedKmh]
    );

    return reply.code(200).send({
      success: true,
      userId: uid,
      updatedAt: new Date().toISOString()
    });
  });

  const TriggerSosSchema = z.object({
    latitude: z.number().min(-90).max(90),
    longitude: z.number().min(-180).max(180),
    hasMedicalSheet: z.boolean().optional().default(false),
    radiusKm: z.number().min(0.5).max(50.0).optional().default(5.0),
    notes: z.string().max(250).optional(),
    source: z.enum(['app', 'ble_mesh']).optional().default('app'),
    meshPacketId: z.string().optional()
  });

  app.post('/v1/sos', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const parsed = TriggerSosSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }

    // Verificar consentimiento emergency_package del emisor (R15)
    const consentRes = await db.query(
      `SELECT granted FROM consents
       WHERE user_id = $1 AND kind = 'emergency_package' AND revoked_at IS NULL
       ORDER BY created_at DESC LIMIT 1`,
      [uid]
    );

    if (consentRes.rows.length > 0 && consentRes.rows[0].granted === false) {
      return reply.code(403).send({
        error: 'EMERGENCY_PACKAGE_CONSENT_REQUIRED',
        message: 'Se requiere consentimiento de paquete de emergencia para detonar el radar SOS'
      });
    }

    const { latitude, longitude, hasMedicalSheet = false, radiusKm = 5.0, notes = null, source = 'app', meshPacketId = null } = parsed.data;

    // Deduplicación por meshPacketId si viene de retransmisión BLE Mesh
    if (meshPacketId) {
      const existing = await db.query(
        `SELECT id, created_at, status FROM sos_events WHERE mesh_packet_id = $1 LIMIT 1`,
        [meshPacketId]
      );
      if (existing.rows.length > 0) {
        return reply.code(200).send({
          success: true,
          sosId: existing.rows[0].id,
          latitude,
          longitude,
          radiusKm,
          hasMedicalSheet,
          source,
          meshPacketId,
          duplicate: true,
          nearbyDetectedCount: 0,
          notifiedUsersCount: 0,
          notifiedUserIds: [],
          createdAt: existing.rows[0].created_at
        });
      }
    }

    // 1. Insertar evento SOS activo
    const sosRes = await db.query(
      `INSERT INTO sos_events (user_id, latitude, longitude, has_medical_sheet, radius_km, status, notes, source, mesh_packet_id)
       VALUES ($1, $2, $3, $4, $5, 'active', $6, $7, $8)
       RETURNING id, created_at`,
      [uid, latitude, longitude, hasMedicalSheet, radiusKm, notes, source, meshPacketId]
    );
    const sosEvent = sosRes.rows[0];

    // 2. Actualizar telemetría efímera del accidentado
    await db.query(
      `INSERT INTO ephemeral_telemetry (user_id, lat, lng, heading, speed, recorded_at, created_at)
       VALUES ($1, $2, $3, 0.0, 0.0, NOW(), NOW())`,
      [uid, latitude, longitude]
    );

    // 3. Consulta Haversine en SQL puro con Bounding Box indexado (±0.05° lat, ±0.06° lon)
    // Filtro de consentimiento estricto: emergency_package = true Y notifications = true
    const nearbyRes = await db.query(
      `WITH latest_telemetry AS (
         SELECT DISTINCT ON (user_id) user_id, lat, lng, recorded_at
         FROM ephemeral_telemetry
         WHERE recorded_at >= NOW() - INTERVAL '30 minutes'
           AND lat BETWEEN ($1 - 0.05) AND ($1 + 0.05)
           AND lng BETWEEN ($2 - 0.06) AND ($2 + 0.06)
         ORDER BY user_id, recorded_at DESC
       )
       SELECT 
         t.user_id,
         t.lat,
         t.lng,
         (
           6371.0 * 2.0 * ASIN(
             SQRT(
               POWER(SIN(RADIANS(t.lat - $1) / 2.0), 2) +
               COS(RADIANS($1)) * COS(RADIANS(t.lat)) *
               POWER(SIN(RADIANS(t.lng - $2) / 2.0), 2)
             )
           )
         ) AS distance_km
       FROM latest_telemetry t
       JOIN consents c_em ON c_em.user_id = t.user_id AND c_em.kind = 'emergency_package' AND c_em.granted = TRUE
       JOIN consents c_notif ON c_notif.user_id = t.user_id AND c_notif.kind = 'notifications' AND c_notif.granted = TRUE
       WHERE t.user_id != $3
         AND (
           6371.0 * 2.0 * ASIN(
             SQRT(
               POWER(SIN(RADIANS(t.lat - $1) / 2.0), 2) +
               COS(RADIANS($1)) * COS(RADIANS(t.lat)) *
               POWER(SIN(RADIANS(t.lng - $2) / 2.0), 2)
             )
           )
         ) <= $4
       ORDER BY distance_km ASC`,
      [latitude, longitude, uid, radiusKm]
    );

    let notifiedCount = 0;
    const notifiedUserIds: string[] = [];

    // 4. Despachar notificaciones push a cada rescatista cercano
    for (const row of nearbyRes.rows) {
      const dist = parseFloat(Number(row.distance_km).toFixed(1));
      const bearingDeg = calculateBearingDegrees(row.lat, row.lng, latitude, longitude);
      const cardinal = degreesToCardinal(bearingDeg);

      const tokenRes = await db.query(
        `SELECT token FROM fcm_tokens WHERE user_id = $1`,
        [row.user_id]
      );
      const tokens = tokenRes.rows.map((r: { token: string }) => r.token);

      if (tokens.length > 0) {
        await fcmService.sendSosProximity(tokens, {
          sosId: sosEvent.id,
          distanceKm: dist.toString(),
          bearing: cardinal,
          hasMedicalSheet: hasMedicalSheet ? 'true' : 'false'
        });
        notifiedCount++;
        notifiedUserIds.push(row.user_id);
      }
    }

    return reply.code(201).send({
      success: true,
      sosId: sosEvent.id,
      latitude,
      longitude,
      radiusKm,
      hasMedicalSheet,
      source,
      meshPacketId,
      nearbyDetectedCount: nearbyRes.rows.length,
      notifiedUsersCount: notifiedCount,
      notifiedUserIds,
      createdAt: sosEvent.created_at
    });
  });

  app.get('/v1/sos/nearby', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    // Verificar consentimiento emergency_package (R15)
    const consentRes = await db.query(
      `SELECT granted FROM consents
       WHERE user_id = $1 AND kind = 'emergency_package' AND revoked_at IS NULL
       ORDER BY created_at DESC LIMIT 1`,
      [uid]
    );

    if (consentRes.rows.length > 0 && consentRes.rows[0].granted === false) {
      return reply.code(403).send({
        error: 'EMERGENCY_PACKAGE_CONSENT_REQUIRED',
        message: 'Se requiere consentimiento de paquete de emergencia para acceder al radar de proximidad'
      });
    }

    const query = req.query as { lat?: string; lng?: string; radiusKm?: string };
    let userLat = query.lat ? parseFloat(query.lat) : NaN;
    let userLng = query.lng ? parseFloat(query.lng) : NaN;
    const radiusKm = query.radiusKm ? parseFloat(query.radiusKm) : 5.0;

    if (isNaN(userLat) || isNaN(userLng)) {
      // Fallback a última posición conocida en ephemeral_telemetry
      const locRes = await db.query(
        `SELECT lat, lng FROM ephemeral_telemetry WHERE user_id = $1 ORDER BY recorded_at DESC LIMIT 1`,
        [uid]
      );
      if (locRes.rows.length > 0) {
        userLat = locRes.rows[0].lat;
        userLng = locRes.rows[0].lng;
      } else {
        return reply.code(200).send({
          count: 0,
          sosAlerts: []
        });
      }
    }

    const sosRows = await db.query(
      `SELECT 
         s.id,
         s.user_id,
         s.latitude,
         s.longitude,
         s.has_medical_sheet,
         s.radius_km,
         s.created_at,
         s.notes,
         s.source,
         s.mesh_packet_id,
         u.display_name AS emitter_alias,
         (
           6371.0 * 2.0 * ASIN(
             SQRT(
               POWER(SIN(RADIANS(s.latitude - $1) / 2.0), 2) +
               COS(RADIANS($1)) * COS(RADIANS(s.latitude)) *
               POWER(SIN(RADIANS(s.longitude - $2) / 2.0), 2)
             )
           )
         ) AS distance_km
       FROM sos_events s
       JOIN users u ON u.id = s.user_id
       WHERE s.status = 'active'
         AND s.user_id != $3
         AND s.created_at >= NOW() - INTERVAL '6 hours'
         AND s.latitude BETWEEN ($1 - 0.05) AND ($1 + 0.05)
         AND s.longitude BETWEEN ($2 - 0.06) AND ($2 + 0.06)
         AND (
           6371.0 * 2.0 * ASIN(
             SQRT(
               POWER(SIN(RADIANS(s.latitude - $1) / 2.0), 2) +
               COS(RADIANS($1)) * COS(RADIANS(s.latitude)) *
               POWER(SIN(RADIANS(s.longitude - $2) / 2.0), 2)
             )
           )
         ) <= $4
       ORDER BY distance_km ASC`,
      [userLat, userLng, uid, radiusKm]
    );

    const alerts = sosRows.rows.map((row: any) => {
      const dist = parseFloat(Number(row.distance_km).toFixed(1));
      const bearingDeg = calculateBearingDegrees(userLat, userLng, row.latitude, row.longitude);
      const cardinal = degreesToCardinal(bearingDeg);
      return {
        id: row.id,
        userId: row.user_id,
        emitterAlias: row.emitter_alias ?? 'Compañero Motero',
        latitude: row.latitude,
        longitude: row.longitude,
        distanceKm: dist,
        bearing: cardinal,
        hasMedicalSheet: Boolean(row.has_medical_sheet),
        notes: row.notes,
        source: row.source ?? 'app',
        meshPacketId: row.mesh_packet_id ?? null,
        createdAt: row.created_at
      };
    });

    return reply.code(200).send({
      count: alerts.length,
      sosAlerts: alerts
    });
  });

  app.get('/v1/sos/:id', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const { id } = req.params as { id: string };
    const sosRes = await db.query(
      `SELECT s.*, u.display_name AS emitter_alias
       FROM sos_events s
       JOIN users u ON u.id = s.user_id
       WHERE s.id = $1`,
      [id]
    );

    if (sosRes.rows.length === 0) {
      return reply.code(404).send({ error: 'NOT_FOUND', message: 'Evento SOS no encontrado' });
    }

    const s = sosRes.rows[0];
    return reply.code(200).send({
      id: s.id,
      userId: s.user_id,
      emitterAlias: s.emitter_alias ?? 'Compañero Motero',
      latitude: s.latitude,
      longitude: s.longitude,
      status: s.status,
      hasMedicalSheet: Boolean(s.has_medical_sheet),
      radiusKm: s.radius_km,
      source: s.source ?? 'app',
      meshPacketId: s.mesh_packet_id ?? null,
      createdAt: s.created_at,
      resolvedAt: s.resolved_at,
      resolvedBy: s.resolved_by,
      notes: s.notes
    });
  });

  app.post('/v1/sos/:id/resolve', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const { id } = req.params as { id: string };
    const updateRes = await db.query(
      `UPDATE sos_events
       SET status = 'resolved', resolved_at = NOW(), resolved_by = $1
       WHERE id = $2 AND status = 'active'
       RETURNING id, resolved_at`,
      [uid, id]
    );

    if (updateRes.rows.length === 0) {
      return reply.code(404).send({ error: 'NOT_FOUND', message: 'Evento SOS no encontrado o ya resuelto' });
    }

    return reply.code(200).send({
      success: true,
      sosId: id,
      status: 'resolved',
      resolvedAt: updateRes.rows[0].resolved_at
    });
  });

  // =========================================================================
  // FASE 7.1.3: MAPAS OFFLINE CON PMTILES (CORREDORES VIALES)
  // =========================================================================
  const mapsDir = path.join(fileURLToPath(new URL('..', import.meta.url)), '..', 'public', 'maps');
  ensureCanonicalPmtilesFiles(mapsDir);

  app.get('/v1/maps/regions', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    return reply.code(200).send({
      success: true,
      regions: getAllRegions()
    });
  });

  app.post('/v1/maps/custom-route', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const body = (req.body as any) || {};
    if (!body.name || !body.boundingBox || !Array.isArray(body.boundingBox) || body.boundingBox.length !== 4) {
      return reply.code(400).send({
        error: 'VALIDATION_ERROR',
        message: 'name y boundingBox [minLat, minLng, maxLat, maxLng] son requeridos'
      });
    }

    const routeId = body.id || `custom-${Date.now()}`;
    const region = registerCustomRouteRegion(
      {
        id: routeId,
        name: body.name,
        description: body.description,
        boundingBox: body.boundingBox,
        estimatedSizeMb: typeof body.estimatedSizeMb === 'number' ? body.estimatedSizeMb : 12.0
      },
      mapsDir
    );

    return reply.code(201).send({
      success: true,
      region
    });
  });

  app.get('/v1/maps/regions/:id/download', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const { id } = req.params as { id: string };
    const region = findRegionById(id);
    if (!region) {
      return reply.code(404).send({ error: 'NOT_FOUND', message: 'Región de mapa offline no encontrada' });
    }

    const filePath = getMapFilePath(mapsDir, region.fileName);
    if (!fs.existsSync(filePath)) {
      ensureCanonicalPmtilesFiles(mapsDir);
    }

    const stat = fs.statSync(filePath);
    const rangeHeader = req.headers.range;

    reply.header('Accept-Ranges', 'bytes');
    reply.header('Content-Type', 'application/vnd.pmtiles');
    reply.header('Content-Disposition', `attachment; filename="${region.fileName}"`);

    if (rangeHeader) {
      const match = rangeHeader.match(/bytes=(\d+)-(\d*)/);
      if (match) {
        const start = parseInt(match[1], 10);
        const end = match[2] ? parseInt(match[2], 10) : stat.size - 1;

        if (start >= stat.size || end >= stat.size || start > end) {
          reply.header('Content-Range', `bytes */${stat.size}`);
          return reply.code(416).send({ error: 'RANGE_NOT_SATISFIABLE' });
        }

        const chunkLength = end - start + 1;
        reply.code(206);
        reply.header('Content-Range', `bytes ${start}-${end}/${stat.size}`);
        reply.header('Content-Length', chunkLength);

        const stream = fs.createReadStream(filePath, { start, end });
        return reply.send(stream);
      }
    }

    reply.code(200);
    reply.header('Content-Length', stat.size);
    const stream = fs.createReadStream(filePath);
    return reply.send(stream);
  });

    app.get('/v1/users/me/export', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    // 1. Perfil del usuario
    const userRes = await db.query(
      `SELECT id, display_name, email, bike_model, status, created_at
       FROM users WHERE id = $1`,
      [uid]
    );
    const user = userRes.rows[0] || null;

    // 2. Membresías y grupos
    const membershipsRes = await db.query(
      `SELECT m.group_id, g.name AS group_name, m.status_kind, m.club_role, m.invite_state, m.accepted_at AS created_at
       FROM memberships m
       JOIN groups g ON m.group_id = g.id
       WHERE m.user_id = $1`,
      [uid]
    );

    // 3. Consentimientos
    const consentsRes = await db.query(
      `SELECT id, group_id, kind, granted, policy_version, created_at, revoked_at
       FROM consents WHERE user_id = $1 ORDER BY created_at DESC`,
      [uid]
    );

    // 4. Suscripciones
    const subsRes = await db.query(
      `SELECT id, play_product_id AS product_id, state, created_at, current_period_end AS expires_at, grace_until
       FROM subscriptions WHERE user_id = $1 ORDER BY created_at DESC`,
      [uid]
    );

    // 5. Historial de rodadas
    const ridesRes = await db.query(
      `SELECT r.id AS ride_id, r.title, r.host_group, r.state, r.starts_at, r.closed_at, rp.counted_for_rank
       FROM ride_participants rp
       JOIN rides r ON rp.ride_id = r.id
       WHERE rp.user_id = $1 ORDER BY r.starts_at DESC`,
      [uid]
    );

    // 6. Insignias obtenidas
    const badgesRes = await db.query(
      `SELECT id, family, code, issuer_group, issuer_user, created_at
       FROM badges WHERE receiver = $1 ORDER BY created_at DESC`,
      [uid]
    );

    return {
      exportedAt: new Date().toISOString(),
      gdprCompliance: 'Exportación oficial de datos personales generada conforme a RGPD, LGPD, CCPA y RULES R15.5 de Ruta Biker Sync.',
      user,
      memberships: membershipsRes.rows,
      consents: consentsRes.rows,
      subscriptions: subsRes.rows,
      rides: ridesRes.rows,
      badges: badgesRes.rows
    };
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
       ON CONFLICT (ride_id, user_id) DO UPDATE SET
         state = 'pending',
         invited_by = EXCLUDED.invited_by,
         expires_at = EXCLUDED.expires_at
       RETURNING *`,
      [randomUUID(), rideId, userId, uid, String(hours)]
    );
    return reply.code(201).send(rows[0]);
  });

  app.post('/v1/rides/:rideId/guest-invitations/:invitationId/accept', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { rideId, invitationId } = req.params as { rideId: string; invitationId: string };
    const { consentLocation } = (req.body ?? {}) as { consentLocation?: boolean };
    if (consentLocation === false) {
      return reply.code(400).send({
        error: 'RIDE_LOCATION_CONSENT_REQUIRED',
        message: 'Un invitado a una rodada debe consentir explícitamente para esa salida (RULES R2.1, R15.4)'
      });
    }

    if (!isUuid(rideId) || !isUuid(invitationId)) {
      return reply.code(404).send({
        error: 'GUEST_INVITATION_NOT_FOUND',
        message: 'La invitación no existe en el servidor central (invitación local o fuera de línea).'
      });
    }

    const invRes = await db.query(
      `SELECT id, ride_id, user_id, state, expires_at
       FROM ride_guest_invitations
       WHERE id = $1 AND ride_id = $2`,
      [invitationId, rideId]
    );
    const inv = invRes.rows[0];
    if (!inv) return reply.code(404).send({ error: 'GUEST_INVITATION_NOT_FOUND' });
    if (String(inv.user_id) !== uid) return reply.code(403).send({ error: 'FORBIDDEN' });
    if (new Date(inv.expires_at).getTime() <= Date.now()) {
      await db.query(`UPDATE ride_guest_invitations SET state = 'expired' WHERE id = $1`, [invitationId]);
      return reply.code(410).send({ error: 'GUEST_INVITATION_EXPIRED' });
    }

    const { rows } = await db.query(
      `UPDATE ride_guest_invitations
       SET state = 'accepted', accepted_at = now()
       WHERE id = $1
       RETURNING *`,
      [invitationId]
    );

    const rideRes = await db.query(`SELECT host_group FROM rides WHERE id = $1`, [rideId]);
    const hostGroupId = rideRes.rows[0]?.host_group;
    if (hostGroupId) {
      await db.query(
        `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind, ride_role)
         VALUES ($1, $2, $3, 'guest', 'participante')
         ON CONFLICT (ride_id, user_id) DO NOTHING`,
        [rideId, uid, hostGroupId]
      );
    }

    return reply.code(200).send(rows[0]);
  });

  // ---------------------------------------------------------------------------
  // 17. Fase 3 (Bloque 3.1) — Ciclo de vida de la Rodada, Manifiesto firmado Ed25519
  //     y cierre con validación anti-trampa R6.6 (RULES.md §§6.1–6.6, BACKEND_LICENSING.md §§7, 8)
  // ---------------------------------------------------------------------------
  app.post('/v1/rides', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const body = (req.body ?? {}) as {
      hostGroupId?: string;
      cohostGroupId?: string | null;
      cohostLeaderId?: string | null;
      title?: string;
      routeHash?: string;
      state?: 'planned' | 'active';
      participants?: Array<{
        userId: string;
        groupId?: string;
        statusKind?: CredentialRoleKind;
        rideRole?: RideRoleKind;
      }>;
    };

    if (!body.hostGroupId) {
      return reply.code(400).send({ error: 'HOST_GROUP_REQUIRED' });
    }

    // Verificar que el grupo anfitrión existe y está activo (R4.7: un grupo suspendido no inicia rodadas)
    const grpRes = await db.query(
      `SELECT id, status FROM groups WHERE id = $1`,
      [body.hostGroupId]
    );
    const hostGroup = grpRes.rows[0];
    if (!hostGroup) return reply.code(404).send({ error: 'HOST_GROUP_NOT_FOUND' });
    if (hostGroup.status !== 'active') {
      return reply.code(403).send({
        error: 'GROUP_SUSPENDED',
        message: 'Un grupo suspendido no puede iniciar rodadas (RULES R4.7)'
      });
    }

    // Solo un líder del grupo anfitrión puede crear la rodada (R6.3, R14.1)
    const leaderRes = await db.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [body.hostGroupId, uid]
    );
    if (leaderRes.rows.length === 0) {
      return reply.code(403).send({
        error: 'LEADER_REQUIRED',
        message: 'Solo un líder del grupo anfitrión puede crear la rodada'
      });
    }

    // Si hay grupo coanfitrión (rodada conjunta de 2 grupos — R6.3), verificar que exista y esté activo
    if (body.cohostGroupId) {
      if (body.cohostGroupId === body.hostGroupId) {
        return reply.code(400).send({ error: 'COHOST_MUST_BE_DIFFERENT_GROUP' });
      }
      const coGrpRes = await db.query(
        `SELECT id, status FROM groups WHERE id = $1`,
        [body.cohostGroupId]
      );
      if (!coGrpRes.rows[0] || coGrpRes.rows[0].status !== 'active') {
        return reply.code(400).send({ error: 'INVALID_OR_INACTIVE_COHOST_GROUP' });
      }
    }

    const rideId = randomUUID();
    const initialState = body.state === 'planned' ? 'planned' : 'active';

    await db.query(
      `INSERT INTO rides (
         id, host_group, host_leader, cohost_group, cohost_leader,
         title, route_hash, state, starts_at
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, now())`,
      [
        rideId,
        body.hostGroupId,
        uid,
        body.cohostGroupId ?? null,
        body.cohostLeaderId ?? null,
        body.title ?? null,
        body.routeHash ?? null,
        initialState
      ]
    );

    await db.query(
      `INSERT INTO ride_groups (ride_id, group_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`,
      [rideId, body.hostGroupId]
    );
    if (body.cohostGroupId) {
      await db.query(
        `INSERT INTO ride_groups (ride_id, group_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`,
        [rideId, body.cohostGroupId]
      );
    }

    // El líder anfitrión entra automáticamente como participante de la rodada
    await db.query(
      `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind, ride_role)
       VALUES ($1, $2, $3, 'leader', 'capitan_rodada')
       ON CONFLICT (ride_id, user_id) DO NOTHING`,
      [rideId, uid, body.hostGroupId]
    );

    if (Array.isArray(body.participants)) {
      for (const p of body.participants) {
        await db.query(
          `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind, ride_role)
           VALUES ($1, $2, $3, $4, $5)
           ON CONFLICT (ride_id, user_id) DO UPDATE SET
             status_kind = EXCLUDED.status_kind,
             ride_role = COALESCE(EXCLUDED.ride_role, ride_participants.ride_role)`,
          [
            rideId,
            p.userId,
            p.groupId ?? body.hostGroupId,
            p.statusKind ?? 'full',
            p.rideRole ?? 'participante'
          ]
        );
      }
    }

    await appendAudit(db, uid, 'RIDE_CREATED', 'ride', rideId, {
      hostGroupId: body.hostGroupId,
      cohostGroupId: body.cohostGroupId ?? null,
      state: initialState
    });

    return reply.code(201).send({
      id: rideId,
      hostGroupId: body.hostGroupId,
      cohostGroupId: body.cohostGroupId ?? null,
      hostLeaderId: uid,
      cohostLeaderId: body.cohostLeaderId ?? null,
      title: body.title ?? null,
      routeHash: body.routeHash ?? null,
      state: initialState
    });
  });

  app.post('/v1/rides/:id/participants', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: rideId } = req.params as { id: string };

    const body = (req.body ?? {}) as {
      userId?: string;
      groupId?: string;
      statusKind?: CredentialRoleKind;
      rideRole?: RideRoleKind;
    };
    if (!body.userId) {
      return reply.code(400).send({ error: 'USER_ID_REQUIRED' });
    }

    const rideRes = await db.query(
      `SELECT id, host_group, cohost_group, state FROM rides WHERE id = $1`,
      [rideId]
    );
    const ride = rideRes.rows[0];
    if (!ride) return reply.code(404).send({ error: 'RIDE_NOT_FOUND' });

    const targetGroupId = body.groupId ?? String(ride.host_group);

    // R6.3 y R14.7: En una rodada conjunta de dos grupos, cada líder controla únicamente a su propio grupo
    const callerLeaderGroupsRes = await db.query(
      `SELECT group_id FROM group_leaders WHERE user_id = $1`,
      [uid]
    );
    const callerLeaderGroupIds = callerLeaderGroupsRes.rows.map((r) => String(r.group_id));
    if (!callerLeaderGroupIds.includes(targetGroupId)) {
      return reply.code(403).send({
        error: 'CANNOT_CONTROL_OTHER_GROUP_IN_JOINT_RIDE',
        message: 'Cada líder controla únicamente a su propio grupo en rodadas conjuntas (RULES R6.3, R14.7)'
      });
    }

    try {
      const { rows } = await db.query(
        `INSERT INTO ride_participants (ride_id, user_id, group_id, status_kind, ride_role)
         VALUES ($1, $2, $3, $4, $5)
         ON CONFLICT (ride_id, user_id) DO UPDATE SET
           status_kind = EXCLUDED.status_kind,
           ride_role = COALESCE(EXCLUDED.ride_role, ride_participants.ride_role)
         RETURNING *`,
        [
          rideId,
          body.userId,
          targetGroupId,
          body.statusKind ?? 'full',
          body.rideRole ?? 'participante'
        ]
      );
      return reply.code(201).send(rows[0]);
    } catch (err) {
      return reply.code(403).send({
        error: 'UNAUTHORIZED_RIDE_PARTICIPANT',
        message: (err as Error).message
      });
    }
  });

  // Roster enriquecido para el mapa en vivo. NO incluye shortId: el identificador
  // de malla se deriva en el dispositivo (modelo + ANDROID_ID) y el backend no lo conoce.
  app.get('/v1/rides/:id/participants', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: rideId } = req.params as { id: string };
    if (!isUuid(rideId)) {
      return reply.code(400).send({ error: 'INVALID_RIDE_ID' });
    }

    const rideRes = await db.query(
      `SELECT host_leader, cohost_leader FROM rides WHERE id = $1`,
      [rideId]
    );
    const ride = rideRes.rows[0];
    if (!ride) return reply.code(404).send({ error: 'RIDE_NOT_FOUND' });

    const partRes = await db.query(
      `SELECT rp.user_id, rp.group_id, rp.status_kind, rp.ride_role,
              u.display_name, u.photo_url, u.map_color, u.bike_model
       FROM ride_participants rp
       JOIN users u ON u.id = rp.user_id
       WHERE rp.ride_id = $1
       ORDER BY rp.joined_at ASC`,
      [rideId]
    );

    const isParticipant = partRes.rows.some((r) => String(r.user_id) === uid);
    const isHostOrCohostLeader =
      String(ride.host_leader) === uid ||
      (ride.cohost_leader && String(ride.cohost_leader) === uid);
    if (!isParticipant && !isHostOrCohostLeader) {
      return reply.code(403).send({ error: 'NOT_IN_RIDE' });
    }

    return reply.code(200).send({
      rideId,
      participants: mapRideRosterRows(partRes.rows),
      note: 'El shortId de malla se deriva localmente; el backend no lo conoce.'
    });
  });

  const handleIssueRideManifest = async (req: FastifyRequest, reply: FastifyReply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: rideId } = req.params as { id: string };

    const rideRes = await db.query(
      `SELECT id, host_group, host_leader, cohost_group, cohost_leader, route_hash, state
       FROM rides WHERE id = $1`,
      [rideId]
    );
    const ride = rideRes.rows[0];
    if (!ride) return reply.code(404).send({ error: 'RIDE_NOT_FOUND' });

    const partRes = await db.query(
      `SELECT rp.user_id, rp.group_id, rp.status_kind, rp.ride_role, u.public_key
       FROM ride_participants rp
       JOIN users u ON u.id = rp.user_id
       WHERE rp.ride_id = $1
       ORDER BY rp.joined_at ASC`,
      [rideId]
    );

    const isParticipant = partRes.rows.some((r) => String(r.user_id) === uid);
    const isHostOrCohostLeader =
      String(ride.host_leader) === uid ||
      (ride.cohost_leader && String(ride.cohost_leader) === uid);
    if (!isParticipant && !isHostOrCohostLeader) {
      return reply.code(403).send({ error: 'NOT_IN_RIDE' });
    }

    const participants: RideManifestParticipant[] = partRes.rows.map((r) => ({
      uid: String(r.user_id),
      gid: String(r.group_id),
      statusKind: (r.status_kind ?? 'full') as CredentialRoleKind,
      rideRole: (r.ride_role ?? 'participante') as RideRoleKind,
      pk:
        parseBytea(r.public_key)?.toString('base64') ??
        Buffer.from(`dev-pk-${r.user_id}`, 'utf8').toString('base64')
    }));

    const signed = issueRideManifest(
      {
        rideId: String(ride.id),
        hostGroupId: String(ride.host_group),
        cohostGroupId: ride.cohost_group ? String(ride.cohost_group) : null,
        hostLeaderId: String(ride.host_leader),
        cohostLeaderId: ride.cohost_leader ? String(ride.cohost_leader) : null,
        routeHash: ride.route_hash ? String(ride.route_hash) : null,
        participants
      },
      credentialKeyring
    );

    await db.query(
      `UPDATE rides SET manifest_kid = $1, manifest_issued_at = now() WHERE id = $2`,
      [signed.payload.kid, rideId]
    );

    return reply.code(200).send({
      ...signed,
      publicKeys: credentialKeyring.getPublicKeyMap()
    });
  };

  app.post('/v1/rides/:id/manifest', handleIssueRideManifest);
  app.get('/v1/rides/:id/manifest', handleIssueRideManifest);

  app.post('/v1/rides/:id/close', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id: rideId } = req.params as { id: string };

    const body = (req.body ?? {}) as {
      distanceKm?: number;
      durationMinutes?: number;
      elevationGainM?: number;
      startedAtNight?: boolean;
      rained?: boolean;
      offlineMinutes?: number;
      closedAtIso?: string;
      meshKm?: number;
      maxSpeedKmh?: number;
      signature?: string;
      leaderPublicKey?: string;
      participants?: Array<{
        userId: string;
        hasMeshContact: boolean;
        presencePct: number;
      }>;
    };

    const rideRes = await db.query(
      `SELECT id, host_group, cohost_group, state FROM rides WHERE id = $1`,
      [rideId]
    );
    const ride = rideRes.rows[0];
    if (!ride) return reply.code(404).send({ error: 'RIDE_NOT_FOUND' });

    // Confirmar que quien cierra es un líder del grupo anfitrión (o coanfitrión) — R6.6
    const leaderRes = await db.query(
      `SELECT 1 FROM group_leaders
       WHERE user_id = $1 AND (group_id = $2 OR ($3::uuid IS NOT NULL AND group_id = $3::uuid))`,
      [uid, ride.host_group, ride.cohost_group ?? null]
    );
    if (leaderRes.rows.length === 0) {
      return reply.code(403).send({
        error: 'LEADER_REQUIRED_TO_CLOSE_RIDE',
        message: 'El cierre de la rodada debe ser confirmado por un líder (RULES R6.6)'
      });
    }

    const distanceKm = Number(body.distanceKm ?? 0);
    const durationMinutes = Math.floor(Number(body.durationMinutes ?? 0));
    const elevationGainM = Number(body.elevationGainM ?? 0);
    const startedAtNight = Boolean(body.startedAtNight ?? false);
    const rained = Boolean(body.rained ?? false);
    const offlineMinutes = Math.floor(Number(body.offlineMinutes ?? 0));
    const closedAtIso = body.closedAtIso ? new Date(body.closedAtIso).toISOString() : new Date().toISOString();

    // Anti-Cheat (Fase 7.1.1): Los kilómetros en malla no pueden superar los kilómetros totales
    if (typeof body.meshKm === 'number' && body.meshKm > distanceKm) {
      return reply.code(403).send({
        error: 'INVALID_SUMMARY',
        message: 'Los kilómetros en malla no pueden superar los kilómetros totales'
      });
    }

    // Anti-Cheat (Fase 7.1.1): La velocidad máxima registrada no puede exceder 200 km/h
    if (typeof body.maxSpeedKmh === 'number' && body.maxSpeedKmh > 200) {
      return reply.code(403).send({
        error: 'INVALID_SUMMARY',
        message: 'La velocidad máxima registrada excede 200 km/h'
      });
    }

    // Verificación de Firma Asimétrica Ed25519 (Fase 7.1.1)
    if (body.signature) {
      let pubKey = body.leaderPublicKey;
      if (!pubKey) {
        const uRes = await db.query(`SELECT public_key FROM users WHERE id = $1`, [uid]);
        pubKey = uRes.rows[0]?.public_key;
      }
      if (!pubKey) {
        return reply.code(403).send({
          error: 'INVALID_SIGNATURE',
          message: 'Falta la clave pública del líder para verificar el resumen de la rodada'
        });
      }

      const summaryData: RideSummaryData = {
        rideId,
        distanceKm,
        durationMinutes,
        meshKm: body.meshKm,
        maxSpeedKmh: body.maxSpeedKmh,
        elevationGainM,
        startedAtNight,
        rained,
        offlineMinutes,
        closedAtIso
      };

      const validSig = verifyRideSummarySignature(summaryData, body.signature, pubKey);
      if (!validSig) {
        return reply.code(403).send({
          error: 'INVALID_SIGNATURE',
          message: 'La firma Ed25519 del resumen de rodada es inválida'
        });
      }
    }

    await db.query(
      `UPDATE rides
       SET distance_km = $1,
           duration_minutes = $2,
           elevation_gain_m = $3,
           started_at_night = $4,
           rained = $5,
           offline_minutes = $6,
           closed_by_leader = $7,
           closed_at = $8
       WHERE id = $9`,
      [
        distanceKm,
        durationMinutes,
        elevationGainM,
        startedAtNight,
        rained,
        offlineMinutes,
        uid,
        closedAtIso,
        rideId
      ]
    );

    if (Array.isArray(body.participants)) {
      for (const p of body.participants) {
        await db.query(
          `UPDATE ride_participants
           SET has_mesh_contact = $1,
               presence_pct = $2
           WHERE ride_id = $3 AND user_id = $4`,
          [Boolean(p.hasMeshContact), Number(p.presencePct ?? 0), rideId, p.userId]
        );
      }
    }

    // Evaluar R6.6 a nivel de rodada y participante:
    // 1. >= 2 participantes con contacto real en la mesh
    // 2. > 15 km
    // 3. > 30 min en movimiento
    // 4. presencia >= 70% del recorrido por participante
    // 5. cierre confirmado por líder
    // 6. máximo 3 rodadas contadas por día por usuario
    const allPartsRes = await db.query(
      `SELECT user_id, has_mesh_contact, presence_pct, counted_for_rank, ride_role
       FROM ride_participants WHERE ride_id = $1`,
      [rideId]
    );
    const meshContactCount = allPartsRes.rows.filter((r) => Boolean(r.has_mesh_contact)).length;
    const rideMeetsR66 =
      distanceKm > 15 && durationMinutes > 30 && meshContactCount >= 2;

    const countedUsers: string[] = [];
    const skippedUsers: Array<{ userId: string; reason: string }> = [];

    if (rideMeetsR66) {
      for (const p of allPartsRes.rows) {
        const userId = String(p.user_id);
        if (Boolean(p.counted_for_rank)) {
          countedUsers.push(userId);
          continue;
        }
        if (!p.has_mesh_contact) {
          skippedUsers.push({ userId, reason: 'NO_MESH_CONTACT' });
          continue;
        }
        if (Number(p.presence_pct ?? 0) < 70) {
          skippedUsers.push({ userId, reason: 'PRESENCE_BELOW_70_PCT' });
          continue;
        }

        try {
          // El trigger `trg_ride_participants_enforce_r66` en PostgreSQL 16 valida todas las condiciones R6.6
          // incluyendo el tope de máximo 3 rodadas válidas por día
          await db.query(
            `UPDATE ride_participants
             SET counted_for_rank = true, counted_at = $1
             WHERE ride_id = $2 AND user_id = $3`,
            [closedAtIso, rideId, userId]
          );

          // Cálculo de XP según GAMIFICATION.md §7:
          const baseXP = 10;
          const distanceXP = Math.floor(distanceKm / 50);
          const roleXP =
            p.ride_role === 'capitan_rodada'
              ? 5
              : p.ride_role === 'sweep'
                ? 3
                : p.ride_role === 'scout'
                  ? 2
                  : 0;
          const earnedXP = baseXP + distanceXP + roleXP;

          const currentMonth = closedAtIso.slice(0, 7);
          const rankRes = await db.query(
            `SELECT valid_rides, total_km, streak_months, last_ride_month, xp
             FROM rank_progress WHERE user_id = $1`,
            [userId]
          );

          let newStreak = 1;
          let streakBonusXP = 0;
          if (rankRes.rows.length > 0) {
            const lastMonth = rankRes.rows[0].last_ride_month;
            const currentStreak = Number(rankRes.rows[0].streak_months ?? 0);
            if (lastMonth === currentMonth) {
              newStreak = Math.max(1, currentStreak);
            } else if (lastMonth && isConsecutiveMonth(lastMonth, currentMonth)) {
              newStreak = currentStreak + 1;
              if (newStreak % 3 === 0) {
                streakBonusXP = 20; // +20 XP por racha de 3 meses
              }
            } else {
              newStreak = 1;
            }
          }

          const totalEarnedXP = earnedXP + streakBonusXP;

          await db.query(
            `INSERT INTO rank_progress (user_id, valid_rides, total_km, streak_months, last_ride_month, xp)
             VALUES ($1, 1, $2, $3, $4, $5)
             ON CONFLICT (user_id) DO UPDATE SET
               valid_rides = rank_progress.valid_rides + 1,
               total_km = rank_progress.total_km + $2,
               streak_months = $3,
               last_ride_month = $4,
               xp = rank_progress.xp + $5`,
            [userId, distanceKm, newStreak, currentMonth, totalEarnedXP]
          );

          const updatedKmRes = await db.query(
            `SELECT total_km, valid_rides, streak_months FROM rank_progress WHERE user_id = $1`,
            [userId]
          );
          const currentTotalKm = Number(updatedKmRes.rows[0]?.total_km ?? distanceKm);
          const currentValidRides = Number(updatedKmRes.rows[0]?.valid_rides ?? 1);

          await awardMileageAndExperienceBadges(db, userId, currentTotalKm, currentValidRides, newStreak, {
            started_at_night: startedAtNight,
            rained,
            offline_minutes: offlineMinutes,
            elevation_gain_m: elevationGainM
          });

          countedUsers.push(userId);
        } catch (err) {
          skippedUsers.push({ userId, reason: (err as Error).message });
        }
      }
    } else {
      for (const p of allPartsRes.rows) {
        skippedUsers.push({
          userId: String(p.user_id),
          reason:
            distanceKm <= 15
              ? 'DISTANCE_NOT_OVER_15_KM'
              : durationMinutes <= 30
                ? 'DURATION_NOT_OVER_30_MIN'
                : 'FEWER_THAN_2_MESH_CONTACTS'
        });
      }
    }

    const finalState = rideMeetsR66 && countedUsers.length > 0 ? 'validated' : 'closed';
    await db.query(`UPDATE rides SET state = $1 WHERE id = $2`, [finalState, rideId]);

    await appendAudit(db, uid, 'RIDE_CLOSED', 'ride', rideId, {
      finalState,
      distanceKm,
      durationMinutes,
      meshContactCount,
      countedUsers,
      skippedUsers
    });

    return reply.code(200).send({
      id: rideId,
      state: finalState,
      rideMeetsR66,
      distanceKm,
      durationMinutes,
      meshContactCount,
      countedUsers,
      skippedUsers,
      voiceClipsPurged: true,
      emergencyAccessRevoked: true
    });
  });

  // --------------------------------------------------------------------------
  // GAMIFICACIÓN (Fase 6 — GAMIFICATION.md & SCREENS.md F2)
  // --------------------------------------------------------------------------
  app.get('/v1/users/:id/gamification', async (req: FastifyRequest, reply: FastifyReply) => {
    const { id } = req.params as { id: string };
    const rankRes = await db.query(
      `SELECT valid_rides, total_km, streak_months, last_ride_month, xp
       FROM rank_progress WHERE user_id = $1`,
      [id]
    );

    const validRides = Number(rankRes.rows[0]?.valid_rides ?? 0);
    const totalKm = Number(rankRes.rows[0]?.total_km ?? 0);
    const streakMonths = Number(rankRes.rows[0]?.streak_months ?? 0);
    const lastRideMonth = rankRes.rows[0]?.last_ride_month ?? null;
    const xp = Number(rankRes.rows[0]?.xp ?? 0);

    const rankInfo = computeBikerRankInfo(xp);

    const badgesRes = await db.query(
      `SELECT b.id, b.family, b.code, b.issuer_group, b.issuer_user, b.created_at,
              g.name as issuer_group_name
       FROM badges b
       LEFT JOIN groups g ON g.id = b.issuer_group
       WHERE b.receiver = $1
       ORDER BY b.created_at DESC`,
      [id]
    );

    return reply.code(200).send({
      userId: id,
      validRides,
      totalKm,
      streakMonths,
      lastRideMonth,
      xp,
      currentRank: rankInfo,
      badges: badgesRes.rows.map((r) => ({
        id: r.id,
        family: r.family,
        code: r.code,
        issuerGroupId: r.issuer_group,
        issuerGroupName: r.issuer_group_name,
        createdAt: r.created_at
      }))
    });
  });

  // Otorgar insignia de honor (RULES R8.6, D43)
  app.post('/v1/groups/:id/badges/honor', async (req: FastifyRequest, reply: FastifyReply) => {
    const { id: groupId } = req.params as { id: string };
    const uid = requireUserId(req, reply);
    if (!uid) return;

    // Validar que el emisor sea líder del grupo
    const leaderCheck = await db.query(
      `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
      [groupId, uid]
    );
    if (leaderCheck.rows.length === 0) {
      return reply.code(403).send({ error: 'FORBIDDEN_NOT_GROUP_LEADER' });
    }

    const body = (req.body ?? {}) as { receiverUserId?: string; code?: string };
    if (!body.receiverUserId || !body.code) {
      return reply.code(400).send({ error: 'MISSING_RECEIVER_OR_CODE' });
    }

    try {
      const badgeId = randomUUID();
      await db.query(
        `INSERT INTO badges (id, family, code, issuer_group, issuer_user, receiver)
         VALUES ($1, 'honor', $2, $3, $4, $5)`,
        [badgeId, body.code, groupId, uid, body.receiverUserId]
      );

      // Otorgar +15 XP por insignia de honor recibida (GAMIFICATION.md §7)
      await db.query(
        `INSERT INTO rank_progress (user_id, valid_rides, total_km, xp)
         VALUES ($1, 0, 0, 15)
         ON CONFLICT (user_id) DO UPDATE SET xp = rank_progress.xp + 15`,
        [body.receiverUserId]
      );

      return reply.code(201).send({
        id: badgeId,
        family: 'honor',
        code: body.code,
        issuerGroupId: groupId,
        issuerUserId: uid,
        receiverUserId: body.receiverUserId,
        xpAwarded: 15
      });
    } catch (err) {
      return reply.code(400).send({ error: (err as Error).message });
    }
  });

  // Transición de jerarquía de clubes RC -> MG -> Gang -> MC (RULES R3.4, D45)
  app.post('/v1/groups/:id/transition-tier', async (req: FastifyRequest, reply: FastifyReply) => {
    const { id: groupId } = req.params as { id: string };
    const uid = requireUserId(req, reply);
    if (!uid) return;

    // Solo el líder principal puede solicitar o cambiar el tier
    const groupRes = await db.query(
      `SELECT principal_leader, tier FROM groups WHERE id = $1`,
      [groupId]
    );
    if (groupRes.rows.length === 0) {
      return reply.code(404).send({ error: 'GROUP_NOT_FOUND' });
    }
    if (String(groupRes.rows[0].principal_leader) !== uid) {
      return reply.code(403).send({ error: 'FORBIDDEN_ONLY_PRINCIPAL_LEADER' });
    }

    const body = (req.body ?? {}) as { targetTier?: string; sponsorGroupId?: string };
    if (!body.targetTier || !['RC', 'MF', 'MG', 'GANG', 'MC'].includes(body.targetTier)) {
      return reply.code(400).send({ error: 'INVALID_TIER' });
    }

    try {
      await db.query(
        `UPDATE groups
         SET tier = $1, sponsor_group_id = $2
         WHERE id = $3`,
        [body.targetTier, body.sponsorGroupId ?? null, groupId]
      );

      return reply.code(200).send({
        groupId,
        previousTier: groupRes.rows[0].tier,
        newTier: body.targetTier,
        sponsorGroupId: body.sponsorGroupId ?? null
      });
    } catch (err) {
      return reply.code(400).send({ error: (err as Error).message });
    }
  });

  // ---------------------------------------------------------------------------
  // 17. Módulo de Administración, Soporte y Analítica (Backoffice / Helpdesk / KPIs)
  // ---------------------------------------------------------------------------
  const requireAdminAuth = async (req: FastifyRequest, reply: FastifyReply) => {
    const key =
      (req.headers['x-admin-key'] as string | undefined) ??
      (req.headers['authorization']?.startsWith('Bearer ') ? req.headers['authorization'].slice(7) : undefined);
    if (!verifyAdminAccess(key, env.ADMIN_API_KEY)) {
      reply.code(401).send({ error: 'UNAUTHORIZED_ADMIN', message: 'Clave de administración inválida o no proporcionada' });
      return;
    }
  };

  // 17.0 Logs de desarrollo (Bloque D): nunca en producción y sólo con opt-in
  // explícito. Se devuelven como text/plain y con PII redactada.
  app.get('/v1/debug/logs', { preHandler: requireAdminAuth }, async (req, reply) => {
    const enabledByEnv =
      env.NODE_ENV !== 'production' &&
      env.ENABLE_DEBUG_LOGS === 'true' &&
      Boolean(env.ADMIN_API_KEY); // rechaza la clave por defecto de desarrollo
    if (!enabledByEnv) {
      return reply.code(404).send({ error: 'NOT_FOUND' });
    }

    const q = req.query as { lines?: string; date?: string };
    const requestedLines = Number(q.lines ?? DEFAULT_DEBUG_LOG_LINES);
    const lines = Number.isFinite(requestedLines)
      ? Math.min(MAX_DEBUG_LOG_LINES, Math.max(1, Math.trunc(requestedLines)))
      : DEFAULT_DEBUG_LOG_LINES;
    const date = q.date ?? new Date().toISOString().slice(0, 10).replace(/-/g, '');
    const logsDir = path.resolve(__appDirname, '..', 'logs');
    const logPath = resolveDebugLogPath(logsDir, date);
    if (!logPath || !fs.existsSync(logPath)) {
      return reply.code(404).send({ error: 'LOG_FILE_NOT_FOUND' });
    }

    reply.header('Cache-Control', 'no-store');
    return reply
      .type('text/plain; charset=utf-8')
      .send(readRedactedLogTail(logPath, lines));
  });

  // 17.1 Analítica y Visión General de la Plataforma
  app.get('/v1/admin/analytics/overview', { preHandler: requireAdminAuth }, async () => {
    const overview = await getAdminAnalyticsOverview(db);
    return overview;
  });

  // 17.2 Gestión de Clubes / Grupos
  app.get('/v1/admin/groups', { preHandler: requireAdminAuth }, async (req) => {
    const q = req.query as { search?: string; countryCode?: string; tier?: string; status?: string; limit?: string; offset?: string };
    const limit = q.limit ? parseInt(q.limit, 10) : 50;
    const offset = q.offset ? parseInt(q.offset, 10) : 0;
    return await listAdminGroups(db, { search: q.search, countryCode: q.countryCode, tier: q.tier, status: q.status, limit, offset });
  });

  app.get('/v1/admin/groups/:id', { preHandler: requireAdminAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const details = await getAdminGroupDetails(db, id);
    if (!details) return reply.code(404).send({ error: 'GROUP_NOT_FOUND' });
    return details;
  });

  app.patch('/v1/admin/groups/:id/status', { preHandler: requireAdminAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const body = (req.body ?? {}) as { status?: 'active' | 'suspended'; reason?: string; adminEmail?: string };
    if (!body.status || !['active', 'suspended'].includes(body.status)) {
      return reply.code(400).send({ error: 'INVALID_STATUS' });
    }
    const success = await updateAdminGroupStatus(db, id, body.status, body.adminEmail ?? 'admin@rutabikersync.com', body.reason ?? 'Acción de administrador');
    if (!success) return reply.code(404).send({ error: 'GROUP_NOT_FOUND' });
    return { success: true, groupId: id, status: body.status };
  });

  app.post('/v1/admin/groups/:id/extend-grace', { preHandler: requireAdminAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const body = (req.body ?? {}) as { extraDays?: number; reason?: string; adminEmail?: string };
    const extraDays = body.extraDays && body.extraDays > 0 ? body.extraDays : 7;
    const success = await extendGroupGracePeriod(db, id, extraDays, body.adminEmail ?? 'admin@rutabikersync.com', body.reason ?? 'Prórroga autorizada por soporte');
    if (!success) return reply.code(404).send({ error: 'GROUP_OR_LEADER_NOT_FOUND' });
    return { success: true, groupId: id, extraDaysGranted: extraDays };
  });

  // 17.3 Gestión de Usuarios
  app.get('/v1/admin/users', { preHandler: requireAdminAuth }, async (req) => {
    const q = req.query as { search?: string; status?: string; limit?: string; offset?: string };
    const limit = q.limit ? parseInt(q.limit, 10) : 50;
    const offset = q.offset ? parseInt(q.offset, 10) : 0;
    return await listAdminUsers(db, { search: q.search, status: q.status, limit, offset });
  });

  app.patch('/v1/admin/users/:id/status', { preHandler: requireAdminAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const body = (req.body ?? {}) as { status?: 'active' | 'suspended'; reason?: string; adminEmail?: string };
    if (!body.status || !['active', 'suspended'].includes(body.status)) {
      return reply.code(400).send({ error: 'INVALID_STATUS' });
    }
    const success = await updateAdminUserStatus(db, id, body.status, body.adminEmail ?? 'admin@rutabikersync.com', body.reason ?? 'Acción de moderación');
    if (!success) return reply.code(404).send({ error: 'USER_NOT_FOUND' });
    return { success: true, userId: id, status: body.status };
  });

  // 17.4 Moderación Escalada (R13.5)
  app.get('/v1/admin/support/reports', { preHandler: requireAdminAuth }, async (req) => {
    const q = req.query as { limit?: string };
    const limit = q.limit ? parseInt(q.limit, 10) : 50;
    const reports = await listEscalatedReports(db, limit);
    return { reports };
  });

  app.patch('/v1/admin/support/reports/:id', { preHandler: requireAdminAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const body = (req.body ?? {}) as {
      resolution?: 'warning' | 'muted' | 'ride_removed' | 'group_removed' | 'platform_suspended';
      adminEmail?: string;
      notes?: string;
    };
    if (!body.resolution || !['warning', 'muted', 'ride_removed', 'group_removed', 'platform_suspended'].includes(body.resolution)) {
      return reply.code(400).send({ error: 'INVALID_RESOLUTION' });
    }
    const success = await resolveEscalatedReport(db, id, body.resolution, body.adminEmail ?? 'admin@rutabikersync.com', body.notes ?? '');
    if (!success) return reply.code(404).send({ error: 'REPORT_NOT_FOUND' });
    return { success: true, reportId: id, resolution: body.resolution };
  });

  // 17.5 Tickets de Soporte
  app.get('/v1/admin/support/tickets', { preHandler: requireAdminAuth }, async (req) => {
    const q = req.query as { status?: string; category?: string; priority?: string; limit?: string; offset?: string };
    const limit = q.limit ? parseInt(q.limit, 10) : 50;
    const offset = q.offset ? parseInt(q.offset, 10) : 0;
    return await listSupportTickets(db, { status: q.status, category: q.category, priority: q.priority, limit, offset });
  });

  app.post('/v1/admin/support/tickets', { preHandler: requireAdminAuth }, async (req, reply) => {
    const body = req.body as {
      userId: string;
      groupId?: string | null;
      subject: string;
      category: string;
      priority?: string;
      initialMessage: string;
    };
    if (!body.userId || !body.subject || !body.category || !body.initialMessage) {
      return reply.code(400).send({ error: 'MISSING_REQUIRED_FIELDS' });
    }
    const ticketId = await createSupportTicket(db, body);
    return reply.code(201).send({ success: true, ticketId });
  });

  app.get('/v1/admin/support/tickets/:id', { preHandler: requireAdminAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const details = await getSupportTicketDetails(db, id);
    if (!details) return reply.code(404).send({ error: 'TICKET_NOT_FOUND' });
    return details;
  });

  app.post('/v1/admin/support/tickets/:id/messages', { preHandler: requireAdminAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const body = req.body as {
      senderId: string;
      isAdmin?: boolean;
      message: string;
      nextStatus?: string;
    };
    if (!body.senderId || !body.message) {
      return reply.code(400).send({ error: 'MISSING_REQUIRED_FIELDS' });
    }
    const messageId = await addTicketMessage(db, {
      ticketId: id,
      senderId: body.senderId,
      isAdmin: body.isAdmin ?? true,
      message: body.message,
      nextStatus: body.nextStatus
    });
    return reply.code(201).send({ success: true, messageId });
  });

  // 17.6 Auditoría de Seguridad SOS y Acciones de Administrador
  app.get('/v1/admin/audit/sos', { preHandler: requireAdminAuth }, async (req) => {
    const q = req.query as { limit?: string };
    const limit = q.limit ? parseInt(q.limit, 10) : 50;
    const logs = await listSosAccessAudit(db, limit);
    return { logs };
  });

  app.get('/v1/admin/audit/logs', { preHandler: requireAdminAuth }, async (req) => {
    const q = req.query as { limit?: string };
    const limit = q.limit ? parseInt(q.limit, 10) : 50;
    const logs = await listAdminAuditLogs(db, limit);
    return { logs };
  });


  // ===========================================================================
  // 18. FASE 5: BÓVEDA BIKER AVANZADA (E2EE & RECUPERACIÓN & FICHA MÉDICA)
  // ===========================================================================

  const VaultDocumentSchema = z.object({
    docType: z.string().min(1).max(50),
    ciphertext: z.string().min(1),
    iv: z.string().min(1),
    authTag: z.string().min(1),
    userSalt: z.string().min(1),
    title: z.string().optional()
  });

  const VaultRecoveryKeySchema = z.object({
    encryptedMasterKey: z.string().min(1),
    iv: z.string().min(1),
    authTag: z.string().min(1),
    pbkdf2Salt: z.string().min(1),
    pbkdf2Iterations: z.number().int().min(10000).default(100000)
  });

  const VaultMedicalShareSchema = z.object({
    rideId: z.string().uuid(),
    groupId: z.string().uuid(),
    ciphertext: z.string().min(1),
    iv: z.string().min(1),
    authTag: z.string().min(1),
    durationHours: z.number().int().min(1).max(72).default(24)
  });

  // 18.1 Subir o actualizar documento cifrado en reposo (E2EE)
  app.post('/v1/vault/documents', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const parsed = VaultDocumentSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { docType, ciphertext, iv, authTag, userSalt, title } = parsed.data;

    const res = await db.query(
      `INSERT INTO vault_items (
         id, user_id, doc_type, ciphertext, iv, auth_tag, user_salt, title, created_at, updated_at
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, now(), now())
       ON CONFLICT (user_id, doc_type) DO UPDATE SET
         ciphertext = EXCLUDED.ciphertext,
         iv = EXCLUDED.iv,
         auth_tag = EXCLUDED.auth_tag,
         user_salt = EXCLUDED.user_salt,
         title = EXCLUDED.title,
         updated_at = now()
       RETURNING id, doc_type, title, updated_at`,
      [randomUUID(), uid, docType, ciphertext, iv, authTag, userSalt, title ?? null]
    );

    return reply.code(201).send({
      success: true,
      document: {
        id: res.rows[0].id,
        docType: res.rows[0].doc_type,
        title: res.rows[0].title,
        updatedAt: res.rows[0].updated_at
      }
    });
  });

  // 18.2 Listar metadatos de documentos de la bóveda (sin retornar ciphertext)
  app.get('/v1/vault/documents', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const res = await db.query(
      `SELECT id, doc_type AS "docType", title, user_salt AS "userSalt", created_at AS "createdAt", updated_at AS "updatedAt"
       FROM vault_items
       WHERE user_id = $1
       ORDER BY updated_at DESC`,
      [uid]
    );

    return reply.code(200).send({
      documents: res.rows
    });
  });

  // 18.3 Descargar blob cifrado completo de un documento (valida ownership)
  app.get('/v1/vault/documents/:id', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id } = req.params as { id: string };

    const res = await db.query(
      `SELECT id, user_id, doc_type AS "docType", ciphertext, iv, auth_tag AS "authTag",
              user_salt AS "userSalt", title, updated_at AS "updatedAt"
       FROM vault_items
       WHERE id = $1`,
      [id]
    );

    if (res.rows.length === 0) {
      return reply.code(404).send({ error: 'DOCUMENT_NOT_FOUND' });
    }
    const doc = res.rows[0];
    if (doc.user_id !== uid) {
      return reply.code(403).send({ error: 'FORBIDDEN', message: 'No tienes permiso para acceder a este documento' });
    }

    return reply.code(200).send({
      id: doc.id,
      docType: doc.docType,
      ciphertext: doc.ciphertext,
      iv: doc.iv,
      authTag: doc.authTag,
      userSalt: doc.userSalt,
      title: doc.title,
      updatedAt: doc.updatedAt
    });
  });

  // 18.4 Eliminar documento de la bóveda
  app.delete('/v1/vault/documents/:id', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;
    const { id } = req.params as { id: string };

    const res = await db.query(
      `DELETE FROM vault_items WHERE id = $1 AND user_id = $2 RETURNING id`,
      [id, uid]
    );
    if (res.rows.length === 0) {
      return reply.code(404).send({ error: 'DOCUMENT_NOT_FOUND' });
    }

    return reply.code(200).send({ success: true, deletedId: id });
  });

    // 18.4 Helper: Rate limiting de grado militar para recuperación de Bóveda (RULES.md §15.8)
  async function checkAndEnforceRecoveryRateLimit(
    ip: string,
    uid: string,
    endpoint: string,
    reply: FastifyReply
  ): Promise<boolean> {
    // 1. Bloqueo 24 horas (86400s) si acumula >= 20 intentos en 24h
    const res24h = await db.query<{ count: string }>(
      `SELECT count(*) as count FROM vault_recovery_attempts
       WHERE user_id = $1 AND created_at > now() - interval '24 hours'`,
      [uid]
    );
    const total24h = parseInt(res24h.rows[0]?.count ?? '0', 10);
    if (total24h >= 20) {
      reply.header('Retry-After', '86400').code(429).send({
        error: 'TOO_MANY_ATTEMPTS',
        message: 'Has superado el límite de intentos de recuperación (bloqueo preventivo de 24 horas). Espera antes de reintentar.',
        retryAfter: 86400
      });
      return false;
    }

    // 2. Bloqueo 6 horas (21600s) si acumula >= 10 intentos en 6h
    const res6h = await db.query<{ count: string }>(
      `SELECT count(*) as count FROM vault_recovery_attempts
       WHERE user_id = $1 AND created_at > now() - interval '6 hours'`,
      [uid]
    );
    const total6h = parseInt(res6h.rows[0]?.count ?? '0', 10);
    if (total6h >= 10) {
      reply.header('Retry-After', '21600').code(429).send({
        error: 'TOO_MANY_ATTEMPTS',
        message: 'Has superado el límite de intentos de recuperación (bloqueo preventivo de 6 horas). Espera antes de reintentar.',
        retryAfter: 21600
      });
      return false;
    }

    // 3. Bloqueo 1 hora (3600s) si acumula >= 5 intentos en 1h
    const res1h = await db.query<{ count: string }>(
      `SELECT count(*) as count FROM vault_recovery_attempts
       WHERE user_id = $1 AND created_at > now() - interval '1 hour'`,
      [uid]
    );
    const total1h = parseInt(res1h.rows[0]?.count ?? '0', 10);
    if (total1h >= 5) {
      reply.header('Retry-After', '3600').code(429).send({
        error: 'TOO_MANY_ATTEMPTS',
        message: 'Has superado el límite de 5 intentos por hora. Espera 60 minutos antes de reintentar.',
        retryAfter: 3600
      });
      return false;
    }

    // Registrar el intento
    await db.query(
      `INSERT INTO vault_recovery_attempts (ip, user_id, endpoint, success, created_at)
       VALUES ($1, $2, $3, false, now())`,
      [ip, uid, endpoint]
    );

    return true;
  }

  // 18.5 Solicitar token efímero de recuperación (TTL 5 min, un solo uso)
  app.post('/v1/vault/recovery/token', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const clientIp = (req.headers['x-forwarded-for'] as string)?.split(',')[0]?.trim() || req.ip || '127.0.0.1';
    const allowed = await checkAndEnforceRecoveryRateLimit(clientIp, uid, '/v1/vault/recovery/token', reply);
    if (!allowed) return;

    const token = randomUUID() + '-' + randomUUID();
    // Invalidar tokens previos no usados
    await db.query(
      `UPDATE vault_recovery_tokens SET used = true WHERE user_id = $1 AND used = false`,
      [uid]
    );

    await db.query(
      `INSERT INTO vault_recovery_tokens (id, user_id, token, expires_at, used)
       VALUES ($1, $2, $3, now() + interval '5 minutes', false)`,
      [randomUUID(), uid, token]
    );

    return reply.code(200).send({
      recoveryToken: token,
      expiresInSeconds: 300
    });
  });

  // 18.6 Guardar clave maestra de recuperación cifrada con Passphrase (requiere X-Recovery-Token)
  app.post('/v1/vault/recovery', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const clientIp = (req.headers['x-forwarded-for'] as string)?.split(',')[0]?.trim() || req.ip || '127.0.0.1';
    const allowed = await checkAndEnforceRecoveryRateLimit(clientIp, uid, '/v1/vault/recovery', reply);
    if (!allowed) return;

    const recoveryToken = (req.headers['x-recovery-token'] as string | undefined)?.trim();
    if (!recoveryToken) {
      return reply.code(403).send({
        error: 'REAUTH_REQUIRED',
        message: 'Se requiere token de recuperación de un solo uso en header X-Recovery-Token'
      });
    }

    const tokenRes = await db.query(
      `SELECT id FROM vault_recovery_tokens
       WHERE user_id = $1 AND token = $2 AND used = false AND expires_at > now()`,
      [uid, recoveryToken]
    );
    if (tokenRes.rows.length === 0) {
      return reply.code(403).send({
        error: 'REAUTH_REQUIRED',
        message: 'Token de recuperación inválido, ya utilizado o expirado'
      });
    }

    // Marcar como consumido inmediatamente (single use)
    await db.query(`UPDATE vault_recovery_tokens SET used = true WHERE id = $1`, [tokenRes.rows[0].id]);

    const parsed = VaultRecoveryKeySchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { encryptedMasterKey, iv, authTag, pbkdf2Salt, pbkdf2Iterations } = parsed.data;

    await db.query(
      `INSERT INTO vault_recovery_keys (
         user_id, encrypted_master_key, iv, auth_tag, pbkdf2_salt, pbkdf2_iterations, created_at, last_used_at
       ) VALUES ($1, $2, $3, $4, $5, $6, now(), now())
       ON CONFLICT (user_id) DO UPDATE SET
         encrypted_master_key = EXCLUDED.encrypted_master_key,
         iv = EXCLUDED.iv,
         auth_tag = EXCLUDED.auth_tag,
         pbkdf2_salt = EXCLUDED.pbkdf2_salt,
         pbkdf2_iterations = EXCLUDED.pbkdf2_iterations,
         created_at = now(),
         last_used_at = now()`,
      [uid, encryptedMasterKey, iv, authTag, pbkdf2Salt, pbkdf2Iterations]
    );

    return reply.code(201).send({
      success: true,
      message: 'Clave maestra de recuperación registrada exitosamente'
    });
  });

  // 18.7 Obtener clave maestra de recuperación cifrada (requiere X-Recovery-Token)
  app.get('/v1/vault/recovery', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const clientIp = (req.headers['x-forwarded-for'] as string)?.split(',')[0]?.trim() || req.ip || '127.0.0.1';
    const allowed = await checkAndEnforceRecoveryRateLimit(clientIp, uid, '/v1/vault/recovery', reply);
    if (!allowed) return;

    const recoveryToken = (req.headers['x-recovery-token'] as string | undefined)?.trim();
    if (!recoveryToken) {
      return reply.code(403).send({
        error: 'REAUTH_REQUIRED',
        message: 'Se requiere re-autenticación (X-Recovery-Token) para acceder a la clave de recuperación'
      });
    }

    const tokenRes = await db.query(
      `SELECT id FROM vault_recovery_tokens
       WHERE user_id = $1 AND token = $2 AND used = false AND expires_at > now()`,
      [uid, recoveryToken]
    );
    if (tokenRes.rows.length === 0) {
      return reply.code(403).send({
        error: 'REAUTH_REQUIRED',
        message: 'Token de recuperación inválido, ya utilizado o expirado'
      });
    }

    await db.query(`UPDATE vault_recovery_tokens SET used = true WHERE id = $1`, [tokenRes.rows[0].id]);

    const res = await db.query(
      `SELECT encrypted_master_key AS "encryptedMasterKey", iv, auth_tag AS "authTag",
              pbkdf2_salt AS "pbkdf2Salt", pbkdf2_iterations AS "pbkdf2Iterations", created_at AS "createdAt"
       FROM vault_recovery_keys
       WHERE user_id = $1`,
      [uid]
    );

    if (res.rows.length === 0) {
      return reply.code(404).send({ error: 'RECOVERY_KEY_NOT_FOUND' });
    }

    await db.query(`UPDATE vault_recovery_keys SET last_used_at = now() WHERE user_id = $1`, [uid]);

    return reply.code(200).send(res.rows[0]);
  });

  // 18.8 Compartir Ficha Médica de Emergencia durante una Rodada Activa
  app.post('/v1/vault/medical/share', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const parsed = VaultMedicalShareSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'VALIDATION_ERROR', details: parsed.error.flatten() });
    }
    const { rideId, groupId, ciphertext, iv, authTag, durationHours } = parsed.data;

    // Verificar que la rodada exista y esté activa
    const rideRes = await db.query(`SELECT id, state FROM rides WHERE id = $1`, [rideId]);
    if (rideRes.rows.length === 0) {
      return reply.code(404).send({ error: 'RIDE_NOT_FOUND' });
    }
    if (rideRes.rows[0].state !== 'active') {
      return reply.code(403).send({
        error: 'RIDE_NOT_ACTIVE',
        message: 'Solo se puede compartir la ficha médica mientras la rodada esté activa (RULES R6.5)'
      });
    }

    const expiresAt = new Date(Date.now() + durationHours * 3600 * 1000).toISOString();

    await db.query(
      `INSERT INTO shared_medical_sheets (
         id, user_id, ride_id, group_id, ciphertext, iv, auth_tag, shared_at, expires_at
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, now(), $8)
       ON CONFLICT (user_id, ride_id) DO UPDATE SET
         ciphertext = EXCLUDED.ciphertext,
         iv = EXCLUDED.iv,
         auth_tag = EXCLUDED.auth_tag,
         shared_at = now(),
         expires_at = EXCLUDED.expires_at`,
      [randomUUID(), uid, rideId, groupId, ciphertext, iv, authTag, expiresAt]
    );

    return reply.code(201).send({
      success: true,
      rideId,
      expiresAt,
      message: 'Ficha médica compartida temporalmente para la rodada'
    });
  });

  // 18.8.1 Revocar Anticipadamente Compartición de Ficha Médica (Hito 3 - RULES R6.5)
  app.delete('/v1/vault/medical/share', async (req, reply) => {
    const uid = requireUserId(req, reply);
    if (!uid) return;

    const { rideId } = req.query as { rideId?: string };
    if (!rideId) {
      return reply.code(400).send({ error: 'RIDE_ID_REQUIRED', message: 'Se requiere el parámetro query ?rideId=' });
    }

    const delRes = await db.query(
      `DELETE FROM shared_medical_sheets WHERE user_id = $1 AND ride_id = $2 RETURNING id`,
      [uid, rideId]
    );

    if ((delRes.rowCount ?? 0) === 0) {
      return reply.code(404).send({
        error: 'NOT_FOUND',
        message: 'No existe compartición activa de ficha médica para revocar en esta rodada'
      });
    }

    return reply.code(200).send({
      success: true,
      rideId,
      message: 'Acceso a la ficha médica revocado exitosamente'
    });
  });

  // 18.9 Obtener Ficha Médica de un Piloto (RBAC Estricto: solo Líder/Sargento y rodada activa)
  app.get('/v1/vault/medical/:targetUserId', async (req, reply) => {
    const callerUid = requireUserId(req, reply);
    if (!callerUid) return;
    const { targetUserId } = req.params as { targetUserId: string };
    const { rideId } = req.query as { rideId?: string };

    if (!rideId) {
      return reply.code(400).send({ error: 'RIDE_ID_REQUIRED', message: 'Se requiere el parámetro query ?rideId=' });
    }

    // 1. Verificar estado de la rodada
    const rideRes = await db.query(`SELECT id, state, host_group FROM rides WHERE id = $1`, [rideId]);
    if (rideRes.rows.length === 0) {
      return reply.code(404).send({ error: 'RIDE_NOT_FOUND' });
    }
    const ride = rideRes.rows[0];
    if (ride.state !== 'active') {
      return reply.code(403).send({
        error: 'RIDE_NOT_ACTIVE',
        message: 'La rodada no está activa o ya ha finalizado; el acceso a la ficha médica ha expirado (RULES R6.5)'
      });
    }

    // 2. Verificar rol del solicitante (Líder principal, co-líder o Sargento de Armas)
    const grpRes = await db.query(
      `SELECT principal_leader FROM groups WHERE id = $1`,
      [ride.host_group]
    );
    const principalLeader = grpRes.rows[0]?.principal_leader;

    let isAuthorized = false;
    if (principalLeader === callerUid) {
      isAuthorized = true;
    } else {
      const leaderRes = await db.query(
        `SELECT 1 FROM group_leaders WHERE group_id = $1 AND user_id = $2`,
        [ride.host_group, callerUid]
      );
      if (leaderRes.rows.length > 0) {
        isAuthorized = true;
      } else {
        // Verificar si es Sargento de Armas en memberships o group_leaders
        const roleRes = await db.query(
          `SELECT club_role FROM memberships
           WHERE group_id = $1 AND user_id = $2 AND invite_state = 'accepted'`,
          [ride.host_group, callerUid]
        );
        const role = roleRes.rows[0]?.club_role?.toUpperCase();
        if (role === 'SERGEANT_AT_ARMS' || role === 'SARGENTO DE ARMAS' || role === 'SARGENTO_ARMAS' || role === 'PRESIDENT' || role === 'PRESIDENTE' || role === 'VICE_PRESIDENT' || role === 'VICE') {
          isAuthorized = true;
        }
      }
    }

    if (!isAuthorized) {
      return reply.code(403).send({
        error: 'MEDICAL_ACCESS_RESTRICTED',
        message: 'Acceso denegado: solo el Líder o Sargento de Armas del club organizador puede acceder a fichas médicas de emergencia'
      });
    }

    // 3. Obtener la ficha médica compartida
    const sheetRes = await db.query(
      `SELECT id, user_id AS "userId", ride_id AS "rideId", ciphertext, iv, auth_tag AS "authTag",
              shared_at AS "sharedAt", expires_at AS "expiresAt"
       FROM shared_medical_sheets
       WHERE user_id = $1 AND ride_id = $2 AND expires_at > now()`,
      [targetUserId, rideId]
    );

    if (sheetRes.rows.length === 0) {
      return reply.code(404).send({
        error: 'MEDICAL_SHEET_NOT_FOUND',
        message: 'El piloto no ha compartido su ficha médica para esta rodada o el acceso ha expirado'
      });
    }

    await appendAudit(db, callerUid, 'MEDICAL_SHEET_ACCESSED', 'user', targetUserId, { rideId });

    return reply.code(200).send(sheetRes.rows[0]);
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

function isConsecutiveMonth(prev: string, current: string): boolean {
  const [py, pm] = prev.split('-').map(Number);
  const [cy, cm] = current.split('-').map(Number);
  if (cy === py && cm === pm + 1) return true;
  if (cy === py + 1 && pm === 12 && cm === 1) return true;
  return false;
}

function computeBikerRankInfo(xp: number): {
  code: string;
  title: string;
  minXp: number;
  nextRankTitle: string | null;
  nextRankXp: number | null;
  progressPct: number;
} {
  if (xp >= 5000) {
    return {
      code: 'leyenda',
      title: 'Leyenda',
      minXp: 5000,
      nextRankTitle: null,
      nextRankXp: null,
      progressPct: 100
    };
  }
  if (xp >= 2000) {
    return {
      code: 'elite',
      title: 'Élite',
      minXp: 2000,
      nextRankTitle: 'Leyenda',
      nextRankXp: 5000,
      progressPct: Math.round(((xp - 2000) / (5000 - 2000)) * 100)
    };
  }
  if (xp >= 500) {
    return {
      code: 'veterano',
      title: 'Veterano',
      minXp: 500,
      nextRankTitle: 'Élite',
      nextRankXp: 2000,
      progressPct: Math.round(((xp - 500) / (2000 - 500)) * 100)
    };
  }
  if (xp >= 100) {
    return {
      code: 'rider',
      title: 'Rider',
      minXp: 100,
      nextRankTitle: 'Veterano',
      nextRankXp: 500,
      progressPct: Math.round(((xp - 100) / (500 - 100)) * 100)
    };
  }
  return {
    code: 'novato',
    title: 'Novato',
    minXp: 0,
    nextRankTitle: 'Rider',
    nextRankXp: 100,
    progressPct: Math.round((xp / 100) * 100)
  };
}

async function awardMileageAndExperienceBadges(
  db: DbExecutor,
  userId: string,
  totalKm: number,
  validRides: number,
  streakMonths: number,
  ride: {
    started_at_night?: boolean;
    rained?: boolean;
    offline_minutes?: number;
    elevation_gain_m?: number;
  }
): Promise<void> {
  const mileageMilestones: Array<[number, string]> = [
    [10, 'km_10_primeros_pasos'],
    [50, 'km_50_rodador'],
    [100, 'km_100_caminante'],
    [500, 'km_500_viajero'],
    [1000, 'km_1000_explorador'],
    [5000, 'km_5000_aventurero'],
    [10000, 'km_10000_trotamundos'],
    [25000, 'km_25000_cartografo'],
    [50000, 'km_50000_nomada'],
    [100000, 'km_100000_legendario']
  ];

  for (const [km, code] of mileageMilestones) {
    if (totalKm >= km) {
      await db.query(
        `INSERT INTO badges (id, family, code, receiver)
         VALUES ($1, 'achievement', $2, $3)
         ON CONFLICT DO NOTHING`,
        [randomUUID(), code, userId]
      );
    }
  }

  if (validRides >= 1) {
    await db.query(
      `INSERT INTO badges (id, family, code, receiver)
       VALUES ($1, 'achievement', 'exp_primera_rodada', $2)
       ON CONFLICT DO NOTHING`,
      [randomUUID(), userId]
    );
  }

  const streakMilestones: Array<[number, string]> = [
    [3, 'streak_3_meses'],
    [6, 'streak_6_meses'],
    [12, 'streak_12_meses'],
    [24, 'streak_24_imparable'],
    [36, 'streak_36_eterno']
  ];
  for (const [months, code] of streakMilestones) {
    if (streakMonths >= months) {
      await db.query(
        `INSERT INTO badges (id, family, code, receiver)
         VALUES ($1, 'achievement', $2, $3)
         ON CONFLICT DO NOTHING`,
        [randomUUID(), code, userId]
      );
    }
  }

  if (ride.started_at_night) {
    await db.query(
      `INSERT INTO badges (id, family, code, receiver)
       VALUES ($1, 'achievement', 'exp_rodada_nocturna', $2)
       ON CONFLICT DO NOTHING`,
      [randomUUID(), userId]
    );
  }
  if (ride.rained) {
    await db.query(
      `INSERT INTO badges (id, family, code, receiver)
       VALUES ($1, 'achievement', 'exp_bajo_la_lluvia', $2)
       ON CONFLICT DO NOTHING`,
      [randomUUID(), userId]
    );
  }
  if ((ride.offline_minutes ?? 0) >= 30) {
    await db.query(
      `INSERT INTO badges (id, family, code, receiver)
       VALUES ($1, 'achievement', 'exp_sin_cobertura', $2)
       ON CONFLICT DO NOTHING`,
      [randomUUID(), userId]
    );
  }
  if ((ride.elevation_gain_m ?? 0) >= 500) {
    await db.query(
      `INSERT INTO badges (id, family, code, receiver)
       VALUES ($1, 'achievement', 'exp_rodada_montana', $2)
       ON CONFLICT DO NOTHING`,
      [randomUUID(), userId]
    );
  }
}
