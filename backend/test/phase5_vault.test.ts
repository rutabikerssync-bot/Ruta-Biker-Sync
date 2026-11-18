import { beforeAll, afterAll, describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';
import { buildApp } from '../src/app.js';
import { createPgPool, readInitMigrationSql, type DbExecutor } from '../src/db/pool.js';
import type { FastifyInstance } from 'fastify';

describe('Fase 5: Bóveda Biker Avanzada (E2EE, Recuperación y Ficha Médica)', () => {
  let db: DbExecutor;
  let app: FastifyInstance;

  // IDs de prueba deterministas
  const userBikerId = randomUUID();
  const leaderId = randomUUID();
  const sergeantId = randomUUID();
  const regularMemberId = randomUUID();
  const groupId = randomUUID();
  const rideId = randomUUID();

  beforeAll(async () => {
    const connectionString = process.env.PG_TEST_URL ?? 'postgres://rbs:rbs_dev_password@localhost:5433/rbs';
    db = createPgPool(connectionString);
    await db.query('SELECT 1');
    const sql = readInitMigrationSql();
    await db.query(sql);

    // Limpiar usuarios específicos de Phase 5 si quedaron remanentes
    const phase5Filter = `email LIKE '%.e2ee@rbs.dev' OR email = 'ratelimit.user@rbs.dev'`;
    await db.query(`DELETE FROM shared_medical_sheets WHERE user_id IN (SELECT id FROM users WHERE ${phase5Filter})`);
    await db.query(`DELETE FROM vault_recovery_attempts WHERE user_id IN (SELECT id FROM users WHERE ${phase5Filter})`);
    await db.query(`DELETE FROM vault_recovery_tokens WHERE user_id IN (SELECT id FROM users WHERE ${phase5Filter})`);
    await db.query(`DELETE FROM vault_recovery_keys WHERE user_id IN (SELECT id FROM users WHERE ${phase5Filter})`);
    await db.query(`DELETE FROM vault_items WHERE user_id IN (SELECT id FROM users WHERE ${phase5Filter})`);
    await db.query(`DELETE FROM rides WHERE host_leader IN (SELECT id FROM users WHERE ${phase5Filter})`);
    await db.query(`DELETE FROM memberships WHERE user_id IN (SELECT id FROM users WHERE ${phase5Filter}) OR group_id IN (SELECT id FROM groups WHERE principal_leader IN (SELECT id FROM users WHERE ${phase5Filter}))`);
    await db.query(`DELETE FROM group_leaders WHERE user_id IN (SELECT id FROM users WHERE ${phase5Filter}) OR group_id IN (SELECT id FROM groups WHERE principal_leader IN (SELECT id FROM users WHERE ${phase5Filter}))`);
    await db.query(`DELETE FROM groups WHERE principal_leader IN (SELECT id FROM users WHERE ${phase5Filter})`);
    await db.query(`DELETE FROM users WHERE ${phase5Filter}`);
    // Preparar usuarios y club
    await db.query(`
      INSERT INTO users (id, email, display_name, country_code, account_type) VALUES
        ($1, 'biker.e2ee@rbs.dev', 'Biker E2EE', 'VE', 'member'),
        ($2, 'leader.e2ee@rbs.dev', 'Líder Fundador', 'VE', 'leader'),
        ($3, 'sergeant.e2ee@rbs.dev', 'Sargento Armas', 'VE', 'member'),
        ($4, 'regular.e2ee@rbs.dev', 'Miembro Regular', 'VE', 'member')
      ON CONFLICT (id) DO NOTHING;
    `, [userBikerId, leaderId, sergeantId, regularMemberId]);

    await db.query(`
      INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader, status)
      VALUES ($1, 'VE', 'Club Bóveda Test', 'club boveda test', $2, $3, 'active')
      ON CONFLICT (id) DO NOTHING;
    `, [groupId, Buffer.from('0000111122223333', 'hex'), leaderId]);

    // Asignar Sargento de Armas en memberships
    await db.query(`
      INSERT INTO memberships (id, group_id, user_id, status_kind, club_role, invite_state, accepted_at)
      VALUES
        ($1, $2, $3, 'full', 'sargento_armas', 'accepted', now()),
        ($4, $2, $5, 'full', NULL, 'accepted', now())
      ON CONFLICT (id) DO NOTHING;
    `, [randomUUID(), groupId, sergeantId, randomUUID(), regularMemberId]);

    // Crear rodada activa
    await db.query(`
      INSERT INTO rides (id, host_group, host_leader, title, state, starts_at)
      VALUES ($1, $2, $3, 'Rodada Nocturna Caracas', 'active', now())
      ON CONFLICT (id) DO NOTHING;
    `, [rideId, groupId, leaderId]);

    app = await buildApp({ db, logger: false });
  });

  afterAll(async () => {
    // Limpiar datos de prueba
    await db.query(`DELETE FROM shared_medical_sheets WHERE ride_id = $1`, [rideId]);
    await db.query(`DELETE FROM vault_recovery_tokens WHERE user_id = $1`, [userBikerId]);
    await db.query(`DELETE FROM vault_recovery_keys WHERE user_id = $1`, [userBikerId]);
    await db.query(`DELETE FROM vault_items WHERE user_id = $1`, [userBikerId]);
    await db.query(`DELETE FROM rides WHERE id = $1`, [rideId]);
    await db.query(`DELETE FROM memberships WHERE group_id = $1`, [groupId]);
    await db.query(`DELETE FROM groups WHERE id = $1`, [groupId]);
    await db.query(`DELETE FROM vault_recovery_attempts`);
    await db.query(`DELETE FROM users WHERE id IN ($1, $2, $3, $4)`, [userBikerId, leaderId, sergeantId, regularMemberId]);

    if (app) await app.close();
    await db.end();
  });

  describe('5.1 & 5.2: Documentos Cifrados en Reposo (E2EE)', () => {
    let docId: string;

    it('1. Sube un documento cifrado en reposo exitosamente (201)', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/vault/documents',
        headers: { 'x-user-id': userBikerId },
        payload: {
          docType: 'medical_sheet',
          ciphertext: 'c29tZV9lbmNyeXB0ZWRfYmluYXJ5X2RhdGE=',
          iv: 'MTIzNDU2Nzg5MDEy',
          authTag: 'YWJjZGVmMTIzNDU2Nzg5MA==',
          userSalt: 'MDEyMzQ1Njc4OWFiY2RlZg==',
          title: 'Ficha Médica de Emergencia'
        }
      });

      expect(res.statusCode).toBe(201);
      const body = res.json();
      expect(body.success).toBe(true);
      expect(body.document.docType).toBe('medical_sheet');
      expect(body.document.title).toBe('Ficha Médica de Emergencia');
      expect(body.document.id).toBeDefined();
      docId = body.document.id;
    });

    it('2. Lista metadatos de documentos sin exponer ciphertext', async () => {
      const res = await app.inject({
        method: 'GET',
        url: '/v1/vault/documents',
        headers: { 'x-user-id': userBikerId }
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(Array.isArray(body.documents)).toBe(true);
      expect(body.documents.length).toBe(1);
      expect(body.documents[0].docType).toBe('medical_sheet');
      expect(body.documents[0].ciphertext).toBeUndefined(); // Privacidad: sin blob
      expect(body.documents[0].userSalt).toBe('MDEyMzQ1Njc4OWFiY2RlZg==');
    });

    it('3. Descarga el blob cifrado completo validando propiedad (200)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: `/v1/vault/documents/${docId}`,
        headers: { 'x-user-id': userBikerId }
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(body.id).toBe(docId);
      expect(body.ciphertext).toBe('c29tZV9lbmNyeXB0ZWRfYmluYXJ5X2RhdGE=');
      expect(body.iv).toBe('MTIzNDU2Nzg5MDEy');
      expect(body.authTag).toBe('YWJjZGVmMTIzNDU2Nzg5MA==');
      expect(body.userSalt).toBe('MDEyMzQ1Njc4OWFiY2RlZg==');
    });

    it('4. Bloquea el acceso a documentos de otro usuario (403 FORBIDDEN)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: `/v1/vault/documents/${docId}`,
        headers: { 'x-user-id': regularMemberId }
      });

      expect(res.statusCode).toBe(403);
      expect(res.json().error).toBe('FORBIDDEN');
    });

    it('5. Elimina documento por el dueño (200)', async () => {
      const res = await app.inject({
        method: 'DELETE',
        url: `/v1/vault/documents/${docId}`,
        headers: { 'x-user-id': userBikerId }
      });

      expect(res.statusCode).toBe(200);
      expect(res.json().success).toBe(true);

      const verifyRes = await app.inject({
        method: 'GET',
        url: `/v1/vault/documents/${docId}`,
        headers: { 'x-user-id': userBikerId }
      });
      expect(verifyRes.statusCode).toBe(404);
    });
  });

  describe('5.2: Recuperación de Clave Maestra con Passphrase Fuerte (PBKDF2)', () => {
    let recoveryToken: string;

    it('1. Genera token de un solo uso para recuperación con TTL 300s (200)', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/vault/recovery/token',
        headers: { 'x-user-id': userBikerId }
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(body.recoveryToken).toBeDefined();
      expect(body.expiresInSeconds).toBe(300);
      recoveryToken = body.recoveryToken;
    });

    it('2. Bloquea registro de recovery blob sin X-Recovery-Token (403 REAUTH_REQUIRED)', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/vault/recovery',
        headers: { 'x-user-id': userBikerId },
        payload: {
          encryptedMasterKey: 'ZW5jcnlwdGVkX21hc3Rlcl9rZXlfYmxvYg==',
          iv: 'MTIzNDU2Nzg5MDEy',
          authTag: 'YWJjZGVmMTIzNDU2Nzg5MA==',
          pbkdf2Salt: 'cGJrZGYyX3NhbHRfdmFsdWU=',
          pbkdf2Iterations: 100000
        }
      });

      expect(res.statusCode).toBe(403);
      expect(res.json().error).toBe('REAUTH_REQUIRED');
    });

    it('3. Registra clave maestra de recuperación con X-Recovery-Token válido (201)', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/vault/recovery',
        headers: {
          'x-user-id': userBikerId,
          'x-recovery-token': recoveryToken
        },
        payload: {
          encryptedMasterKey: 'ZW5jcnlwdGVkX21hc3Rlcl9rZXlfYmxvYg==',
          iv: 'MTIzNDU2Nzg5MDEy',
          authTag: 'YWJjZGVmMTIzNDU2Nzg5MA==',
          pbkdf2Salt: 'cGJrZGYyX3NhbHRfdmFsdWU=',
          pbkdf2Iterations: 100000
        }
      });

      expect(res.statusCode).toBe(201);
      expect(res.json().success).toBe(true);
    });

    it('4. Bloquea reutilización del token de recuperación ya consumido (403)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: '/v1/vault/recovery',
        headers: {
          'x-user-id': userBikerId,
          'x-recovery-token': recoveryToken // ya usado
        }
      });

      expect(res.statusCode).toBe(403);
      expect(res.json().error).toBe('REAUTH_REQUIRED');
    });

    it('5. Descarga la clave de recuperación con nuevo token efímero (200)', async () => {
      await db.query('DELETE FROM vault_recovery_attempts WHERE user_id = $1', [userBikerId]);
      // Solicitar nuevo token
      const tokenRes = await app.inject({
        method: 'POST',
        url: '/v1/vault/recovery/token',
        headers: { 'x-user-id': userBikerId }
      });
      const freshToken = tokenRes.json().recoveryToken;

      const res = await app.inject({
        method: 'GET',
        url: '/v1/vault/recovery',
        headers: {
          'x-user-id': userBikerId,
          'x-recovery-token': freshToken
        }
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(body.encryptedMasterKey).toBe('ZW5jcnlwdGVkX21hc3Rlcl9rZXlfYmxvYg==');
      expect(body.pbkdf2Salt).toBe('cGJrZGYyX3NhbHRfdmFsdWU=');
      expect(body.pbkdf2Iterations).toBe(100000);
    });
  });

  describe('5.3: Compartición Efímera de Ficha Médica y RBAC Estricto', () => {
    it('1. Comparte ficha médica para rodada activa (201)', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/vault/medical/share',
        headers: { 'x-user-id': userBikerId },
        payload: {
          rideId,
          groupId,
          ciphertext: 'bWVkaWNhbF9zaGVldF9lbmNyeXB0ZWQ=',
          iv: 'MTIzNDU2Nzg5MDEy',
          authTag: 'YWJjZGVmMTIzNDU2Nzg5MA==',
          durationHours: 12
        }
      });

      expect(res.statusCode).toBe(201);
      const body = res.json();
      expect(body.success).toBe(true);
      expect(body.rideId).toBe(rideId);
      expect(body.expiresAt).toBeDefined();
    });

    it('2. Bloquea acceso a ficha médica a miembro regular sin cargo directivo (403 MEDICAL_ACCESS_RESTRICTED)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: `/v1/vault/medical/${userBikerId}?rideId=${rideId}`,
        headers: { 'x-user-id': regularMemberId }
      });

      expect(res.statusCode).toBe(403);
      expect(res.json().error).toBe('MEDICAL_ACCESS_RESTRICTED');
    });

    it('3. Permite acceso a ficha médica al Sargento de Armas durante la rodada (200)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: `/v1/vault/medical/${userBikerId}?rideId=${rideId}`,
        headers: { 'x-user-id': sergeantId }
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(body.userId).toBe(userBikerId);
      expect(body.ciphertext).toBe('bWVkaWNhbF9zaGVldF9lbmNyeXB0ZWQ=');
      expect(body.authTag).toBe('YWJjZGVmMTIzNDU2Nzg5MA==');
    });

    it('4. Permite acceso a ficha médica al Líder Fundador del club (200)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: `/v1/vault/medical/${userBikerId}?rideId=${rideId}`,
        headers: { 'x-user-id': leaderId }
      });

      expect(res.statusCode).toBe(200);
      expect(res.json().ciphertext).toBe('bWVkaWNhbF9zaGVldF9lbmNyeXB0ZWQ=');
    });

    it('5. Bloquea acceso a ficha médica cuando la rodada finaliza (403 RIDE_NOT_ACTIVE)', async () => {
      // Cerrar la rodada en base de datos
      await db.query(`UPDATE rides SET state = 'closed' WHERE id = $1`, [rideId]);

      const res = await app.inject({
        method: 'GET',
        url: `/v1/vault/medical/${userBikerId}?rideId=${rideId}`,
        headers: { 'x-user-id': sergeantId }
      });

      expect(res.statusCode).toBe(403);
      expect(res.json().error).toBe('RIDE_NOT_ACTIVE');
    });
  });

  describe('Hito 2: Rate Limiting de Grado Militar en Recuperación E2EE (RULES.md §15.8)', () => {
    const rateLimitTestUserId = randomUUID();

    beforeAll(async () => {
      await db.query(`
        INSERT INTO users (id, email, display_name, country_code, account_type)
        VALUES ($1, $2, 'Rate Limit Biker', 'VE', 'member')
        ON CONFLICT (id) DO NOTHING;
      `, [rateLimitTestUserId, `ratelimit.${rateLimitTestUserId}@rbs.dev`]);
    });

    it('1. Permite exactamente 5 solicitudes de token en 1 hora (200 OK)', async () => {
      // Limpiar intentos previos para este usuario de prueba
      await db.query('DELETE FROM vault_recovery_attempts WHERE user_id = $1', [rateLimitTestUserId]);

      for (let i = 1; i <= 5; i++) {
        const res = await app.inject({
          method: 'POST',
          url: '/v1/vault/recovery/token',
          headers: { 'x-user-id': rateLimitTestUserId }
        });
        expect(res.statusCode).toBe(200);
        expect(res.json().recoveryToken).toBeDefined();
      }

      // Verificar que los 5 intentos quedaron registrados en la tabla de auditoría
      const attempts = await db.query(
        'SELECT count(*) as count FROM vault_recovery_attempts WHERE user_id = $1',
        [rateLimitTestUserId]
      );
      expect(parseInt(attempts.rows[0].count, 10)).toBe(5);
    });

    it('2. Bloquea el 6º intento con HTTP 429 Too Many Requests y cabecera Retry-After: 3600', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/v1/vault/recovery/token',
        headers: { 'x-user-id': rateLimitTestUserId }
      });

      expect(res.statusCode).toBe(429);
      expect(res.headers['retry-after']).toBe('3600');
      const body = res.json();
      expect(body.error).toBe('TOO_MANY_ATTEMPTS');
      expect(body.retryAfter).toBe(3600);
      expect(body.message).toContain('Has superado el límite de 5 intentos por hora');
    });

    it('3. Bloquea también GET /v1/vault/recovery bajo el mismo límite de usuario (HTTP 429)', async () => {
      const res = await app.inject({
        method: 'GET',
        url: '/v1/vault/recovery',
        headers: {
          'x-user-id': rateLimitTestUserId,
          'x-recovery-token': 'any-token'
        }
      });

      expect(res.statusCode).toBe(429);
      expect(res.headers['retry-after']).toBe('3600');
    });

    it('4. Aplica bloqueo progresivo exponencial de 6 horas al acumular 10 intentos (Retry-After: 21600)', async () => {
      // Insertar 5 intentos más en el historial reciente
      for (let i = 0; i < 5; i++) {
        await db.query(
          `INSERT INTO vault_recovery_attempts (ip, user_id, endpoint, success, created_at)
           VALUES ('127.0.0.1', $1, '/v1/vault/recovery/token', false, now())`,
          [rateLimitTestUserId]
        );
      }

      const res = await app.inject({
        method: 'POST',
        url: '/v1/vault/recovery/token',
        headers: { 'x-user-id': rateLimitTestUserId }
      });

      expect(res.statusCode).toBe(429);
      expect(res.headers['retry-after']).toBe('21600');
      expect(res.json().retryAfter).toBe(21600);
      expect(res.json().message).toContain('6 horas');
    });

    it('5. Aplica bloqueo progresivo exponencial de 24 horas al acumular 20 intentos (Retry-After: 86400)', async () => {
      // Insertar 10 intentos más en el historial reciente (total >= 20)
      for (let i = 0; i < 10; i++) {
        await db.query(
          `INSERT INTO vault_recovery_attempts (ip, user_id, endpoint, success, created_at)
           VALUES ('127.0.0.1', $1, '/v1/vault/recovery/token', false, now())`,
          [rateLimitTestUserId]
        );
      }

      const res = await app.inject({
        method: 'POST',
        url: '/v1/vault/recovery/token',
        headers: { 'x-user-id': rateLimitTestUserId }
      });

      expect(res.statusCode).toBe(429);
      expect(res.headers['retry-after']).toBe('86400');
      expect(res.json().retryAfter).toBe(86400);
      expect(res.json().message).toContain('24 horas');
    });
  });

  describe('Hito 3: Revocación Anticipada de Ficha Médica y Blindaje de Auditoría (Fase 5.5)', () => {
    const revUserBikerId = randomUUID();
    const revSergeantId = randomUUID();
    const revLeaderId = randomUUID();
    const revGroupId = randomUUID();
    const revRideId = randomUUID();

    beforeAll(async () => {
      // Registrar usuarios y club para la prueba de revocación
      await db.query(`
        INSERT INTO users (id, email, display_name, country_code, account_type) VALUES
          ($1, 'biker.revoc@rbs.dev', 'Biker Revocacion', 'VE', 'member'),
          ($2, 'leader.revoc@rbs.dev', 'Leader Revocacion', 'VE', 'leader'),
          ($3, 'sergeant.revoc@rbs.dev', 'Sergeant Revocacion', 'VE', 'member')
        ON CONFLICT (id) DO NOTHING;
      `, [revUserBikerId, revLeaderId, revSergeantId]);

      await db.query(`
        INSERT INTO groups (id, country_code, name, name_normalized, logo_phash, principal_leader, status)
        VALUES ($1, 'VE', 'Club Revocacion Test', 'club revocacion test', $2, $3, 'active')
        ON CONFLICT (id) DO NOTHING;
      `, [revGroupId, Buffer.from('0000111122223333', 'hex'), revLeaderId]);

      await db.query(`
        INSERT INTO memberships (id, group_id, user_id, status_kind, club_role, invite_state, accepted_at)
        VALUES ($1, $2, $3, 'full', 'sargento_armas', 'accepted', now())
        ON CONFLICT (id) DO NOTHING;
      `, [randomUUID(), revGroupId, revSergeantId]);

      await db.query(`
        INSERT INTO rides (id, host_group, host_leader, title, state, starts_at)
        VALUES ($1, $2, $3, 'Rodada Revocacion Activa', 'active', now())
        ON CONFLICT (id) DO NOTHING;
      `, [revRideId, revGroupId, revLeaderId]);
    });

    afterAll(async () => {
      await db.query(`DELETE FROM shared_medical_sheets WHERE ride_id = $1`, [revRideId]);
      await db.query(`DELETE FROM rides WHERE id = $1`, [revRideId]);
      await db.query(`DELETE FROM memberships WHERE group_id = $1`, [revGroupId]);
      await db.query(`DELETE FROM groups WHERE id = $1`, [revGroupId]);
      await db.query(`DELETE FROM users WHERE id IN ($1, $2, $3)`, [revUserBikerId, revLeaderId, revSergeantId]);
    });

    it('1. Dueño revoca anticipadamente el acceso a su ficha médica compartida (200 OK)', async () => {
      // 1. Compartir ficha médica
      const shareRes = await app.inject({
        method: 'POST',
        url: '/v1/vault/medical/share',
        headers: { 'x-user-id': revUserBikerId },
        payload: {
          rideId: revRideId,
          groupId: revGroupId,
          ciphertext: 'c2hhcmVkX2RhdGFfYmVmb3JlX3Jldm9jYXRpb24=',
          iv: 'MTIzNDU2Nzg5MDEy',
          authTag: 'YWJjZGVmMTIzNDU2Nzg5MA==',
          durationHours: 24
        }
      });
      expect(shareRes.statusCode).toBe(201);

      // 2. Verificar que Sargento puede leerla antes de revocar
      const readBefore = await app.inject({
        method: 'GET',
        url: `/v1/vault/medical/${revUserBikerId}?rideId=${revRideId}`,
        headers: { 'x-user-id': revSergeantId }
      });
      expect(readBefore.statusCode).toBe(200);

      // 3. Dueño revoca anticipadamente
      const revokeRes = await app.inject({
        method: 'DELETE',
        url: `/v1/vault/medical/share?rideId=${revRideId}`,
        headers: { 'x-user-id': revUserBikerId }
      });
      expect(revokeRes.statusCode).toBe(200);
      expect(revokeRes.json().success).toBe(true);
    });

    it('2. Bloquea revocación a un tercero y devuelve 404 si no tiene ficha para revocar', async () => {
      const thirdPartyRes = await app.inject({
        method: 'DELETE',
        url: `/v1/vault/medical/share?rideId=${revRideId}`,
        headers: { 'x-user-id': revSergeantId } // El sargento no tiene ficha compartida propia
      });
      expect(thirdPartyRes.statusCode).toBe(404);
      expect(thirdPartyRes.json().error).toBe('NOT_FOUND');
    });

    it('3. Tras la revocación, el Sargento de Armas recibe 404 al intentar leer la ficha', async () => {
      const readAfter = await app.inject({
        method: 'GET',
        url: `/v1/vault/medical/${revUserBikerId}?rideId=${revRideId}`,
        headers: { 'x-user-id': revSergeantId }
      });
      expect(readAfter.statusCode).toBe(404);
      expect(readAfter.json().error).toBe('MEDICAL_SHEET_NOT_FOUND');
    });

    it('4. Blindaje de Auditoría Forense: al eliminar un usuario, los intentos persisten con user_id = NULL (ON DELETE SET NULL)', async () => {
      const ephemeralAuditUserId = randomUUID();
      await db.query(`
        INSERT INTO users (id, email, display_name, country_code, account_type)
        VALUES ($1, 'ephemeral.audit@rbs.dev', 'Ephemeral Audit Biker', 'VE', 'member')
      `, [ephemeralAuditUserId]);

      // Registrar 3 intentos fallidos de recuperación
      for (let i = 0; i < 3; i++) {
        await db.query(`
          INSERT INTO vault_recovery_attempts (ip, user_id, endpoint, success, created_at)
          VALUES ('192.168.1.100', $1, '/v1/vault/recovery/token', false, now())
        `, [ephemeralAuditUserId]);
      }

      // Eliminar al usuario de la base de datos
      await db.query(`DELETE FROM users WHERE id = $1`, [ephemeralAuditUserId]);

      // Verificar que los registros en vault_recovery_attempts NO se eliminaron, sino que user_id es NULL
      const checkRes = await db.query(`
        SELECT count(*) as count, user_id
        FROM vault_recovery_attempts
        WHERE ip = '192.168.1.100'
        GROUP BY user_id
      `);
      expect(Number(checkRes.rows[0].count)).toBe(3);
      expect(checkRes.rows[0].user_id).toBeNull();

      // Limpieza de prueba
      await db.query(`DELETE FROM vault_recovery_attempts WHERE ip = '192.168.1.100'`);
    });
  });
});
