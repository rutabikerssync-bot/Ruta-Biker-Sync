ALTER TABLE club_routes
  ADD COLUMN IF NOT EXISTS waypoints_json JSONB NULL;
