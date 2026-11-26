CREATE TABLE IF NOT EXISTS promo_banners (
  id               UUID PRIMARY KEY,
  title            TEXT NOT NULL,
  subtitle         TEXT NOT NULL DEFAULT '',
  badge_text       TEXT NOT NULL DEFAULT '',
  image_url        TEXT NOT NULL DEFAULT '',
  accent_color_hex TEXT NOT NULL DEFAULT '#E5B869',
  cta_label        TEXT NOT NULL DEFAULT 'Ver más',
  cta_target       TEXT NOT NULL DEFAULT '',
  frequency        TEXT NOT NULL DEFAULT 'once_per_day' CHECK (frequency IN ('once_per_campaign','once_per_day')),
  priority         INTEGER NOT NULL DEFAULT 0,
  active           BOOLEAN NOT NULL DEFAULT true,
  starts_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  ends_at          TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '7 days'),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_promo_banners_active_window
  ON promo_banners (active, starts_at, ends_at, priority DESC);
