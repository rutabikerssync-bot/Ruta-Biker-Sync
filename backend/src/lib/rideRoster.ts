import type { CredentialRoleKind, RideRoleKind } from './credentials.js';

export interface RideRosterRow {
  user_id: string;
  group_id: string | null;
  status_kind: string | null;
  ride_role: string | null;
  display_name: string | null;
  photo_url: string | null;
  map_color: string | null;
  bike_model: string | null;
}

export interface RideRosterParticipant {
  userId: string;
  groupId: string | null;
  statusKind: CredentialRoleKind;
  rideRole: RideRoleKind | null;
  roleInConvoy: 'Punta' | 'Scout' | 'Sweep' | 'Rider';
  displayName: string;
  avatarUrl: string | null;
  mapColor: string | null;
  bikeModel: string | null;
}

/**
 * Normaliza el rol almacenado en `ride_participants.ride_role` a las etiquetas
 * operativas que entiende la UI del convoy. El rol crudo se conserva en `rideRole`.
 * No se inventan datos: si no hay rol, se reporta `Rider`.
 */
export function normalizeRoleInConvoy(
  rideRole: string | null | undefined
): RideRosterParticipant['roleInConvoy'] {
  switch ((rideRole ?? '').trim().toLowerCase()) {
    case 'capitan_rodada':
      return 'Punta';
    case 'scout':
      return 'Scout';
    case 'sweep':
      return 'Sweep';
    default:
      return 'Rider';
  }
}

/**
 * Proyecta rows reales de `ride_participants JOIN users` al DTO del roster.
 * `photo_url` viene de la migración 0014; no se expone `photo_blob`.
 */
export function mapRideRosterRows(rows: RideRosterRow[]): RideRosterParticipant[] {
  return rows.map((row) => {
    const rawRole = row.ride_role as RideRoleKind | null | undefined;
    const displayName = String(row.display_name ?? '').trim();
    return {
      userId: String(row.user_id),
      groupId: row.group_id ? String(row.group_id) : null,
      statusKind: (row.status_kind ?? 'full') as CredentialRoleKind,
      rideRole: rawRole ?? null,
      roleInConvoy: normalizeRoleInConvoy(rawRole),
      displayName: displayName || 'Desconocido',
      avatarUrl: row.photo_url ? String(row.photo_url) : null,
      mapColor: row.map_color ? String(row.map_color) : null,
      bikeModel: row.bike_model ? String(row.bike_model) : null
    };
  });
}
