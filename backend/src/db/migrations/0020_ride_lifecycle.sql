-- 0020_ride_lifecycle.sql
-- Ciclo operativo de rodada y presencia en el punto de encuentro.
-- No altera columnas ni el CHECK de status existentes.

ALTER TABLE club_routes
  ADD COLUMN IF NOT EXISTS outbound_started_at TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS destination_reached_at TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS return_started_at TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ NULL;

ALTER TABLE club_route_rsvps
  ADD COLUMN IF NOT EXISTS on_site_at TIMESTAMPTZ NULL;

CREATE INDEX IF NOT EXISTS idx_club_route_rsvps_route_on_site
  ON club_route_rsvps (route_id, on_site_at);

-- La mesa directiva también debe leer sus propias rutas para operar el ciclo.
-- Se mantiene el aislamiento: miembros aceptados o líderes DEL MISMO club únicamente.
DROP POLICY IF EXISTS routes_read ON club_routes;
CREATE POLICY routes_read ON club_routes FOR SELECT
USING (
  EXISTS (
    SELECT 1 FROM memberships m
    WHERE m.group_id = club_routes.group_id
      AND m.user_id = auth.uid()
      AND m.invite_state = 'accepted'
  )
  OR EXISTS (
    SELECT 1 FROM group_leaders gl
    WHERE gl.group_id = club_routes.group_id
      AND gl.user_id = auth.uid()
  )
);
