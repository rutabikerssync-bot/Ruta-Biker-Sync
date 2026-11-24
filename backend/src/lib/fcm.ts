import fs from 'node:fs';
import admin from 'firebase-admin';
import { getMessaging } from 'firebase-admin/messaging';
import type { DbExecutor } from '../db/pool.js';
import {
  NOTIFICATION_CHANNEL_ID,
  NOTIFICATION_FCM_TYPE,
  type NotificationType
} from './notificationTypes.js';

export type FcmNotificationType = NotificationType;

export interface SendResult {
  successCount: number;
  failureCount: number;
  messageIds: string[];
}

export class FcmService {
  private app: any = null;
  private isLiveFirebase = false;

  constructor(serviceAccountPath?: string) {
    // La credencial SIEMPRE viene de la variable de entorno. Nunca se asume
    // process.cwd() para evitar usar una clave real en tests/CI por accidente.
    const resolvedPath = serviceAccountPath ?? process.env.GOOGLE_APPLICATION_CREDENTIALS?.trim();

    if (!resolvedPath) {
      console.info('ℹ️ GOOGLE_APPLICATION_CREDENTIALS no definida. Usando modo FcmService Stub/Test.');
      return;
    }

    if (!fs.existsSync(resolvedPath)) {
      console.warn(`⚠️ GOOGLE_APPLICATION_CREDENTIALS apunta a un archivo inexistente. Usando modo FcmService Stub/Test: ${resolvedPath}`);
      return;
    }

    try {
      const json = JSON.parse(fs.readFileSync(resolvedPath, 'utf8'));
      if (!json.private_key || json.private_key.includes('REPLACE_WITH_FIREBASE')) {
        console.info('ℹ️ Firebase service_account.json es una plantilla. Usando modo FcmService Stub/Test.');
        return;
      }

      // firebase-admin v14 expone `cert` directamente en el default import;
      // versiones anteriores lo exponen como `credential.cert`. Soportamos ambas.
      const adminAny = admin as any;
      const certFn =
        adminAny.credential?.cert ??
        adminAny.cert ??
        adminAny.default?.credential?.cert ??
        adminAny.default?.cert;
      const initFn = adminAny.initializeApp ?? adminAny.default?.initializeApp;
      if (typeof certFn !== 'function' || typeof initFn !== 'function') {
        console.info('ℹ️ Firebase admin cert/initializeApp no disponible. Usando modo Stub/Test.');
        return;
      }

      // Reutiliza la app por defecto si el módulo ya fue inicializado (evita
      // 'default app already exists' cuando se inyectan servicios en tests).
      const apps = typeof adminAny.getApps === 'function'
        ? adminAny.getApps()
        : adminAny.apps ?? [];
      this.app = apps[0] ?? initFn({
        credential: certFn(json)
      });
      this.isLiveFirebase = true;
      console.info('🔥 FCM_LIVE: Firebase Admin SDK inicializado con GOOGLE_APPLICATION_CREDENTIALS.');
    } catch (err) {
      console.warn('⚠️ No se pudo inicializar Firebase Admin desde GOOGLE_APPLICATION_CREDENTIALS:', err);
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
          sound: channelId === NOTIFICATION_CHANNEL_ID.SOS_PROXIMITY ? 'sos_alarm' : 'default',
          priority: priority === 'high' ? 'max' : 'default'
        }
      }
    };

    // firebase-admin v14: messaging vive en el subpaquete modular.
    const messaging = getMessaging(this.app);
    const response = await messaging.sendEachForMulticast(message);
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
        type: NOTIFICATION_FCM_TYPE.SOS_PROXIMITY,
        sosId: params.sosId ?? '',
        distanceKm: params.distanceKm ?? '0.0',
        bearing: params.bearing ?? 'Norte ⬆',
        hasMedicalSheet: params.hasMedicalSheet ?? 'false',
        lat: String(params.lat ?? 0),
        lng: String(params.lng ?? 0),
        ...(params.rideId ? { rideId: params.rideId } : {}),
        ...(params.senderUserId ? { senderUserId: params.senderUserId } : {})
      },
      NOTIFICATION_CHANNEL_ID.SOS_PROXIMITY,
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
        type: NOTIFICATION_FCM_TYPE.RIDE_INVITATION,
        rideId: params.rideId,
        clubName: params.clubName
      },
      NOTIFICATION_CHANNEL_ID.RIDE_INVITATION,
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
        type: NOTIFICATION_FCM_TYPE.CLUB_INVITATION,
        groupId: params.groupId,
        clubName: params.clubName
      },
      NOTIFICATION_CHANNEL_ID.CLUB_INVITATION,
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
        type: NOTIFICATION_FCM_TYPE.DIRECT_MESSAGE,
        fromUserId: params.fromUserId,
        fromAlias: params.fromAlias
      },
      NOTIFICATION_CHANNEL_ID.DIRECT_MESSAGE,
      'normal'
    );
  }

  /**
   * 5. Chat táctico (Comparte canal con mensajes directos; NUNCA con SOS).
   */
  public async sendTacticalChat(
    tokens: string[],
    params: {
      groupId: string;
      fromUserId: string;
      fromAlias: string;
      preview: string;
    }
  ): Promise<SendResult> {
    return this.sendMulticast(
      tokens,
      {
        title: `💬 Chat táctico: ${params.fromAlias}`,
        body: params.preview
      },
      {
        type: NOTIFICATION_FCM_TYPE.TACTICAL_CHAT,
        groupId: params.groupId,
        fromUserId: params.fromUserId,
        fromAlias: params.fromAlias
      },
      NOTIFICATION_CHANNEL_ID.TACTICAL_CHAT,
      'normal'
    );
  }

  /**
   * 6. Señal de convoy (Comparte canal de chat; abre la cabina por deep-link).
   */
  public async sendConvoySignal(
    tokens: string[],
    params: {
      rideId: string;
      signal: string;
      fromAlias: string;
    }
  ): Promise<SendResult> {
    return this.sendMulticast(
      tokens,
      {
        title: `🏍️ Señal de convoy: ${params.signal}`,
        body: `${params.fromAlias} envió una señal al convoy`
      },
      {
        type: NOTIFICATION_FCM_TYPE.CONVOY_SIGNAL,
        rideId: params.rideId,
        signal: params.signal,
        fromAlias: params.fromAlias
      },
      NOTIFICATION_CHANNEL_ID.CONVOY_SIGNAL,
      'normal'
    );
  }
}

export const defaultFcmService = new FcmService();
