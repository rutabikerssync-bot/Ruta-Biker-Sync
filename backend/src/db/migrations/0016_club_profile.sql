-- 0016_club_profile.sql
-- Columnas de Identidad y Tradición Biker para grupos/clubes

ALTER TABLE groups
    ADD COLUMN IF NOT EXISTS foundation_date DATE DEFAULT '2018-05-15',
    ADD COLUMN IF NOT EXISTS colors_description TEXT DEFAULT 'Negro y Oro',
    ADD COLUMN IF NOT EXISTS club_siglas TEXT DEFAULT 'MC',
    ADD COLUMN IF NOT EXISTS tradition_type TEXT DEFAULT 'Moto Club (MC - Tradición 1%er / Clásica)',
    ADD COLUMN IF NOT EXISTS directiva_scope TEXT DEFAULT 'Mesa Directiva Nacional',
    ADD COLUMN IF NOT EXISTS directiva_region TEXT DEFAULT 'Venezuela',
    ADD COLUMN IF NOT EXISTS motto TEXT DEFAULT 'Hermandad, Respeto y Carretera',
    ADD COLUMN IF NOT EXISTS city TEXT;
