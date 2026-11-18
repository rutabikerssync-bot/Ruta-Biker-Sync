-- =============================================================================
-- Migración 0010: Hardening Backend Fase 7.1.1
-- Rate Limiting (/v1/auth, /v1/invites) y Telemetría Efímera RGPD (24h)
-- =============================================================================

CREATE TABLE IF NOT EXISTS rate_limit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope VARCHAR(32) NOT NULL, -- 'auth' | 'invite'
    ip VARCHAR(64) NOT NULL,
    user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rate_limit_events_lookup ON rate_limit_events (scope, ip, user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rate_limit_events_time ON rate_limit_events (created_at DESC);

-- Tabla de telemetría y coordenadas efímeras con purga obligatoria RGPD a las 24h
CREATE TABLE IF NOT EXISTS ephemeral_telemetry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id UUID REFERENCES rides(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    lat DOUBLE PRECISION NOT NULL,
    lng DOUBLE PRECISION NOT NULL,
    altitude REAL,
    speed REAL,
    heading REAL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ephemeral_telemetry_time ON ephemeral_telemetry (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ephemeral_telemetry_ride ON ephemeral_telemetry (ride_id, created_at DESC);
