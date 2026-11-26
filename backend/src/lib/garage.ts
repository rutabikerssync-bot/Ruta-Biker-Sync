import { z } from 'zod';
import type { DbExecutor } from '../db/pool.js';

export const GarageUpsertSchema = z.object({
  garageState: z.record(z.unknown())
});

export interface UserGarageRecord {
  garageState: Record<string, unknown> | null;
  updatedAt: string | null;
}

export async function getUserBikeGarage(
  db: DbExecutor,
  userId: string
): Promise<UserGarageRecord> {
  const res = await db.query<{ garage_state: Record<string, unknown>; updated_at: string | Date }>(
    `SELECT garage_state, updated_at
     FROM user_bike_garage
     WHERE user_id = $1`,
    [userId]
  );
  const row = res.rows[0];
  if (!row) {
    return { garageState: null, updatedAt: null };
  }
  return {
    garageState: row.garage_state ?? null,
    updatedAt: row.updated_at instanceof Date ? row.updated_at.toISOString() : String(row.updated_at)
  };
}

export async function upsertUserBikeGarage(
  db: DbExecutor,
  userId: string,
  garageState: Record<string, unknown>
): Promise<UserGarageRecord> {
  const res = await db.query<{ garage_state: Record<string, unknown>; updated_at: string | Date }>(
    `INSERT INTO user_bike_garage (user_id, garage_state, updated_at)
     VALUES ($1, $2::jsonb, now())
     ON CONFLICT (user_id)
     DO UPDATE SET garage_state = EXCLUDED.garage_state, updated_at = now()
     RETURNING garage_state, updated_at`,
    [userId, JSON.stringify(garageState)]
  );
  const row = res.rows[0];
  return {
    garageState: row?.garage_state ?? garageState,
    updatedAt:
      row?.updated_at instanceof Date
        ? row.updated_at.toISOString()
        : row?.updated_at
          ? String(row.updated_at)
          : new Date().toISOString()
  };
}
