CREATE TABLE IF NOT EXISTS user_bike_garage (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  garage_state JSONB NOT NULL DEFAULT '{}'::jsonb,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
