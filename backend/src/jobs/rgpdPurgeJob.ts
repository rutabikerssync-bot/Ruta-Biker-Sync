import type { DbExecutor } from '../db/pool.js';

export interface RgpdPurgeResult {
  purgedAt: string;
  expiredRecoveryTokens: number;
  oldRecoveryAttempts: number;
  oldRateLimitEvents: number;
  expiredMedicalSheets: number;
  oldEphemeralTelemetry: number;
}

export async function executeRgpdPurge(db: DbExecutor): Promise<RgpdPurgeResult> {
  // 1. Purgar tokens expirados o usados de recuperación de bóveda (>24h)
  const tokensRes = await db.query(
    `DELETE FROM vault_recovery_tokens
     WHERE expires_at < now() OR (created_at < now() - interval '24 hours' AND used = true)`
  );

  // 2. Purgar intentos de recuperación con más de 30 días (RGPD minimización)
  const attemptsRes = await db.query(
    `DELETE FROM vault_recovery_attempts
     WHERE created_at < now() - interval '30 days'`
  );

  // 3. Purgar eventos de rate limit con más de 24 horas
  const rateLimitRes = await db.query(
    `DELETE FROM rate_limit_events
     WHERE created_at < now() - interval '24 hours'`
  );

  // 4. Purgar fichas médicas compartidas expiradas (> 24 horas)
  const medicalRes = await db.query(
    `DELETE FROM shared_medical_sheets
     WHERE expires_at < now() OR shared_at < now() - interval '24 hours'`
  );

  // 5. Purgar telemetría y coordenadas efímeras con antigüedad > 24 horas (RGPD)
  const telemetryRes = await db.query(
    `DELETE FROM ephemeral_telemetry
     WHERE created_at < now() - interval '24 hours'`
  );

  return {
    purgedAt: new Date().toISOString(),
    expiredRecoveryTokens: tokensRes.rowCount ?? 0,
    oldRecoveryAttempts: attemptsRes.rowCount ?? 0,
    oldRateLimitEvents: rateLimitRes.rowCount ?? 0,
    expiredMedicalSheets: medicalRes.rowCount ?? 0,
    oldEphemeralTelemetry: telemetryRes.rowCount ?? 0
  };
}

export function startRgpdPurgeCron(db: DbExecutor, intervalMs: number = 24 * 60 * 60 * 1000): NodeJS.Timeout {
  // Ejecuta una purga inicial no bloqueante
  executeRgpdPurge(db).catch((err) => {
    console.error('[RGPD Purge Job] Error en purga inicial:', err);
  });

  return setInterval(() => {
    executeRgpdPurge(db).catch((err) => {
      console.error('[RGPD Purge Job] Error en ejecución periódica:', err);
    });
  }, intervalMs);
}
