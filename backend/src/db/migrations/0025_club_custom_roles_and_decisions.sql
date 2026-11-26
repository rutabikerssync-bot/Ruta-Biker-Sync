CREATE TABLE IF NOT EXISTS club_custom_roles (
  id              UUID PRIMARY KEY,
  group_id        UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  title           TEXT NOT NULL,
  badge_icon      TEXT NOT NULL DEFAULT 'shield',
  can_create_ride BOOLEAN NOT NULL DEFAULT false,
  can_moderate    BOOLEAN NOT NULL DEFAULT false,
  created_by      UUID NOT NULL REFERENCES users(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (group_id, title)
);

CREATE INDEX IF NOT EXISTS idx_club_custom_roles_group
  ON club_custom_roles (group_id);

ALTER TABLE memberships
  ADD COLUMN IF NOT EXISTS custom_role_id UUID REFERENCES club_custom_roles(id) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS club_decisions (
  id                 UUID PRIMARY KEY,
  group_id           UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  title              TEXT NOT NULL,
  description        TEXT NOT NULL DEFAULT '',
  category           TEXT NOT NULL DEFAULT 'general',
  options            JSONB NOT NULL,
  allowed_audiences  TEXT[] NOT NULL,
  min_quorum_percent INTEGER NOT NULL DEFAULT 0 CHECK (min_quorum_percent BETWEEN 0 AND 100),
  state              TEXT NOT NULL DEFAULT 'open' CHECK (state IN ('open','closed')),
  created_by         UUID NOT NULL REFERENCES users(id),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  closes_at          TIMESTAMPTZ NOT NULL,
  closed_at          TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_club_decisions_group_state
  ON club_decisions (group_id, state, closes_at DESC);

-- Registro de participación: solo guarda quién ya votó (sin opción elegida -> secreto real).
CREATE TABLE IF NOT EXISTS club_decision_receipts (
  decision_id UUID NOT NULL REFERENCES club_decisions(id) ON DELETE CASCADE,
  user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  voted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (decision_id, user_id)
);

-- Contadores anónimos por opción: sin user_id -> imposible correlacionar voto con piloto.
CREATE TABLE IF NOT EXISTS club_decision_tallies (
  decision_id  UUID NOT NULL REFERENCES club_decisions(id) ON DELETE CASCADE,
  option_index INTEGER NOT NULL CHECK (option_index >= 0),
  votes_count  INTEGER NOT NULL DEFAULT 0 CHECK (votes_count >= 0),
  PRIMARY KEY (decision_id, option_index)
);
