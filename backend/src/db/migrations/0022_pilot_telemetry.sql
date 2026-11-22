-- 0022_pilot_telemetry.sql
-- Telemetría del piloto: estado por dispositivo y eventos (latidos, errores, logs).
-- La identidad del dispositivo es la huella de su clave de piloto, nunca un UID
-- ni un dato de peer inventado. No se guardan coordenadas ni datos médicos.

CREATE TABLE IF NOT EXISTS pilot_devices (
  pilot_key_fp     TEXT PRIMARY KEY,
  pilot_build      TEXT NOT NULL,
  device_model     TEXT,
  device_brand     TEXT,
  android_version  TEXT,
  app_version      TEXT,
  git_commit       TEXT,
  git_dirty        BOOLEAN NOT NULL DEFAULT false,
  battery_pct      INTEGER,
  is_charging      BOOLEAN,
  peer_count       INTEGER,
  mesh_state       TEXT,
  last_error       TEXT,
  last_error_stack TEXT,
  first_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS pilot_device_events (
  id          UUID PRIMARY KEY,
  pilot_key_fp TEXT NOT NULL REFERENCES pilot_devices(pilot_key_fp) ON DELETE CASCADE,
  kind        TEXT NOT NULL CHECK (kind IN ('heartbeat', 'error', 'log_tail')),
  severity    TEXT NOT NULL DEFAULT 'info' CHECK (severity IN ('debug', 'info', 'warning', 'error', 'fatal')),
  message     TEXT,
  stack       TEXT,
  logs_tail   TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pilot_events_device_created
  ON pilot_device_events (pilot_key_fp, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_pilot_events_kind_created
  ON pilot_device_events (kind, created_at DESC);
