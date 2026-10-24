/**
 * Cargos del club, permisos delegados por defecto (RULES.md R3) y reglas de mayoría de líderes (RULES.md R14).
 */

export const CLUB_ROLES = [
  'presidente',
  'vice',
  'capitan_ruta',
  'sargento_armas',
  'secretario',
  'tesorero',
  'road_marshal'
] as const;

export type ClubRole = (typeof CLUB_ROLES)[number];

export const MEMBER_STATUS_KINDS = ['full', 'prospect', 'guest'] as const;
export type MemberStatusKind = (typeof MEMBER_STATUS_KINDS)[number];

export interface RolePermissionSet {
  honoraryTitle: boolean;
  canProposeRoutesAndOrder: boolean;
  canManageRideSafetySignals: boolean;
  canTrackEventAttendanceAndOrder: boolean;
  canViewAttendanceAndHistory: boolean;
  canViewPaymentTrafficLightReadOnly: boolean;
}

export const DEFAULT_ROLE_PERMISSIONS: Record<ClubRole, RolePermissionSet> = {
  presidente: {
    honoraryTitle: true,
    canProposeRoutesAndOrder: false,
    canManageRideSafetySignals: false,
    canTrackEventAttendanceAndOrder: false,
    canViewAttendanceAndHistory: false,
    canViewPaymentTrafficLightReadOnly: false
  },
  vice: {
    honoraryTitle: true,
    canProposeRoutesAndOrder: false,
    canManageRideSafetySignals: false,
    canTrackEventAttendanceAndOrder: false,
    canViewAttendanceAndHistory: false,
    canViewPaymentTrafficLightReadOnly: false
  },
  capitan_ruta: {
    honoraryTitle: false,
    canProposeRoutesAndOrder: true,
    canManageRideSafetySignals: false,
    canTrackEventAttendanceAndOrder: false,
    canViewAttendanceAndHistory: false,
    canViewPaymentTrafficLightReadOnly: false
  },
  road_marshal: {
    honoraryTitle: false,
    canProposeRoutesAndOrder: false,
    canManageRideSafetySignals: true,
    canTrackEventAttendanceAndOrder: false,
    canViewAttendanceAndHistory: false,
    canViewPaymentTrafficLightReadOnly: false
  },
  sargento_armas: {
    honoraryTitle: false,
    canProposeRoutesAndOrder: false,
    canManageRideSafetySignals: false,
    canTrackEventAttendanceAndOrder: true,
    canViewAttendanceAndHistory: false,
    canViewPaymentTrafficLightReadOnly: false
  },
  secretario: {
    honoraryTitle: false,
    canProposeRoutesAndOrder: false,
    canManageRideSafetySignals: false,
    canTrackEventAttendanceAndOrder: false,
    canViewAttendanceAndHistory: true,
    canViewPaymentTrafficLightReadOnly: false
  },
  tesorero: {
    honoraryTitle: false,
    canProposeRoutesAndOrder: false,
    canManageRideSafetySignals: false,
    canTrackEventAttendanceAndOrder: false,
    canViewAttendanceAndHistory: false,
    canViewPaymentTrafficLightReadOnly: true
  }
};

/**
 * Calcula los votos necesarios para aprobar una decisión sensible según RULES.md R14.2 y R14.4:
 * - Con 3 líderes elegibles: mayoría = 2
 * - Con 2 líderes elegibles: ambos = 2
 * - Con 1 líder elegible: él solo = 1
 * - R14.4: Un líder no puede votar su propia sanción (se descuenta de los elegibles).
 */
export function requiredLeaderVotesForMajority(eligibleLeadersCount: number): number {
  if (eligibleLeadersCount <= 1) return 1;
  return 2; // tanto para 2 líderes (ambos) como para 3 líderes (2 de 3)
}

/**
 * Calcula el semáforo de pagos (RULES R7.2 y BACKEND_LICENSING §8):
 * - 'green': al día y vence en >= 7 días
 * - 'yellow': vence en < 7 días o está en periodo de gracia vigente
 * - 'red': sin suscripción o vencido
 */
export type PaymentSemaphore = 'green' | 'yellow' | 'red';

export function computePaymentSemaphore(
  paidUntil: Date | null | undefined,
  inGraceUntil: Date | null | undefined,
  now: Date = new Date()
): PaymentSemaphore {
  if (inGraceUntil && inGraceUntil.getTime() > now.getTime()) {
    return 'yellow';
  }
  if (!paidUntil || paidUntil.getTime() <= now.getTime()) {
    return 'red';
  }
  const sevenDaysMs = 7 * 24 * 60 * 60 * 1000;
  if (paidUntil.getTime() - now.getTime() < sevenDaysMs) {
    return 'yellow';
  }
  return 'green';
}

/**
 * Regla P7 APROBADA (RULES.md §3 y §12, MEMORY.md D42):
 * Los invitados (`guest`) NO reciben cargos honoríficos del club (`presidente`, `vice`, etc.).
 * Solo `full` y `prospect` pueden recibir cargos honoríficos.
 */
export const P7_FORBID_GUEST_CLUB_ROLES: boolean =
  process.env.FORBID_GUEST_CLUB_ROLES !== 'false';

export function canMemberHoldClubRole(
  statusKind: MemberStatusKind,
  clubRole: ClubRole | null | undefined,
  forbidGuestRoles: boolean = P7_FORBID_GUEST_CLUB_ROLES
): boolean {
  if (!clubRole) return true;
  if (statusKind === 'guest' && forbidGuestRoles) {
    return false;
  }
  return true;
}

