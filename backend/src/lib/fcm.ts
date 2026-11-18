import fs from 'node:fs';
import path from 'node:path';
import admin from 'firebase-admin';
import type { DbExecutor } from '../db/pool.js';

export type FcmNotificationType =
  | 'SOS_PROXIMITY'
  | 'RIDE_INVITATION'
  | 'CLUB_INVITATION'
  | 'DIRECT_MESSAGE';

export interface SendResult {
  successCount: number;
  failureCount: number;
  messageIds: string[];
}

export class FcmService {
  private app: any = null;
  private isLiveFirebase = false;

  constructor(serviceAccountPath?: string) {
    const resolvedPath =
      serviceAccountPath ??
      path.join(process.cwd(), 'service_account.json');

    if (fs.existsSync(resolvedPath)) {
      try {
        const raw = fs.readFileSync(resolvedPath, 'utf8');
        const json = JSON.parse(raw);
        if (
          json.private_key &&
          !json.private_key.includes('REPLACE_WITH_FIREBASE')
        ) {
          const credentialObj = (admin as any).credential ?? (admin as any).default?.credential;
          if (credentialObj && typeof credentialObj.cert === 'function') {
            this.app = admin.initializeApp({
              credential: credentialObj.cert(json)
            });
            this.isLiveFirebase = true;
            console.info('🔥 Firebase Admin SDK inicializado exitosamente.');
          } else {
            console.info('ℹ️ Firebase admin.credential.cert no disponible. Usando modo Stub/Test.');
          }
        } else {
          console.info('ℹ️ Firebase service_account.json es una plantilla. Usando modo FcmService Stub/Test.');
        }
      } catch (err) {
        console.warn('⚠️ No se pudo inicializar Firebase Admin desde service_account.json:', err);
      }
    }
  }

  public isLive(): boolean {
    return this.isLiveFirebase;
  }

  /**
   * Registra o actualiza el token FCM de un usuario en PostgreSQL
   */
  public async registerToken(
    db: DbExecutor,
    userId: string,
    token: string,
    platform: string = 'android'
  ): Promise<void> {
    await db.query(
      `INSERT INTO fcm_tokens (user_id, token, platform, updated_at)
       VALUES ($1, $2, $3, now())
       ON CONFLICT (user_id, token) DO UPDATE
       SET platform = EXCLUDED.platform, updated_at = now()`,
      [userId, token, platform]
    );
  }

  /**
   * Obtiene los tokens FCM activos de un usuario respetando el consentimiento R15
   */
  public async getActiveTokensForUser(
    db: DbExecutor,
    userId: string
  ): Promise<string[]> {
    // Regla R15: Verificar si el usuario otorgó consentimiento de notificaciones
    const consentRes = await db.query(
      `SELECT granted FROM consents
       WHERE user_id = $1 AND kind = 'notifications' AND revoked_at IS NULL
       ORDER BY created_at DESC LIMIT 1`,
      [userId]
    );

    // Si existe registro explícito de consentimiento denegado (granted = false), no enviar
    if (consentRes.rows.length > 0 && consentRes.rows[0].granted === false) {
      return [];
    }

    const res = await db.query(
      `SELECT token FROM fcm_tokens WHERE user_id = $1`,
      [userId]
    );
    return res.rows.map((r) => r.token);
  }

