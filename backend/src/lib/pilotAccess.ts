import { createHash, timingSafeEqual } from 'node:crypto';
import type { FastifyRequest } from 'fastify';

/**
 * Gate de acceso para el piloto remoto con túnel HTTPS.
 *
 * El túnel rápido de Cloudflare no tiene Access: la única barrera es este gate,
 * por lo que se activa con una lista blanca de claves emitidas por build/tester.
 * Si hay claves configuradas, toda petición /v1/* debe presentar una cabecera
 * `x-rbs-pilot-key` vigente. Retirar una clave de la lista (o añadirla a la
 * lista de revocadas) corta el acceso en el próximo reinicio del backend.
 *
 * Nada de esto sustituye al manifiesto firmado de rodada ni a la autorización
 * Ed25519 de malla: es una capa de acceso al API, no de identidad de motorista.
 */

export interface PilotConfig {
  enabled: boolean;
  allowedDigests: Buffer[];
  revokedDigests: Buffer[];
  publicOrigins: Set<string>;
  allowTryCloudflareOrigins: boolean;
}

const MIN_KEY_LENGTH = 16;
const MAX_KEY_LENGTH = 256;

function digest(value: string): Buffer {
  return createHash('sha256').update(value, 'utf8').digest();
}

function parseKeyList(raw: string | undefined, label: string): string[] {
  if (!raw || !raw.trim()) return [];
  return raw
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry, index) => {
      if (entry.length < MIN_KEY_LENGTH || entry.length > MAX_KEY_LENGTH) {
        throw new Error(
          `${label}[${index}] debe tener entre ${MIN_KEY_LENGTH} y ${MAX_KEY_LENGTH} caracteres`
        );
      }
      if (/\s/.test(entry)) {
        throw new Error(`${label}[${index}] no puede contener espacios`);
      }
      return entry;
    });
}

export function parsePilotConfig(env: NodeJS.ProcessEnv): PilotConfig {
  const allowed = parseKeyList(env.RBS_PILOT_ALLOWED_KEYS, 'RBS_PILOT_ALLOWED_KEYS');
  const revoked = parseKeyList(env.RBS_PILOT_REVOKED_KEYS, 'RBS_PILOT_REVOKED_KEYS');
  const forced = env.RBS_PILOT_MODE === 'true';

  if (forced && allowed.length === 0) {
    throw new Error('RBS_PILOT_MODE=true requiere RBS_PILOT_ALLOWED_KEYS no vacío (fail-closed)');
  }

  const origins = (env.RBS_PILOT_PUBLIC_ORIGINS ?? '')
    .split(',')
    .map((entry) => entry.trim().replace(/\/$/, ''))
    .filter(Boolean);
  origins.push('http://127.0.0.1:3000', 'http://localhost:3000');

  return {
    enabled: allowed.length > 0,
    allowedDigests: allowed.map(digest),
    revokedDigests: revoked.map(digest),
    publicOrigins: new Set(origins),
    allowTryCloudflareOrigins: env.RBS_PILOT_ALLOW_TRYCLOUDFLARE !== 'false'
  };
}

function matchesAny(candidate: Buffer, list: Buffer[]): boolean {
  let matched = false;
  for (const expected of list) {
    const equal = candidate.length === expected.length && timingSafeEqual(candidate, expected);
    matched = matched || equal;
  }
  return matched;
}

export function isPilotKeyAllowed(config: PilotConfig, key: string | undefined | null): boolean {
  if (!config.enabled || !key) return false;
  const candidate = digest(key.trim());
  return matchesAny(candidate, config.allowedDigests) && !matchesAny(candidate, config.revokedDigests);
}

export function isPilotKeyRevoked(config: PilotConfig, key: string | undefined | null): boolean {
  if (!key) return false;
  return matchesAny(digest(key.trim()), config.revokedDigests);
}

/** Huella corta y no reversible para correlacionar logs sin exponer la clave. */
export function pilotKeyFingerprint(key: string | undefined | null): string {
  if (!key) return 'none';
  return digest(key.trim()).subarray(0, 6).toString('hex');
}

/** Solo se eximen salud, estáticos y el formulario público de soporte. */
export function isPilotGateExempt(req: FastifyRequest): boolean {
  if (req.method === 'OPTIONS') return true;
  const path = (req.raw.url ?? '').split('?')[0];
  if (path === '/health') return true;
  if (path === '/v1/support/report') return true;
  return !path.startsWith('/v1/');
}

export function isPilotOriginAllowed(config: PilotConfig, origin: string | undefined): boolean {
  if (!origin) return true; // clientes nativos no envían Origin
  const normalized = origin.trim().replace(/\/$/, '');
  if (config.publicOrigins.has(normalized)) return true;
  if (!config.enabled || !config.allowTryCloudflareOrigins) return false;
  return /^https:\/\/[A-Za-z0-9-]+\.trycloudflare\.com$/i.test(normalized);
}

export function pilotRateLimitKey(
  config: PilotConfig,
  req: FastifyRequest,
  fallback: (req: FastifyRequest) => string
): string {
  if (!config.enabled) return fallback(req);
  const key = req.headers['x-rbs-pilot-key'];
  const value = typeof key === 'string' ? key : '';
  return `pilot:${pilotKeyFingerprint(value)}`;
}
