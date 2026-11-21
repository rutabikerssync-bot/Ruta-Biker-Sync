-- 0019_open_qr_invitations.sql
-- Las invitaciones abiertas compartidas por QR no tienen un destinatario hasta
-- que un piloto las acepta. El endpoint ya soporta invitedUserId ausente y
-- asigna invited_user durante la aceptación; alinear el esquema con ese flujo.
ALTER TABLE invitations
  ALTER COLUMN invited_user DROP NOT NULL;
