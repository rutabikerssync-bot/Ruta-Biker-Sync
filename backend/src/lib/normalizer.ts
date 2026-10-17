/**
 * Normalización y comparación de nombres de grupo por país (RULES.md R5.1 y BACKEND_LICENSING.md §11).
 *
 * Reglas (BACKEND_LICENSING.md §11):
 * 1. Minúsculas
 * 2. Quitar acentos / diacríticos (NFD)
 * 3. Eliminar espacios y símbolos
 * 4. Mapear sustituciones leet (4->a, 3->e, 1->l/i, 0->o, 5->s, 7->t)
 * 5. Comparar por igualdad exacta y por distancia de edición baja dentro del mismo país.
 */

const LEET_MAP: Record<string, string> = {
  '0': 'o',
  '1': 'i',
  '3': 'e',
  '4': 'a',
  '5': 's',
  '7': 't',
  '8': 'b'
};

export function normalizeGroupName(raw: string): string {
  return raw
    .trim()
    .toLowerCase()
    .replace(/@/g, 'a')
    .replace(/\$/g, 's')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]/g, '');
}

/**
 * Convierte sustituciones visuales (leet speak) y colapsa letras repetidas consecutivas.
 */
export function toLeetSkeleton(raw: string): string {
  const norm = normalizeGroupName(raw);
  let out = '';
  let prev = '';
  for (const ch of norm) {
    const mapped = LEET_MAP[ch] ?? ch;
    if (mapped !== prev) {
      out += mapped;
      prev = mapped;
    }
  }
  return out;
}

export const normalizeGroupNameSkeleton = toLeetSkeleton;

export function levenshteinDistance(a: string, b: string): number {
  if (a === b) return 0;
  if (a.length === 0) return b.length;
  if (b.length === 0) return a.length;

  const matrix: number[][] = Array.from({ length: a.length + 1 }, () =>
    new Array<number>(b.length + 1).fill(0)
  );

  for (let i = 0; i <= a.length; i++) matrix[i][0] = i;
  for (let j = 0; j <= b.length; j++) matrix[0][j] = j;

  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      matrix[i][j] = Math.min(
        matrix[i - 1][j] + 1,
        matrix[i][j - 1] + 1,
        matrix[i - 1][j - 1] + cost
      );
    }
  }
  return matrix[a.length][b.length];
}

export interface NameCollisionResult {
  collides: boolean;
  reason: 'EXACT_NORMALIZED' | 'LEET_EQUIVALENT' | 'NEAR_DUPLICATE' | null;
  distance: number;
}

export function checkGroupNameCollision(
  candidateRaw: string,
  existingRawOrNormalized: string
): NameCollisionResult {
  const normA = normalizeGroupName(candidateRaw);
  const normB = normalizeGroupName(existingRawOrNormalized);

  if (normA === normB) {
    return { collides: true, reason: 'EXACT_NORMALIZED', distance: 0 };
  }

  const skelA = toLeetSkeleton(candidateRaw);
  const skelB = toLeetSkeleton(existingRawOrNormalized);
  if (skelA.length > 0 && (skelA === skelB || skelA.replace(/l/g, 'i') === skelB.replace(/l/g, 'i'))) {
    return { collides: true, reason: 'LEET_EQUIVALENT', distance: 0 };
  }

  const dist = levenshteinDistance(skelA, skelB);
  const minLen = Math.min(skelA.length, skelB.length);
  if (minLen >= 5 && dist <= 1) {
    return { collides: true, reason: 'NEAR_DUPLICATE', distance: dist };
  }

  return { collides: false, reason: null, distance: dist };
}
