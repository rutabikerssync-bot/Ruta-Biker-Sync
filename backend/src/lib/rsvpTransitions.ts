/**
 * Maquina de estados del RSVP de una ruta de club.
 *
 * Los valores coinciden con el CHECK de `club_route_rsvps.status` (migracion 0017).
 * Modulo puro: sin imports, sin I/O, reutilizable por la validacion del backend y
 * como referencia de la misma verdad para el cliente Android.
 *
 * Regla clave: todo estado puede repetirse (idempotencia). `home` es terminal:
 * una vez llegado a casa no se reapunta sin pasar por una nueva ruta.
 */

export type RsvpStatus = 'pending' | 'confirmed' | 'en_route' | 'home' | 'no_go';

export const RSVP_STATUSES: readonly RsvpStatus[] = [
  'pending',
  'confirmed',
  'en_route',
  'home',
  'no_go'
] as const;

/** Estados que el cliente puede enviar explicitamente al endpoint de RSVP. */
export const RSVP_WRITABLE_STATUSES = ['confirmed', 'en_route', 'home', 'no_go'] as const;
export type RsvpWritableStatus = (typeof RSVP_WRITABLE_STATUSES)[number];

const ALLOWED_TRANSITIONS: Record<RsvpStatus, readonly RsvpStatus[]> = {
  pending: ['pending', 'confirmed', 'en_route', 'home', 'no_go'],
  confirmed: ['confirmed', 'en_route', 'home', 'no_go'],
  en_route: ['en_route', 'home', 'no_go'],
  home: ['home'],
  no_go: ['no_go', 'confirmed', 'en_route']
};

/**
 * Devuelve true si `from -> to` es una transicion permitida.
 * `from === to` siempre es permitido para garantizar reintentos idempotentes
 * (CRITICO para el outbox offline de la Fase 5).
 */
export function canTransition(from: RsvpStatus, to: RsvpStatus): boolean {
  return ALLOWED_TRANSITIONS[from]?.includes(to) ?? false;
}
