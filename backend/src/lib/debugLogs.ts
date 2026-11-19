import fs from 'node:fs';
import path from 'node:path';

/**
 * Bloque D: utilidades para el endpoint de logs de desarrollo.
 *
 * El archivo de log se elige por una fecha con formato estricto (YYYYMMDD), de modo
 * que nunca se acepta una ruta arbitraria del cliente. Antes de responder se recorta
 * el número de líneas y se redacta PII (correos, tokens, UUIDs, coordenadas y blobs).
 */

export const DEBUG_LOG_PREFIX = 'fastify_dev_';
export const DEFAULT_DEBUG_LOG_LINES = 200;
export const MAX_DEBUG_LOG_LINES = 500;
const MAX_READ_CHARS = 2_000_000;

const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const JWT = /\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g;
const BEARER = /(bearer\s+)[A-Za-z0-9._~+/=-]{8,}/gi;
const QUOTED_NAME =
  /\b(name|displayName|fullName|nombre|display_name|full_name)\s*[:=]\s*(['"])[^'"]*\2/gi;
const ASSIGNMENT =
  /\b(token|secret|password|passwd|api[_-]?key|authorization|x-admin-key|uid|name|displayName|fullName|fcm[_-]?token|refresh[_-]?token)\s*[=:]\s*([^\s,;]+)/gi;
const COORDINATE = /-?\d{1,3}\.\d{5,}/g;
const UUID =
  /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi;
const BASE64_BLOB = /data:[A-Za-z0-9.+/-]+;base64,[A-Za-z0-9+/=]{16,}/g;

export function isDebugLogDate(date: string): boolean {
  return /^\d{8}$/.test(date);
}

export function resolveDebugLogPath(logsDir: string, date: string): string | null {
  if (!isDebugLogDate(date)) return null;
  const resolved = path.resolve(logsDir, `${DEBUG_LOG_PREFIX}${date}.log`);
  if (path.dirname(resolved) !== path.resolve(logsDir)) return null;
  return resolved;
}

export function redactBackendLogLine(line: string): string {
  return line
    .replace(BASE64_BLOB, '[base64-redacted]')
    .replace(JWT, '[jwt-redacted]')
    .replace(BEARER, (_all, prefix: string) => `${prefix}[redacted]`)
    .replace(QUOTED_NAME, (_all, key: string) => `${key}=[redacted]`)
    .replace(ASSIGNMENT, (_all, key: string) => `${key}=[redacted]`)
    .replace(EMAIL, '[email-redacted]')
    .replace(COORDINATE, '[coord]')
    .replace(UUID, '[uuid-redacted]');
}

export function tailLogLines(raw: string, maxLines: number): string[] {
  return raw.split(/\r?\n/).slice(-Math.max(1, maxLines));
}

export function readRedactedLogTail(logPath: string, maxLines: number): string {
  const raw = fs.readFileSync(logPath, 'utf8').slice(-MAX_READ_CHARS);
  return tailLogLines(raw, maxLines).map(redactBackendLogLine).join('\n');
}
