import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import type { DbExecutor } from '../db/pool.js';

export const PromoBannerCreateSchema = z.object({
  title: z.string().trim().min(2).max(120),
  subtitle: z.string().trim().max(260).default(''),
  badgeText: z.string().trim().max(40).default(''),
  imageUrl: z.string().trim().max(2000).default(''),
  accentColorHex: z
    .string()
    .trim()
    .regex(/^#[0-9A-Fa-f]{6}$/, 'Color hexadecimal inválido (#RRGGBB)')
    .default('#E5B869'),
  ctaLabel: z.string().trim().min(1).max(40).default('Ver más'),
  ctaTarget: z.string().trim().max(500).default(''),
  frequency: z.enum(['once_per_campaign', 'once_per_day']).default('once_per_day'),
  priority: z.number().int().min(-100).max(1000).default(0),
  active: z.boolean().default(true),
  startsAt: z.string().datetime().optional(),
  endsAt: z.string().datetime().optional()
});

export const PromoBannerUpdateSchema = PromoBannerCreateSchema.partial();

export interface PromoBannerRecord {
  id: string;
  title: string;
  subtitle: string;
  badgeText: string;
  imageUrl: string;
  accentColorHex: string;
  ctaLabel: string;
  ctaTarget: string;
  frequency: 'once_per_campaign' | 'once_per_day';
  priority: number;
  active: boolean;
  startsAt: string;
  endsAt: string;
  createdAt: string;
  updatedAt: string;
}

function mapBannerRow(row: any): PromoBannerRecord {
  return {
    id: row.id,
    title: row.title,
    subtitle: row.subtitle ?? '',
    badgeText: row.badge_text ?? '',
    imageUrl: row.image_url ?? '',
    accentColorHex: row.accent_color_hex ?? '#E5B869',
    ctaLabel: row.cta_label ?? 'Ver más',
    ctaTarget: row.cta_target ?? '',
    frequency: row.frequency === 'once_per_campaign' ? 'once_per_campaign' : 'once_per_day',
    priority: Number(row.priority ?? 0),
    active: Boolean(row.active),
    startsAt: row.starts_at instanceof Date ? row.starts_at.toISOString() : String(row.starts_at),
    endsAt: row.ends_at instanceof Date ? row.ends_at.toISOString() : String(row.ends_at),
    createdAt: row.created_at instanceof Date ? row.created_at.toISOString() : String(row.created_at),
    updatedAt: row.updated_at instanceof Date ? row.updated_at.toISOString() : String(row.updated_at)
  };
}

export async function listActivePromoBanners(db: DbExecutor): Promise<PromoBannerRecord[]> {
  const res = await db.query(
    `SELECT *
     FROM promo_banners
     WHERE active = true
       AND starts_at <= now()
       AND ends_at >= now()
     ORDER BY priority DESC, created_at DESC
     LIMIT 10`
  );
  return res.rows.map(mapBannerRow);
}

export async function listAllPromoBannersAdmin(db: DbExecutor): Promise<PromoBannerRecord[]> {
  const res = await db.query(
    `SELECT *
     FROM promo_banners
     ORDER BY priority DESC, created_at DESC`
  );
  return res.rows.map(mapBannerRow);
}

export async function createPromoBanner(
  db: DbExecutor,
  input: z.infer<typeof PromoBannerCreateSchema>
): Promise<PromoBannerRecord> {
  const id = randomUUID();
  const res = await db.query(
    `INSERT INTO promo_banners
       (id, title, subtitle, badge_text, image_url, accent_color_hex, cta_label, cta_target, frequency, priority, active, starts_at, ends_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, COALESCE($12::timestamptz, now()), COALESCE($13::timestamptz, now() + interval '7 days'))
     RETURNING *`,
    [
      id,
      input.title,
      input.subtitle,
      input.badgeText,
      input.imageUrl,
      input.accentColorHex,
      input.ctaLabel,
      input.ctaTarget,
      input.frequency,
      input.priority,
      input.active,
      input.startsAt ?? null,
      input.endsAt ?? null
    ]
  );
  return mapBannerRow(res.rows[0]);
}

export async function updatePromoBanner(
  db: DbExecutor,
  id: string,
  input: z.infer<typeof PromoBannerUpdateSchema>
): Promise<PromoBannerRecord | null> {
  const existing = await db.query(`SELECT * FROM promo_banners WHERE id = $1`, [id]);
  const cur = existing.rows[0];
  if (!cur) return null;

  const res = await db.query(
    `UPDATE promo_banners
     SET title = $2,
         subtitle = $3,
         badge_text = $4,
         image_url = $5,
         accent_color_hex = $6,
         cta_label = $7,
         cta_target = $8,
         frequency = $9,
         priority = $10,
         active = $11,
         starts_at = $12,
         ends_at = $13,
         updated_at = now()
     WHERE id = $1
     RETURNING *`,
    [
      id,
      input.title ?? cur.title,
      input.subtitle ?? cur.subtitle,
      input.badgeText ?? cur.badge_text,
      input.imageUrl ?? cur.image_url,
      input.accentColorHex ?? cur.accent_color_hex,
      input.ctaLabel ?? cur.cta_label,
      input.ctaTarget ?? cur.cta_target,
      input.frequency ?? cur.frequency,
      input.priority ?? cur.priority,
      input.active ?? cur.active,
      input.startsAt ?? cur.starts_at,
      input.endsAt ?? cur.ends_at
    ]
  );
  return mapBannerRow(res.rows[0]);
}

export async function deletePromoBanner(db: DbExecutor, id: string): Promise<boolean> {
  const res = await db.query(`DELETE FROM promo_banners WHERE id = $1`, [id]);
  return (res.rowCount ?? 0) > 0;
}
