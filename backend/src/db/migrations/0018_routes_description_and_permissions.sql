-- 0018_routes_description_and_permissions.sql
-- Fase 5 (Commit 1): descripcion de ruta, permisos de escritura para toda la mesa
-- directiva (group_leaders) y alineacion RLS/handler. Sin BEGIN/COMMIT (pool).

ALTER TABLE club_routes
  ADD COLUMN IF NOT EXISTS description TEXT NULL;

-- routes_write: antes restringia a is_principal=true; la regla de negocio permite
-- a cualquier lider del grupo (mesa directiva) crear/editar rutas. Se mantienen
-- intactas routes_read, rsvps_* y politicas de perfil/grupos.
DROP POLICY IF EXISTS routes_write ON club_routes;
CREATE POLICY routes_write ON club_routes FOR ALL
USING (EXISTS (
  SELECT 1 FROM group_leaders gl
  WHERE gl.group_id = club_routes.group_id
    AND gl.user_id = auth.uid()
))
WITH CHECK (EXISTS (
  SELECT 1 FROM group_leaders gl
  WHERE gl.group_id = club_routes.group_id
    AND gl.user_id = auth.uid()
));
