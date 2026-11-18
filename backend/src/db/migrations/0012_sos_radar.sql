-- Migración 0012: Radar SOS de Proximidad 5 km y Eventos SOS (Fase 7.1.5)

-- Índices B-tree optimizados para Bounding Box y filtro temporal sobre ephemeral_telemetry existente
CREATE INDEX IF NOT EXISTS idx_ephemeral_telemetry_coords ON ephemeral_telemetry (lat, lng);
CREATE INDEX IF NOT EXISTS idx_ephemeral_telemetry_user_time ON ephemeral_telemetry (user_id, recorded_at DESC);

-- 2. Tabla de eventos SOS
CREATE TABLE IF NOT EXISTS sos_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  latitude DOUBLE PRECISION NOT NULL,
  longitude DOUBLE PRECISION NOT NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'resolved', 'cancelled')),
  has_medical_sheet BOOLEAN NOT NULL DEFAULT FALSE,
  radius_km DOUBLE PRECISION NOT NULL DEFAULT 5.0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  resolved_at TIMESTAMPTZ,
  resolved_by UUID REFERENCES users(id),
  notes TEXT
);

CREATE INDEX IF NOT EXISTS idx_sos_events_status_created ON sos_events (status, created_at);
CREATE INDEX IF NOT EXISTS idx_sos_events_coords ON sos_events (latitude, longitude);
