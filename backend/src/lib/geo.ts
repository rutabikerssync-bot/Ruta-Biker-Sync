/**
 * Utilidades geoespaciales canónicas para el Radar SOS de Proximidad 5 km (Fase 7.1.5).
 */

export interface GeoPoint {
  latitude: number;
  longitude: number;
}

/**
 * Calcula la distancia ortodrómica en kilómetros entre dos coordenadas usando la fórmula de Haversine.
 */
export function calculateHaversineDistanceKm(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number
): number {
  const R = 6371.0; // Radio medio de la Tierra en km
  const dLat = (lat2 - lat1) * (Math.PI / 180.0);
  const dLon = (lon2 - lon1) * (Math.PI / 180.0);
  const rLat1 = lat1 * (Math.PI / 180.0);
  const rLat2 = lat2 * (Math.PI / 180.0);

  const a =
    Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0) +
    Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
  const c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
  return R * c;
}

/**
 * Calcula el acimut (rumbo en grados [0, 360)) desde el punto de origen (from) hacia el destino (to).
 */
export function calculateBearingDegrees(
  fromLat: number,
  fromLon: number,
  toLat: number,
  toLon: number
): number {
  const dLon = (toLon - fromLon) * (Math.PI / 180.0);
  const lat1 = fromLat * (Math.PI / 180.0);
  const lat2 = toLat * (Math.PI / 180.0);

  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  const brng = Math.atan2(y, x) * (180.0 / Math.PI);
  return (brng + 360.0) % 360.0;
}

/**
 * Convierte un rumbo en grados a los 8 rumbos cardinales estándar con indicador visual.
 */
export function degreesToCardinal(deg: number): string {
  const normalized = ((deg % 360) + 360) % 360;
  if (normalized >= 337.5 || normalized < 22.5) return 'Norte ⬆';
  if (normalized >= 22.5 && normalized < 67.5) return 'Noreste ↗';
  if (normalized >= 67.5 && normalized < 112.5) return 'Este ➡';
  if (normalized >= 112.5 && normalized < 157.5) return 'Sureste ↘';
  if (normalized >= 157.5 && normalized < 202.5) return 'Sur ⬇';
  if (normalized >= 202.5 && normalized < 247.5) return 'Suroeste ↙';
  if (normalized >= 247.5 && normalized < 292.5) return 'Oeste ⬅';
  return 'Noroeste ↖';
}

/**
 * Genera el texto estándar de alerta para el banner y la notificación push sin exponer coordenadas exactas.
 */
export function formatSosAlertText(distanceKm: number, cardinal: string): string {
  const distFormatted = distanceKm.toFixed(1);
  return `🚨 SOS BIKER CERCANO — A ${distFormatted} km al ${cardinal}`;
}
