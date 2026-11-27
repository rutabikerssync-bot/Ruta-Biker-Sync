UPDATE club_routes r
SET completed_at = COALESCE(r.completed_at, now()),
    status = 'completed',
    updated_at = now()
WHERE r.completed_at IS NULL
  AND r.return_started_at IS NOT NULL
  AND EXISTS (
    SELECT 1 FROM club_route_rsvps v
    WHERE v.route_id = r.id AND v.status = 'home'
  )
  AND NOT EXISTS (
    SELECT 1 FROM club_route_rsvps v
    WHERE v.route_id = r.id AND v.status IN ('confirmed', 'en_route')
  );
