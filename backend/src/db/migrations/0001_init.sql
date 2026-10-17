-- =============================================================================
-- Ruta Biker Sync — Migración 0001_init.sql
-- Fuente: BACKEND_LICENSING.md §4 + reglas de RULES.md (R1..R15) y D32/D33
-- IMPORTANTE: El backend NUNCA guarda ubicación, audio, documentos ni datos de salud.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Usuarios (RULES R1.1..R1.3; D35: password_hash scrypt N=16384,r=8,p=1)
CREATE TABLE IF NOT EXISTS users (
  id              UUID PRIMARY KEY,
  email           CITEXT UNIQUE NOT NULL,
  password_hash   TEXT,                  -- hash scrypt (scrypt$saltHex$keyHex, D35)
  display_name    TEXT,
  country_code    CHAR(2) NOT NULL,
  account_type    TEXT NOT NULL CHECK (account_type IN ('member','leader')),
  public_key      BYTEA,                 -- clave pública del dispositivo
  avatar_webp     BYTEA,                 -- avatar 64x64 WebP (~3 KB, DESIGN.md §7)
  bike_model      TEXT,                  -- moto del perfil (RULES R1.3)
  map_color       TEXT,                  -- color en el mapa (RULES R1.3)
  status          TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended','deleted')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 2. Suscripciones (reflejo de Play Billing; ver D30/D32: kind IN ('leader','member_slot'))
CREATE TABLE IF NOT EXISTS subscriptions (
  id                  UUID PRIMARY KEY,
  user_id             UUID NOT NULL REFERENCES users(id),
  kind                TEXT NOT NULL CHECK (kind IN ('leader','member_slot')),
  play_product_id     TEXT NOT NULL,       -- p. ej. leader_monthly, member_slot_1..5
  play_purchase_token TEXT UNIQUE NOT NULL,
  state               TEXT NOT NULL CHECK (state IN ('active','grace','on_hold','canceled','expired','refunded')),
  current_period_end  TIMESTAMPTZ NOT NULL,
  grace_until         TIMESTAMPTZ,
  auto_renewing       BOOLEAN NOT NULL DEFAULT true,
  assigned_group_id   UUID,                -- solo para member_slot
  last_verified_at    TIMESTAMPTZ NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. Grupos (RULES R5.1, R5.2, R3.3)
CREATE TABLE IF NOT EXISTS groups (
  id               UUID PRIMARY KEY,
  country_code     CHAR(2) NOT NULL,
  name             TEXT NOT NULL,
  name_normalized  TEXT NOT NULL,         -- sin acentos, sin símbolos, sustituciones leet
  logo_url         TEXT,
  logo_phash       BYTEA NOT NULL,        -- huella perceptual de 64 bits (8 bytes)
  principal_leader UUID NOT NULL REFERENCES users(id),
  role_permissions JSONB,                 -- permisos configurables por cargo (RULES R3.3)
  status           TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended','transfer_pending','deleted')),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (country_code, name_normalized)
);

-- 4. Líderes del grupo (máximo 3, exactamente 1 principal: RULES R4.1, R4.3)
CREATE TABLE IF NOT EXISTS group_leaders (
  group_id     UUID NOT NULL REFERENCES groups(id),
  user_id      UUID NOT NULL REFERENCES users(id),
  is_principal BOOLEAN NOT NULL DEFAULT false,
  added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, user_id)
);

-- Índice único parcial: a lo sumo 1 líder principal por grupo
CREATE UNIQUE INDEX IF NOT EXISTS idx_group_leaders_one_principal
  ON group_leaders (group_id)
  WHERE is_principal = true;

-- Trigger que limita a 3 líderes por group_id (RULES R4.1)
CREATE OR REPLACE FUNCTION enforce_max_three_group_leaders()
RETURNS TRIGGER AS $$
DECLARE
  current_count INT;
BEGIN
  SELECT COUNT(*) INTO current_count
  FROM group_leaders
  WHERE group_id = NEW.group_id;

  IF current_count >= 3 THEN
    RAISE EXCEPTION 'MAX_LEADERS_EXCEEDED: Un grupo no puede tener más de 3 líderes (RULES R4.1)';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_max_three_group_leaders ON group_leaders;
DROP TRIGGER IF EXISTS trg_enforce_max_three_leaders ON group_leaders;
CREATE TRIGGER trg_enforce_max_three_leaders
  BEFORE INSERT ON group_leaders
  FOR EACH ROW
  EXECUTE FUNCTION enforce_max_three_group_leaders();

-- 5. Membresías (RULES R2, R3)
CREATE TABLE IF NOT EXISTS memberships (
  id            UUID PRIMARY KEY,
  group_id      UUID NOT NULL REFERENCES groups(id),
  user_id       UUID NOT NULL REFERENCES users(id),
  status_kind   TEXT NOT NULL CHECK (status_kind IN ('full','prospect','guest')),
  club_role     TEXT CHECK (club_role IS NULL OR club_role IN (
                  'presidente','vice','capitan_ruta','sargento_armas',
                  'secretario','tesorero','road_marshal'
                )),
  invite_state  TEXT NOT NULL CHECK (invite_state IN ('pending','accepted','declined','left','removed')),
  accepted_at   TIMESTAMPTZ,
  paid_until    TIMESTAMPTZ,              -- derivado de la suscripción asignada
  UNIQUE (group_id, user_id)
);

-- Regla R2: un usuario con status_kind en ('full','prospect') y invite_state='accepted'
-- solo puede tener UNA membresía así (índice único parcial).
DROP INDEX IF EXISTS idx_memberships_single_full_or_prospect;
CREATE UNIQUE INDEX IF NOT EXISTS idx_memberships_one_full_or_prospect
  ON memberships (user_id)
  WHERE status_kind IN ('full','prospect') AND invite_state = 'accepted';

-- 6. Invitaciones (RULES R2.1)
CREATE TABLE IF NOT EXISTS invitations (
  id           UUID PRIMARY KEY,
  group_id     UUID NOT NULL REFERENCES groups(id),
  invited_user UUID NOT NULL REFERENCES users(id),
  invited_by   UUID NOT NULL REFERENCES users(id),
  status_kind  TEXT NOT NULL CHECK (status_kind IN ('full','prospect','guest')),
  state        TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','accepted','declined','expired')),
  expires_at   TIMESTAMPTZ NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 7. Transferencias de grupo (RULES R5.3)
CREATE TABLE IF NOT EXISTS group_transfers (
  id            UUID PRIMARY KEY,
  group_id      UUID NOT NULL REFERENCES groups(id),
  from_user     UUID NOT NULL REFERENCES users(id),
  to_user       UUID NOT NULL REFERENCES users(id),
  state         TEXT NOT NULL CHECK (state IN ('pending','accepted','canceled','completed')),
  executes_at   TIMESTAMPTZ NOT NULL,     -- creación + 7 días
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 8. Rodadas (solo metadatos, sin ubicaciones: RULES R6, R10.4)
CREATE TABLE IF NOT EXISTS rides (
  id            UUID PRIMARY KEY,
  host_group    UUID NOT NULL REFERENCES groups(id),
  host_leader   UUID NOT NULL REFERENCES users(id),
  title         TEXT,
  route_hash    TEXT,
  state         TEXT NOT NULL CHECK (state IN ('planned','active','closed','validated','rejected')),
  starts_at     TIMESTAMPTZ,
  closed_at     TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS ride_groups (
  ride_id  UUID NOT NULL REFERENCES rides(id),
  group_id UUID NOT NULL REFERENCES groups(id),
  PRIMARY KEY (ride_id, group_id)
);

CREATE TABLE IF NOT EXISTS ride_participants (
  ride_id          UUID NOT NULL REFERENCES rides(id),
  user_id          UUID NOT NULL REFERENCES users(id),
  group_id         UUID NOT NULL REFERENCES groups(id),
  counted_for_rank BOOLEAN NOT NULL DEFAULT false,
  PRIMARY KEY (ride_id, user_id)
);

-- 9. Rangos e insignias (RULES R8)
CREATE TABLE IF NOT EXISTS rank_progress (
  user_id     UUID PRIMARY KEY REFERENCES users(id),
  valid_rides INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS badges (
  id           UUID PRIMARY KEY,
  family       TEXT NOT NULL CHECK (family IN ('rank','achievement','honor')),
  code         TEXT NOT NULL,
  issuer_group UUID REFERENCES groups(id),
  issuer_user  UUID REFERENCES users(id), -- cualquier líder del grupo puede otorgar honor (R4.7)
  receiver     UUID NOT NULL REFERENCES users(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 10. Auditoría (inmutable, solo inserción)
CREATE TABLE IF NOT EXISTS audit_log (
  id          BIGSERIAL PRIMARY KEY,
  actor       UUID,
  action      TEXT NOT NULL,
  target_type TEXT,
  target_id   UUID,
  detail      JSONB,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION prevent_audit_log_mutation()
RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'AUDIT_LOG_IMMUTABLE: audit_log es de solo inserción';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_log_immutable ON audit_log;
CREATE TRIGGER trg_audit_log_immutable
  BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW
  EXECUTE FUNCTION prevent_audit_log_mutation();

-- 11. Acceso de emergencia (solo registro; el contenido nunca pasa por el servidor: RULES R10.3, R10.4)
CREATE TABLE IF NOT EXISTS emergency_access_log (
  id          BIGSERIAL PRIMARY KEY,
  ride_id     UUID NOT NULL,
  subject     UUID NOT NULL,
  accessed_by UUID NOT NULL,
  reason      TEXT NOT NULL CHECK (reason IN ('sos','leader_declared')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 12. Consentimientos (por usuario, grupo y tipo: RULES R15.1)
CREATE TABLE IF NOT EXISTS consents (
  id             UUID PRIMARY KEY,
  user_id        UUID NOT NULL REFERENCES users(id),
  group_id       UUID REFERENCES groups(id),     -- NULL solo para consentimientos de cuenta ('terms')
  kind           TEXT NOT NULL CHECK (kind IN ('membership','location','emergency_package','notifications','terms')),
  granted        BOOLEAN NOT NULL,
  policy_version TEXT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_consents_user_group_kind ON consents (user_id, group_id, kind);

-- 13. Reportes de moderación (sin audio ni ubicación: RULES R13)
CREATE TABLE IF NOT EXISTS moderation_reports (
  id         UUID PRIMARY KEY,
  reporter   UUID NOT NULL REFERENCES users(id),
  reported   UUID NOT NULL REFERENCES users(id),
  group_id   UUID NOT NULL REFERENCES groups(id),
  ride_id    UUID REFERENCES rides(id),
  reason     TEXT NOT NULL CHECK (reason IN ('abuse','harassment','dangerous','other')),
  state      TEXT NOT NULL DEFAULT 'open' CHECK (state IN ('open','reviewing','actioned','dismissed','escalated')),
  resolution TEXT CHECK (resolution IS NULL OR resolution IN ('warning','muted','ride_removed','group_removed','platform_suspended')),
  decided_by UUID[],
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 14. Decisiones sensibles que requieren mayoría de líderes (RULES R14.2)
CREATE TABLE IF NOT EXISTS leader_votes (
  id          UUID PRIMARY KEY,
  group_id    UUID NOT NULL REFERENCES groups(id),
  proposal    TEXT NOT NULL CHECK (proposal IN ('remove_member','remove_leader','declare_emergency','change_identity')),
  target_id   UUID,
  proposed_by UUID NOT NULL REFERENCES users(id),
  state       TEXT NOT NULL DEFAULT 'open' CHECK (state IN ('open','approved','rejected','expired')),
  expires_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS leader_vote_ballots (
  vote_id UUID NOT NULL REFERENCES leader_votes(id),
  leader  UUID NOT NULL REFERENCES users(id),
  approve BOOLEAN NOT NULL,
  PRIMARY KEY (vote_id, leader)
);

-- 15. Métricas anónimas de mesh (agregados, sin ubicaciones ni identidades: BACKEND_LICENSING §7b)
CREATE TABLE IF NOT EXISTS ride_mesh_metrics (
  ride_id                  UUID PRIMARY KEY REFERENCES rides(id),
  participants             INT NOT NULL,
  heartbeats_delivered_pct REAL,
  avg_hops                 REAL,
  island_events            INT,
  device_models            JSONB,
  uploaded_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 16. Eventos de Play (idempotencia RTDN)
CREATE TABLE IF NOT EXISTS play_events (
  message_id  TEXT PRIMARY KEY,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  payload     JSONB NOT NULL
);

-- 17. Denuncias por imitación de nombre/logo de grupo (RULES R5.4)
CREATE TABLE IF NOT EXISTS imitation_reports (
  id                UUID PRIMARY KEY,
  reporter_user     UUID NOT NULL REFERENCES users(id),
  claimed_group_id  UUID REFERENCES groups(id),
  reported_group_id UUID NOT NULL REFERENCES groups(id),
  reason            TEXT NOT NULL,
  state             TEXT NOT NULL DEFAULT 'pending_review' CHECK (state IN ('pending_review','confirmed','dismissed')),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 18. Tokens de refresco hasheados y revocables (BACKEND_LICENSING §10, Decisiones D35 y D40)
CREATE TABLE IF NOT EXISTS refresh_tokens (
  token_hash       TEXT PRIMARY KEY,
  user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at       TIMESTAMPTZ NOT NULL,
  revoked_at       TIMESTAMPTZ,
  replaced_by_hash TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens (user_id);

