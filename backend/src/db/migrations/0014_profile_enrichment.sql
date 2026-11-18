-- =============================================================================
-- Ruta Biker Sync — Migración 0014_profile_enrichment.sql
-- Módulos: Enriquecimiento de Perfil de Piloto, Datos de la Motocicleta,
--          Visibilidad / Privacidad, Almacenamiento de Fotos (BYTEA) y Reportes de Soporte
-- =============================================================================

BEGIN;

-- 1. Enriquecimiento de la tabla users
ALTER TABLE users ADD COLUMN IF NOT EXISTS photo_url TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS photo_blob BYTEA;

ALTER TABLE users ADD COLUMN IF NOT EXISTS motorcycle_brand TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS motorcycle_model TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS motorcycle_displacement INTEGER;
ALTER TABLE users ADD COLUMN IF NOT EXISTS motorcycle_year INTEGER;
ALTER TABLE users ADD COLUMN IF NOT EXISTS motorcycle_color TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS motorcycle_serial TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS motorcycle_photo_url TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS motorcycle_photo_blob BYTEA;

ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_visibility TEXT NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE users ADD COLUMN IF NOT EXISTS motorcycle_visibility TEXT NOT NULL DEFAULT 'PUBLIC';

-- 2. Tabla de Reportes de Soporte Técnico y Bugs
CREATE TABLE IF NOT EXISTS support_reports (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
  subject         TEXT NOT NULL,
  message         TEXT NOT NULL,
  screenshot_blob BYTEA,
  screenshot_url  TEXT,
  status          TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_progress', 'resolved', 'closed')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_support_reports_user ON support_reports (user_id);
CREATE INDEX IF NOT EXISTS idx_support_reports_created ON support_reports (created_at DESC);

COMMIT;
