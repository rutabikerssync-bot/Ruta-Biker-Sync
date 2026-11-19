-- 0017_club_routes_and_profile_version.sql
-- Perfil versionado (espejo temporal de 0016, Decision D2) + rutas y asistencia de club.
-- rbs es owner no-super/no-bypassrls => FORCE ROW LEVEL SECURITY es obligatorio.
-- No incluir BEGIN/COMMIT/ROLLBACK: node-pg-migrate ya envuelve cada migracion
-- en una transaccion y un ROLLBACK interno revertiria todo el DDL.

ALTER TABLE groups
  ADD COLUMN IF NOT EXISTS club_profile JSONB NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN IF NOT EXISTS profile_version INT NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS profile_updated_by UUID NULL REFERENCES users(id),
  ADD COLUMN IF NOT EXISTS profile_updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE IF NOT EXISTS club_routes (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id     UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  created_by   UUID NOT NULL REFERENCES users(id),
  title        TEXT NOT NULL,
  route_ref    TEXT NULL,
  starts_at    TIMESTAMPTZ NULL,
  ends_at      TIMESTAMPTZ NULL,
  status       TEXT NOT NULL DEFAULT 'published'
               CHECK (status IN ('draft','published','active','completed','cancelled')),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_club_routes_group ON club_routes(group_id, status);

CREATE TABLE IF NOT EXISTS club_route_rsvps (
  route_id     UUID NOT NULL REFERENCES club_routes(id) ON DELETE CASCADE,
  user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status       TEXT NOT NULL DEFAULT 'pending'
               CHECK (status IN ('pending','confirmed','en_route','home','no_go')),
  responded_at TIMESTAMPTZ NULL,
  home_at      TIMESTAMPTZ NULL,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (route_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_club_route_rsvps_user ON club_route_rsvps(user_id);

-- auth.uid() adaptado a Fastify: lee el UID inyectado por transaccion via withUserDb.
CREATE SCHEMA IF NOT EXISTS auth;
CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT NULLIF(current_setting('app.user_id', true), '')::uuid;
$$;

ALTER TABLE club_routes ENABLE ROW LEVEL SECURITY;
ALTER TABLE club_routes FORCE ROW LEVEL SECURITY;
ALTER TABLE club_route_rsvps ENABLE ROW LEVEL SECURITY;
ALTER TABLE club_route_rsvps FORCE ROW LEVEL SECURITY;

-- CREATE POLICY no admite IF NOT EXISTS: se eliminan primero para idempotencia.
DROP POLICY IF EXISTS routes_read      ON club_routes;
DROP POLICY IF EXISTS routes_write     ON club_routes;
DROP POLICY IF EXISTS rsvps_read       ON club_route_rsvps;
DROP POLICY IF EXISTS rsvps_self_write ON club_route_rsvps;

-- Columnas reales del esquema: invite_state='accepted' e is_principal.
CREATE POLICY routes_read ON club_routes FOR SELECT
USING (EXISTS (
  SELECT 1 FROM memberships m
  WHERE m.group_id = club_routes.group_id
    AND m.user_id = auth.uid()
    AND m.invite_state = 'accepted'
));

CREATE POLICY routes_write ON club_routes FOR ALL
USING (EXISTS (
  SELECT 1 FROM group_leaders gl
  WHERE gl.group_id = club_routes.group_id
    AND gl.user_id = auth.uid()
    AND gl.is_principal = true
))
WITH CHECK (EXISTS (
  SELECT 1 FROM group_leaders gl
  WHERE gl.group_id = club_routes.group_id
    AND gl.user_id = auth.uid()
    AND gl.is_principal = true
));

CREATE POLICY rsvps_read ON club_route_rsvps FOR SELECT
USING (EXISTS (
  SELECT 1 FROM club_routes r
  JOIN memberships m ON m.group_id = r.group_id
  WHERE r.id = club_route_rsvps.route_id
    AND m.user_id = auth.uid()
    AND m.invite_state = 'accepted'
));

CREATE POLICY rsvps_self_write ON club_route_rsvps FOR ALL
USING (user_id = auth.uid())
WITH CHECK (user_id = auth.uid());
