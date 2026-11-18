-- Migración 0015: Añade 'MF' (Moto Familia) al check constraint de tier en groups
ALTER TABLE groups DROP CONSTRAINT IF EXISTS groups_tier_check;
ALTER TABLE groups ADD CONSTRAINT groups_tier_check CHECK (tier IN ('RC', 'MF', 'MG', 'GANG', 'MC'));
