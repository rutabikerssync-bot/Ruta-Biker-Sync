/**
 * Contrato único de tipos de notificación entre backend y Android.
 *
 * El valor `fcmType` viaja en `data.type` y debe coincidir con el casing que
 * `NotificationRouting.kt` espera en la app (minúsculas). El valor `channelId`
 * coincide con los canales canónicos declarados en `RutaBikerMessagingService.kt`.
 */
export const NOTIFICATION_TYPES = [
  'SOS_PROXIMITY',
  'RIDE_INVITATION',
  'CLUB_INVITATION',
  'DIRECT_MESSAGE',
  'TACTICAL_CHAT',
  'CONVOY_SIGNAL'
] as const;

export type NotificationType = (typeof NOTIFICATION_TYPES)[number];

export const NOTIFICATION_FCM_TYPE: Record<NotificationType, string> = {
  SOS_PROXIMITY: 'sos',
  RIDE_INVITATION: 'ride_invitation',
  CLUB_INVITATION: 'club_invitation',
  DIRECT_MESSAGE: 'direct_message',
  TACTICAL_CHAT: 'tactical_chat',
  CONVOY_SIGNAL: 'convoy_signal'
};

export const NOTIFICATION_CHANNEL_ID: Record<NotificationType, string> = {
  SOS_PROXIMITY: 'sos_channel',
  RIDE_INVITATION: 'ride_invitation_channel',
  CLUB_INVITATION: 'club_invitation_channel',
  DIRECT_MESSAGE: 'direct_message_channel',
  TACTICAL_CHAT: 'direct_message_channel',
  CONVOY_SIGNAL: 'direct_message_channel'
};

export function isSosNotification(type: NotificationType): boolean {
  return type === 'SOS_PROXIMITY';
}
