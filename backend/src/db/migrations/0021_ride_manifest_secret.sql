-- P0 auditoría 2026-11-21: clave de rodada estable y compartida.
-- El endpoint /v1/rides/:id/manifest generaba una clave aleatoria NUEVA en cada
-- petición, por lo que dos móviles del mismo club no podían autenticarse entre sí.
-- La clave se persiste por rodada y se entrega únicamente dentro del manifiesto
-- firmado con Ed25519 por el backend.

ALTER TABLE rides
    ADD COLUMN IF NOT EXISTS manifest_ride_secret_key_hex TEXT;
