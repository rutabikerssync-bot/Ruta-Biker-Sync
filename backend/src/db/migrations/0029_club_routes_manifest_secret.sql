ALTER TABLE club_routes
  ADD COLUMN IF NOT EXISTS manifest_ride_secret_key_hex TEXT,
  ADD COLUMN IF NOT EXISTS manifest_issued_at TIMESTAMPTZ;

UPDATE club_routes
SET manifest_ride_secret_key_hex = md5(id::text || clock_timestamp()::text || random()::text)
                                || md5(random()::text || id::text),
    manifest_issued_at = COALESCE(manifest_issued_at, now())
WHERE manifest_ride_secret_key_hex IS NULL;
