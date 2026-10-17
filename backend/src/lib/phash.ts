/**
 * Comparación de huellas perceptuales (pHash/dHash de 64 bits = 8 bytes) de logos de grupo por país
 * (RULES.md R5.2 y BACKEND_LICENSING.md §11).
 */

export const LOGO_PHASH_REJECT_DISTANCE = 7;       // < 8 bits (0..7) de diferencia de 64: rechazo directo
export const LOGO_PHASH_REVIEW_DISTANCE = 12;      // 8..12 bits de diferencia: pasa con marca de revisión manual

const POPCOUNT_BYTE = new Uint8Array(256);
for (let i = 0; i < 256; i++) {
  let v = i;
  let c = 0;
  while (v > 0) {
    v &= v - 1;
    c++;
  }
  POPCOUNT_BYTE[i] = c;
}

export function hammingDistance(a: Buffer, b: Buffer): number {
  if (a.length !== b.length || a.length === 0) {
    throw new Error(`Invalid pHash length: expected equal non-zero lengths, got ${a.length} and ${b.length}`);
  }
  let distance = 0;
  for (let i = 0; i < a.length; i++) {
    distance += POPCOUNT_BYTE[a[i] ^ b[i]];
  }
  return distance;
}

export const computePhashHammingDistance = hammingDistance;

export interface LogoSimilarityCheck {
  rejected: boolean;
  needsManualReview: boolean;
  closestGroupId?: string;
  minHammingDistance: number;
}

export function evaluateLogoUniqueness(
  candidatePhash: Buffer,
  sameCountryGroups: Array<{ id: string; logoPhash: Buffer }>
): LogoSimilarityCheck {
  let minDistance = Number.POSITIVE_INFINITY;
  let closestGroupId: string | undefined;

  for (const existing of sameCountryGroups) {
    if (existing.logoPhash.length !== candidatePhash.length) continue;
    const dist = hammingDistance(candidatePhash, existing.logoPhash);
    if (dist < minDistance) {
      minDistance = dist;
      closestGroupId = existing.id;
    }
  }

  if (minDistance <= LOGO_PHASH_REJECT_DISTANCE) {
    return {
      rejected: true,
      needsManualReview: false,
      closestGroupId,
      minHammingDistance: minDistance
    };
  }

  if (minDistance <= LOGO_PHASH_REVIEW_DISTANCE) {
    return {
      rejected: false,
      needsManualReview: true,
      closestGroupId,
      minHammingDistance: minDistance
    };
  }

  return {
    rejected: false,
    needsManualReview: false,
    closestGroupId,
    minHammingDistance: minDistance
  };
}