  /**
   * Envía una notificación multicast a una lista de tokens FCM
   */
  public async sendMulticast(
    tokens: string[],
    notification: { title: string; body: string },
    data: Record<string, string>,
    channelId: string,
    priority: 'high' | 'normal' = 'normal'
  ): Promise<SendResult> {
    if (tokens.length === 0) {
      return { successCount: 0, failureCount: 0, messageIds: [] };
    }

    if (!this.isLiveFirebase || !this.app) {
      // Modo Stub / Test / CI: Simula envío exitoso para todos los tokens
      return {
        successCount: tokens.length,
        failureCount: 0,
        messageIds: tokens.map((_, i) => `mock-msg-id-${Date.now()}-${i}`)
      };
    }

    const message: any = {
      tokens,
      notification: {
        title: notification.title,
        body: notification.body
      },
      data,
      android: {
        priority: priority === 'high' ? 'high' : 'normal',
        notification: {
          channelId,
          sound: channelId === 'sos_channel' ? 'sos_alarm' : 'default',
          priority: priority === 'high' ? 'max' : 'default'
        }
      }
    };

    const response = await this.app.messaging().sendEachForMulticast(message);
    const messageIds: string[] = [];
    response.responses.forEach((res: any) => {
      if (res.messageId) messageIds.push(res.messageId);
    });

    return {
      successCount: response.successCount,
      failureCount: response.failureCount,
      messageIds
    };
  }

  /**
   * 1. Notificación de SOS de proximidad (Radio 5 km)
   */
  public async sendSosProximity(
    tokens: string[],
    params: {
      sosId?: string;
      distanceKm?: string;
      bearing?: string;
      hasMedicalSheet?: string;
      rideId?: string;
      senderUserId?: string;
      lat?: number;
      lng?: number;
    }
  ): Promise<SendResult> {
    const bearingText = params.bearing ? ` al ${params.bearing}` : '';
    const distText = params.distanceKm ? `A ${params.distanceKm} km${bearingText}` : 'En tu zona (< 5 km)';
    return this.sendMulticast(
      tokens,
      {
        title: '🚨 SOS BIKER CERCANO',
        body: `${distText} — Auxilio solicitado`
      },
      {
        type: 'sos',
        sosId: params.sosId ?? '',
        distanceKm: params.distanceKm ?? '0.0',
        bearing: params.bearing ?? 'Norte ⬆',
        hasMedicalSheet: params.hasMedicalSheet ?? 'false',
        ...(params.rideId ? { rideId: params.rideId } : {}),
        ...(params.senderUserId ? { senderUserId: params.senderUserId } : {})
      },
      'sos_channel',
      'high'
    );
  }

  /**
   * 2. Convocatoria / Invitación a Rodada
   */
  public async sendRideInvitation(
    tokens: string[],
    params: {
      rideId: string;
      clubName: string;
      rideTitle: string;
    }
  ): Promise<SendResult> {
    return this.sendMulticast(
      tokens,
      {
        title: `🏍️ Convocatoria: ${params.clubName}`,
        body: `Nueva rodada programada: "${params.rideTitle}"`
      },
      {
        type: 'RIDE_INVITATION',
        rideId: params.rideId,
        clubName: params.clubName
      },
      'ride_invitation_channel',
      'high'
    );
  }

  /**
   * 3. Invitación de Club / Hermandad
   */
  public async sendClubInvitation(
    tokens: string[],
    params: {
      groupId: string;
      clubName: string;
      inviterName: string;
    }
  ): Promise<SendResult> {
    return this.sendMulticast(
      tokens,
      {
        title: '🤝 Invitación de Club',
        body: `${params.inviterName} te ha invitado a unirte a ${params.clubName}`
      },
      {
        type: 'CLUB_INVITATION',
        groupId: params.groupId,
        clubName: params.clubName
      },
      'club_invitation_channel',
      'normal'
    );
  }

  /**
   * 4. Mensaje Táctico Directo
   */
  public async sendDirectMessage(
    tokens: string[],
    params: {
      fromUserId: string;
      fromAlias: string;
      preview: string;
    }
  ): Promise<SendResult> {
    return this.sendMulticast(
      tokens,
      {
        title: `💬 Mensaje de ${params.fromAlias}`,
        body: params.preview
      },
      {
        type: 'DIRECT_MESSAGE',
        fromUserId: params.fromUserId,
        fromAlias: params.fromAlias
      },
      'direct_message_channel',
      'normal'
    );
  }
}

export const defaultFcmService = new FcmService();
