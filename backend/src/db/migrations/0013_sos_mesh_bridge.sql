-- Migración 0013: Puente BLE Mesh a Radar SOS HTTP (Fase 7.1.5.5)
-- Idempotente: seguro para reejecución múltiple sin alterar integridad.

ALTER TABLE sos_events ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'app';
ALTER TABLE sos_events ADD COLUMN IF NOT EXISTS mesh_packet_id TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_sos_events_mesh_packet_id 
    ON sos_events (mesh_packet_id) 
    WHERE mesh_packet_id IS NOT NULL;
