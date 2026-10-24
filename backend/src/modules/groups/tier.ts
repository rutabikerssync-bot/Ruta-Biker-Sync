import { pool, type DbExecutor } from '../../db/pool.js';

export interface TierEligibility {
  current_tier: string;
  eligible_for: string | null;
  requirements: {
    years_required: number;
    years_actual: number;
    sponsor_required: boolean;
    sponsor_present: boolean;
  };
}

/**
 * Evalúa si un grupo cumple los requisitos para subir de nivel.
 * Reglas:
 * - RC → MG: 1-3 años de antigüedad + padrino MC certificado
 * - MG → Gang: 3 años + padrino MC certificado
 * - Gang → MC: 7 años + padrino MC certificado + certificación
 */
export async function evaluateTierEligibility(
  groupId: string,
  db: DbExecutor = pool
): Promise<TierEligibility> {
  const { rows: [group] } = await db.query(
    `SELECT id, tier, legacy_since, is_certified, sponsor_group_id, created_at
     FROM groups WHERE id = $1`,
    [groupId]
  );

  if (!group) throw new Error('Grupo no encontrado');

  const foundedDate = group.legacy_since || group.created_at;
  const yearsActual = (Date.now() - new Date(foundedDate).getTime()) / (1000 * 60 * 60 * 24 * 365);

  const rules: Record<string, { years: number; needsSponsor: boolean }> = {
    RC: { years: 1, needsSponsor: true },
    MG: { years: 3, needsSponsor: true },
    Gang: { years: 7, needsSponsor: true },
    MC: { years: 0, needsSponsor: false }, // MC no sube más
  };

  const nextTier: Record<string, string | null> = {
    RC: 'MG',
    MG: 'Gang',
    Gang: 'MC',
    MC: null,
  };

  const target = nextTier[group.tier];
  if (!target) {
    return {
      current_tier: group.tier,
      eligible_for: null,
      requirements: {
        years_required: 0,
        years_actual: Math.floor(yearsActual),
        sponsor_required: false,
        sponsor_present: !!group.sponsor_group_id
      }
    };
  }

  const rule = rules[group.tier];
  const eligible = yearsActual >= rule.years
    && (!rule.needsSponsor || !!group.sponsor_group_id)
    && group.is_certified;

  return {
    current_tier: group.tier,
    eligible_for: eligible ? target : null,
    requirements: {
      years_required: rule.years,
      years_actual: Math.floor(yearsActual),
      sponsor_required: rule.needsSponsor,
      sponsor_present: !!group.sponsor_group_id,
    }
  };
}

/**
 * Cuenta cuántos clubes certificados (mutual_founder) hay en una región.
 * Cuando llega a 7, el núcleo fundador está completo.
 */
export async function evaluateFounderNucleus(
  countryCode: string,
  db: DbExecutor = pool
): Promise<{ count: number; complete: boolean }> {
  const { rows } = await db.query(
    `SELECT COUNT(*)::int AS count FROM group_certifications gc
     JOIN groups g ON g.id = gc.group_id
     WHERE g.country_code = $1
       AND gc.certification_type = 'mutual_founder'
       AND gc.revoked_at IS NULL`,
    [countryCode]
  );
  return { count: rows[0].count, complete: rows[0].count >= 7 };
}
