-- P0 cierre final 2026-11-21: distribución firmada de claves públicas Ed25519 SOS.
-- Ed25519SosCrypto genera la clave en el móvil; el backend solo la persiste cuando
-- el propio usuario la publica. Nunca se fabrica una clave placeholder.
-- Se almacenan los 32 bytes crudos de la clave pública (no SPKI) para que el
-- manifiesto pueda entregarla como Base64 y Android la importe directamente.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS sos_public_key BYTEA;
