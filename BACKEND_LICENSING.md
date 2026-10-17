# BACKEND_LICENSING — Licencias y cobros de Ruta Biker Sync

Versión 1.1 · Estado: borrador de diseño · Fecha: 2026-10-17
**Cambio v1.1:** unificación de nomenclatura de cupos a `member_slot_1..5` según D30 (antes `guest_slot_1..N` en §3). Ver MEMORY.md.

## 1. Objetivo

Cobrar suscripciones mensuales (líder $15, miembro/invitado $2.50 por grupo), mantener una lista autorizada por grupo y emitir **credenciales firmadas** que los teléfonos validen **sin internet** durante una rodada.

El backend guarda lo mínimo: identidad, suscripciones, grupos, membresías y registros de auditoría. **Nunca** guarda ubicación, audio, documentos ni datos de salud.

## 2. Reglas de negocio que el backend hace cumplir

| # | Regla |
|---|---|
| B1 | Líder: $15/mes por persona. Hasta 3 líderes por grupo, cada uno paga el suyo |
| B2 | Full member y Prospecto: $2.50/mes en su grupo; pertenecen a un solo grupo |
| B3 | Invitado: $2.50/mes por cada grupo que lo agregue, ruede o no |
| B4 | Nadie entra a un grupo sin aceptar invitación (consentimiento) |
| B5 | Nombre de grupo único por país (normalizado) |
| B6 | Logo único por país (huella perceptual) |
| B7 | Transferencia de grupo solo entre miembros del grupo, con espera de 7 días |
| B8 | Si vence el pago, la credencial caduca y la mesh lo rechaza |
| B9 | Gracia de 3 días (configurable) tras un fallo de pago |
| B10 | El grupo sigue activo mientras al menos un líder esté al día |

**Puntos abiertos que afectan al cobro** (ver RULES.md, sección Pendientes):
- P1: ¿líder invitado en otro grupo paga $2.50 o es gratis?
- P2: ¿un líder de varios grupos paga $15 una vez o por grupo? Este esquema asume **una vez por persona**.
- P3: reembolsos y retiro a mitad de mes. Este esquema asume **acceso hasta fin del periodo pagado**.

## 3. Riesgo técnico principal: Google Play Billing

Play no permite que una misma cuenta compre el mismo producto de suscripción varias veces simultáneamente. Un invitado en 3 grupos necesitaría 3 cobros de $2.50.

Opciones a validar **en la consola de Play antes de programar**:

| Opción | Descripción | Pros | Contras |
|---|---|---|---|
| **A. Productos de cupo numerados** | `member_slot_1`, `member_slot_2`, … `member_slot_5`, cada uno a $2.50/mes. Cada cupo se asigna a un grupo (**ver D30 en MEMORY.md**) | Usa solo Play, cumple políticas | Límite práctico de cupos, UX más compleja |
| **B. Complementos (add-ons) de suscripción** | Suscripción base con complementos | Una sola suscripción visible | Soporte y precios dependen de las funciones vigentes de Play |
| **C. Escalonado por cantidad** | Productos de $2.50, $5.00, $7.50… según nº de grupos | Simple de contar | Reasignar entre grupos exige cambiar de producto |
| **D. Pasarela externa (web)** | Cobro fuera de Play | Libertad de precios | Restricciones de políticas de Play para bienes digitales; revisar antes |

**Recomendación inicial:** opción A (cupos numerados, p. ej. hasta 5) más validación legal de políticas vigentes. Decidir con una prueba en la consola antes de la Fase 2.

Los precios y comisiones de Play cambian: verificar tarifas vigentes y los precios por país (impuestos y moneda local).

## 4. Modelo de datos (PostgreSQL)

```sql
-- Usuarios
CREATE TABLE users (
  id              UUID PRIMARY KEY,
  email           CITEXT UNIQUE NOT NULL,
  display_name    TEXT,
  country_code    CHAR(2) NOT NULL,
  account_type    TEXT NOT NULL CHECK (account_type IN ('member','leader')),
  public_key      BYTEA,                 -- clave pública del dispositivo
  status          TEXT NOT NULL DEFAULT 'active', -- active|suspended|deleted
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Suscripciones (reflejo de Play Billing)
CREATE TABLE subscriptions (
  id                 UUID PRIMARY KEY,
  user_id            UUID NOT NULL REFERENCES users(id),
  kind               TEXT NOT NULL CHECK (kind IN ('leader','member_slot')),
  play_product_id    TEXT NOT NULL,       -- p. ej. leader_monthly, member_slot_1
  play_purchase_token TEXT UNIQUE NOT NULL,
  state              TEXT NOT NULL,       -- active|grace|on_hold|canceled|expired|refunded
  current_period_end TIMESTAMPTZ NOT NULL,
  grace_until        TIMESTAMPTZ,
  auto_renewing      BOOLEAN NOT NULL DEFAULT true,
  assigned_group_id  UUID,                -- solo para member_slot
  last_verified_at   TIMESTAMPTZ NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Grupos
CREATE TABLE groups (
  id               UUID PRIMARY KEY,
  country_code     CHAR(2) NOT NULL,
  name             TEXT NOT NULL,
  name_normalized  TEXT NOT NULL,         -- sin acentos, sin símbolos, sustituciones leet
  logo_url         TEXT,
  logo_phash       BYTEA NOT NULL,        -- huella perceptual
  principal_leader UUID NOT NULL REFERENCES users(id),
  status           TEXT NOT NULL DEFAULT 'active', -- active|suspended|transfer_pending|deleted
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (country_code, name_normalized)
);
-- El logo se compara por distancia de Hamming sobre logo_phash, dentro del mismo país.

-- Líderes del grupo (máximo 3)
CREATE TABLE group_leaders (
  group_id     UUID REFERENCES groups(id),
  user_id      UUID REFERENCES users(id),
  is_principal BOOLEAN NOT NULL DEFAULT false,
  added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, user_id)
);
-- Regla: trigger que limita a 3 filas por group_id y exactamente 1 principal.

-- Membresías
CREATE TABLE memberships (
  id            UUID PRIMARY KEY,
  group_id      UUID NOT NULL REFERENCES groups(id),
  user_id       UUID NOT NULL REFERENCES users(id),
  status_kind   TEXT NOT NULL CHECK (status_kind IN ('full','prospect','guest')),
  club_role     TEXT,                     -- presidente, vice, capitan_ruta, sargento_armas,
                                          -- secretario, tesorero, road_marshal
  invite_state  TEXT NOT NULL,            -- pending|accepted|declined|left|removed
  accepted_at   TIMESTAMPTZ,
  paid_until    TIMESTAMPTZ,              -- derivado de la suscripción asignada
  UNIQUE (group_id, user_id)
);
-- Regla: un usuario con status_kind en ('full','prospect') y invite_state='accepted'
-- solo puede tener UNA membresía así (índice único parcial).

-- Invitaciones
CREATE TABLE invitations (
  id           UUID PRIMARY KEY,
  group_id     UUID NOT NULL REFERENCES groups(id),
  invited_user UUID NOT NULL REFERENCES users(id),
  invited_by   UUID NOT NULL REFERENCES users(id),
  status_kind  TEXT NOT NULL,
  state        TEXT NOT NULL DEFAULT 'pending', -- pending|accepted|declined|expired
  expires_at   TIMESTAMPTZ NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Transferencias de grupo
CREATE TABLE group_transfers (
  id            UUID PRIMARY KEY,
  group_id      UUID NOT NULL REFERENCES groups(id),
  from_user     UUID NOT NULL REFERENCES users(id),
  to_user       UUID NOT NULL REFERENCES users(id),
  state         TEXT NOT NULL,            -- pending|accepted|canceled|completed
  executes_at   TIMESTAMPTZ NOT NULL,     -- creación + 7 días
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Rodadas (solo metadatos, sin ubicaciones)
CREATE TABLE rides (
  id            UUID PRIMARY KEY,
  host_group    UUID NOT NULL REFERENCES groups(id),
  host_leader   UUID NOT NULL REFERENCES users(id),
  state         TEXT NOT NULL,            -- planned|active|closed|validated|rejected
  starts_at     TIMESTAMPTZ,
  closed_at     TIMESTAMPTZ
);
CREATE TABLE ride_groups (
  ride_id UUID REFERENCES rides(id),
  group_id UUID REFERENCES groups(id),
  PRIMARY KEY (ride_id, group_id)
);
CREATE TABLE ride_participants (
  ride_id UUID REFERENCES rides(id),
  user_id UUID REFERENCES users(id),
  group_id UUID REFERENCES groups(id),
  counted_for_rank BOOLEAN NOT NULL DEFAULT false,
  PRIMARY KEY (ride_id, user_id)
);

-- Rangos e insignias
CREATE TABLE rank_progress (
  user_id UUID PRIMARY KEY REFERENCES users(id),
  valid_rides INT NOT NULL DEFAULT 0
);
CREATE TABLE badges (
  id          UUID PRIMARY KEY,
  family      TEXT NOT NULL CHECK (family IN ('rank','achievement','honor')),
  code        TEXT NOT NULL,
  issuer_group UUID REFERENCES groups(id),
  issuer_user  UUID REFERENCES users(id), -- cualquier líder del grupo puede otorgar honor
  receiver     UUID NOT NULL REFERENCES users(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Auditoría (inmutable, solo inserción)
CREATE TABLE audit_log (
  id         BIGSERIAL PRIMARY KEY,
  actor      UUID,
  action     TEXT NOT NULL,
  target_type TEXT,
  target_id  UUID,
  detail     JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Acceso de emergencia (solo registro; el contenido nunca pasa por el servidor)
CREATE TABLE emergency_access_log (
  id         BIGSERIAL PRIMARY KEY,
  ride_id    UUID NOT NULL,
  subject    UUID NOT NULL,
  accessed_by UUID NOT NULL,
  reason     TEXT NOT NULL,               -- sos|leader_declared
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Consentimientos (por usuario, grupo y tipo)
CREATE TABLE consents (
  id          UUID PRIMARY KEY,
  user_id     UUID NOT NULL REFERENCES users(id),
  group_id    UUID REFERENCES groups(id),     -- NULL solo para consentimientos de cuenta
  kind        TEXT NOT NULL CHECK (kind IN ('membership','location','emergency_package','notifications','terms')),
  granted     BOOLEAN NOT NULL,
  policy_version TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at  TIMESTAMPTZ
);
CREATE INDEX ON consents (user_id, group_id, kind);

-- Reportes de moderación (sin audio ni ubicación)
CREATE TABLE moderation_reports (
  id           UUID PRIMARY KEY,
  reporter     UUID NOT NULL REFERENCES users(id),
  reported     UUID NOT NULL REFERENCES users(id),
  group_id     UUID NOT NULL REFERENCES groups(id),
  ride_id      UUID REFERENCES rides(id),
  reason       TEXT NOT NULL,           -- abuse|harassment|dangerous|other
  state        TEXT NOT NULL DEFAULT 'open', -- open|reviewing|actioned|dismissed|escalated
  resolution   TEXT,                    -- warning|muted|ride_removed|group_removed|platform_suspended
  decided_by   UUID[],                  -- líderes que decidieron (mayoría, ver RULES R14)
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Decisiones sensibles que requieren mayoría de líderes
CREATE TABLE leader_votes (
  id          UUID PRIMARY KEY,
  group_id    UUID NOT NULL REFERENCES groups(id),
  proposal    TEXT NOT NULL,            -- remove_member|remove_leader|declare_emergency|change_identity
  target_id   UUID,
  proposed_by UUID NOT NULL REFERENCES users(id),
  state       TEXT NOT NULL DEFAULT 'open', -- open|approved|rejected|expired
  expires_at  TIMESTAMPTZ NOT NULL
);
CREATE TABLE leader_vote_ballots (
  vote_id UUID REFERENCES leader_votes(id),
  leader  UUID REFERENCES users(id),
  approve BOOLEAN NOT NULL,
  PRIMARY KEY (vote_id, leader)
);

-- Métricas anónimas de mesh (agregados, sin ubicaciones ni identidades)
CREATE TABLE ride_mesh_metrics (
  ride_id        UUID PRIMARY KEY REFERENCES rides(id),
  participants   INT NOT NULL,
  heartbeats_delivered_pct REAL,
  avg_hops       REAL,
  island_events  INT,                   -- veces que la mesh se partió
  device_models  JSONB,                 -- modelos de teléfono agregados
  uploaded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Eventos de Play (idempotencia)
CREATE TABLE play_events (
  message_id TEXT PRIMARY KEY,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  payload JSONB NOT NULL
);
```

## 5. Estados de suscripción

```
active ──fallo de pago──▶ grace (3 días) ──pago ok──▶ active
                              │
                              └─sin pago─▶ on_hold ──pago ok──▶ active
                                              │
                                              └─30 días─▶ expired
active ──cancelación──▶ canceled (activa hasta current_period_end) ──▶ expired
active ──reembolso──▶ refunded (credencial revocada)
```

**Acceso efectivo** de un usuario en un grupo:

| Condición | Resultado |
|---|---|
| `active` o `canceled` con periodo vigente | Acceso |
| `grace` y `now < grace_until` | Acceso con alerta |
| `on_hold`, `expired`, `refunded` | Sin acceso, credencial no se renueva |

## 6. Flujos

### 6.1 Compra de líder ($15)
1. La app lanza la compra de `leader_monthly` en Play.
2. La app envía `purchase_token` a `POST /v1/subscriptions/verify`.
3. El backend verifica con la API de Google Play (Developer API), crea o actualiza `subscriptions`, pasa `account_type` a `leader` y registra auditoría.
4. Si no se verifica, no se concede acceso.

### 6.2 Invitación y alta de miembro o invitado
1. Un líder crea la invitación (`POST /v1/groups/{id}/invitations`).
2. El usuario ve grupo, logo, estatus y **costo $2.50/mes** y acepta o rechaza.
3. Al aceptar, la app lanza la compra de un cupo de miembro. El backend verifica el token y lo asigna al grupo (`assigned_group_id`).
4. La membresía pasa a `accepted`, se emite credencial y el líder lo ve en verde.
5. Si no paga en el plazo (p. ej. 72 h), la invitación expira sin cobro.

### 6.3 Renovación y fallos
- Play envía notificaciones **RTDN** (Real-time Developer Notifications) por Pub/Sub. El endpoint `POST /v1/play/rtdn` es idempotente (tabla `play_events`).
- Cada evento dispara una consulta a la API de Play para obtener el estado actual (no se confía solo en el mensaje).
- Un job diario concilia todas las suscripciones activas contra Play.

### 6.4 Baja o retiro
- Si el usuario se sale: se cancela su cupo en Play (el usuario cancela desde Play; la app muestra el enlace), acceso hasta fin del periodo.
- Si el líder lo retira: se revoca la credencial desde el próximo ciclo de emisión; el cupo queda libre para reasignar a otro grupo.

### 6.5 Transferencia de grupo
1. Líder principal propone a un miembro del grupo con cuenta de líder activa.
2. El receptor acepta; espera de 7 días con posibilidad de cancelar.
3. Al ejecutarse: cambia `principal_leader`, se registra auditoría y se notifica a todos.

## 7. Credenciales firmadas (validación offline)

Formato (CBOR o JSON firmado con Ed25519):

```json
{
  "v": 1,
  "uid": "<user_id>",
  "gid": "<group_id>",
  "kind": "leader | full | prospect | guest",
  "pk": "<clave pública del dispositivo>",
  "iat": 1760000000,
  "exp": 1760604800,
  "kid": "<id de clave de firma>"
}
```

- **Firma:** clave privada de la plataforma en un KMS/HSM. Los teléfonos incluyen las claves públicas (`kid`) y pueden rotarlas.
- **Duración:** `exp` corto (7 días) y se renueva al sincronizar. Nunca excede `current_period_end + gracia`.
- **Verificación en la mesh:** cada teléfono comprueba firma, `exp`, `gid` de la rodada y que el `pk` del mensaje coincida.
- **Revocación:** como es offline, la revocación es por caducidad corta, más una lista de revocación que se sincroniza antes de cada rodada.
- **Rodadas con dos grupos:** el líder anfitrión emite un **manifiesto de rodada** firmado con los `gid` participantes y la lista de usuarios; los teléfonos aceptan credenciales de cualquiera de esos grupos.
- **Sincronización previa:** la app avisa "sincroniza antes de salir" y bloquea el inicio de rodada si la credencial caduca durante ella.
- **Sincronización forzada al iniciar la rodada:** si hay internet, el teléfono del líder anfitrión descarga la lista de revocación y el manifiesto justo antes de arrancar. Si no hay internet, la app avisa que la lista puede estar desactualizada y muestra la fecha de la última sincronización.
- **Rechazo manual:** el líder anfitrión puede expulsar de la rodada a un participante con credencial válida; el rechazo se propaga por la mesh como mensaje firmado y queda en auditoría al volver a tener conexión.

## 7b. Métricas de campo sin comprometer privacidad
- Cada teléfono guarda un **log local** de heartbeats y saltos durante la rodada.
- Al cerrar la rodada y tener conexión, sube **solo agregados** (porcentaje entregado, saltos promedio, veces que la mesh se partió, modelos de teléfono). Nunca ubicaciones, rutas ni identidades.
- La subida es opcional y se pide como consentimiento aparte.

## 7c. Plan de contingencia si Google Play rechaza la app
1. Revisar las políticas de ubicación en segundo plano, Bluetooth y datos de salud **antes de la Fase 2** y preparar las declaraciones y justificaciones.
2. Mantener una **distribución alternativa** (APK firmado desde la web de la empresa, F-Droid no aplica al ser código cerrado) como respaldo. Nota: fuera de Play no hay Play Billing, así que ese canal requeriría otro sistema de cobro y es una decisión aparte.
3. Si Play rechaza el cobro por cupos múltiples (`member_slot_1..5`, ver D30), activar el **pase por rodada** para invitados (RULES P4).
4. Tener preparada una versión sin las funciones de más riesgo para pasar la revisión, y activarlas por actualización.

## 8. API (resumen REST v1)

| Método | Ruta | Función |
|---|---|---|
| POST | `/v1/auth/register` | Registro de cuenta (`email`, `password` hasheado con `scrypt N=2^17`, `displayName`, `countryCode`, `termsAccepted`, devuelve `accessToken` 30 min + `refreshToken` 30 días, D35/D37) |
| POST | `/v1/auth/login` | Inicio de sesión (`scrypt N=2^17`, devuelve `accessToken` 30 min + `refreshToken` 30 días; bloqueo 15 min tras 5 fallos por `(correo, IP)` o 100 por IP con retardo progresivo, D35/D40) |
| POST | `/v1/auth/refresh` | Rotación de sesión: recibe `refreshToken`, verifica su hash SHA-256 en `refresh_tokens`, lo revoca y emite un nuevo par `accessToken` (30 min) + `refreshToken` (30 días) (D36/D37/D40) |
| POST | `/v1/auth/logout` | Cierre de sesión: revoca inmediatamente el `refreshToken` en base de datos (`revoked_at = now()`) (D36/D37/D40) |
| GET | `/v1/users/me` | Perfil propio, rango (`valid_rides`) e insignias |
| PATCH | `/v1/users/me` | Actualizar perfil (`displayName`, `bikeModel`, `mapColor`, `avatarWebpBase64` máx. 64×64 WebP ≤ 12 KB) (D39) |
| POST | `/v1/subscriptions/verify` | Verificar compra de Play (`leader_monthly` o `member_slot_1..5`) |
| GET | `/v1/subscriptions/me` | Estado de mis suscripciones (6 estados: `active`, `grace` 3 días según R7.3, `on_hold`, `canceled`, `expired`, `refunded`) |
| POST | `/v1/play/rtdn` | Notificaciones de Play (idempotente en `play_events`; suspensión de grupo si caen los 3 líderes según R4.6; promoción de líder principal a los 7 días según R4.5) |
| POST | `/v1/groups` | Crear grupo (valida nombre y logo únicos por país, R5.1/R5.2; parámetro opcional P6 `MAX_PRINCIPAL_GROUPS_PER_LEADER`) |
| GET | `/v1/groups` | Directorio de grupos por país |
| GET | `/v1/groups/{id}` | Detalle de grupo, líderes (`is_principal`) y cargos honoríficos (D39) |
| POST | `/v1/groups/{id}/leaders` | Agregar co-líder con cuenta de líder activa (máx. 3 líderes por grupo, R4.1, respaldado por `trg_enforce_max_three_leaders`) |
| DELETE | `/v1/groups/{id}/leaders/{leaderUid}` | Retirar co-líder o renunciar como líder (protege al principal salvo baja de suscripción, R14.2 / R14.3, D39) |
| GET | `/v1/groups/{id}/members` | Lista con semáforo (solo líderes; nunca expone montos ni medios de pago, R4.2) |
| POST | `/v1/groups/{id}/invitations` | Invitar con estatus (`full`, `prospect`, `guest`) y cargo opcional |
| GET | `/v1/invitations/me` | Invitaciones pendientes del usuario con transparencia de cuota ($2.50/mes) |
| POST | `/v1/invitations/{id}/accept` | Aceptar invitación (valida R2: máx. 1 grupo como Full/Prospecto; registra los 4 consentimientos de R15.1) |
| POST | `/v1/invitations/{id}/decline` | Rechazar invitación sin cobro (R2.1) |
| PATCH | `/v1/groups/{id}/members/{uid}` | Cambiar estatus o cargo honorífico (solo líderes; cargos no dan permisos técnicos, R3; parámetro P7 para invitados) |
| POST | `/v1/groups/{id}/transfer` | Iniciar transferencia del liderazgo principal (espera de 7 días, R5.3 y R14.3) |
| POST | `/v1/groups/{id}/transfer/{transferId}/accept` | Aceptar propuesta de transferencia por el líder receptor (D39) |
| POST | `/v1/groups/{id}/transfer/{transferId}/cancel` | Cancelar transferencia durante los 7 días de espera (R5.3, D39) |
| POST | `/v1/groups/{id}/transfer/{transferId}/execute` | Ejecutar transferencia cumplidos los 7 días (R5.3, D39) |
| POST | `/v1/groups/{id}/imitation-reports` | Denunciar grupo por imitación de nombre o logo (R5.1, D38) |
| POST | `/v1/rides` | Crear rodada |
| POST | `/v1/rides/{id}/manifest` | Obtener manifiesto firmado |
| POST | `/v1/rides/{id}/close` | Cerrar y validar |
| GET | `/v1/credentials` | Credenciales vigentes del usuario (`exp` = 7 días acotado por `current_period_end + gracia`) |
| GET | `/v1/revocations` | Lista de revocación sincronizable antes de iniciar rodada |
| POST | `/v1/groups/{id}/badges` | Otorgar insignia de honor (líderes) |
| DELETE | `/v1/users/me` | Borrado de cuenta (RGPD, R15.3); revoca tokens, cancela membresías, anonimiza insignias y notifica a los líderes de sus grupos |
| GET | `/v1/users/me/consents` | Lista de consentimientos por grupo y tipo |
| PUT | `/v1/users/me/consents/{id}` | Otorgar o revocar un consentimiento |
| GET | `/v1/users/me/export` | Exportación de datos del backend |
| POST | `/v1/groups/{id}/reports` | Reportar a un rider |
| GET | `/v1/groups/{id}/reports` | Ver reportes (líderes) |
| POST | `/v1/groups/{id}/votes` | Proponer decisión sensible |
| POST | `/v1/votes/{id}/ballot` | Votar (líderes) |
| POST | `/v1/rides/{id}/metrics` | Subir métricas anónimas de mesh (solo agregados) |

**Semáforo de pago** (solo estado, nunca montos ni datos de tarjeta): 🟢 al día · 🟡 vence en <7 días o en gracia · 🔴 vencido.

## 9. Alarmas y notificaciones

| Destinatario | Evento | Momento |
|---|---|---|
| Miembro | Vencimiento de cuota | 7, 3 y 1 día antes |
| Miembro | Pago fallido | Inmediato y cada día de gracia |
| Líder | Resumen de pagos por vencer | 3 días antes de cada rodada |
| Líder | Su propia suscripción por caer | 7, 3 y 1 día antes |
| Todos los líderes | Líder principal sin pago | A los 7 días se promueve al siguiente |
| Usuario | Acceso a su paquete de emergencia | Después del evento |

Se envían por FCM (gratis) y correo transaccional. Las alarmas de documentos son **locales** en el teléfono.

## 10. Seguridad y Autenticación (D35 / D36 / D40)

- **Autenticación y Modelo de Amenazas de Sesión (Decisiones D35, D36 y D40):**
  - **Hashing de contraseñas (OWASP) con control de memoria:** `scrypt` con factor de costo **`N = 131072` (`2^17`)**, `r = 8`, `p = 1`, `keylen = 64`, sal aleatoria CSPRNG de 16 bytes (`scrypt-N131072-r8-p1-k64`) y **`maxmem = 268435456` (256 MiB explícito)**. Dado que cada derivación consume `128 * N * r * p = 128 MiB`, el backend aplica un semáforo FIFO (`ScryptConcurrencyLimiter`, `MAX_CONCURRENT_SCRYPT_HASHES = 4`) que acota el pico de memoria a 512 MiB ante ráfagas de logins concurrentes.
  - **Token de sesión propio firmado con HMAC-SHA256 (`backend/src/lib/auth.ts`):** formato compacto `<payload_base64url>.<hmac_sha256_base64url>` que cumple 5 invariantes verificados en tests:
    1. **Algoritmo fijo (`alg === 'HS256'`):** rechaza cualquier token con otro `alg` (impide ataques `alg: none` o confusión de algoritmo).
    2. **Expiración obligatoria (`exp`):** `exp` debe ser número finito y `exp > nowSec` (`accessToken` = **30 min / `1800 s`**; `refreshToken` = **30 días / `2592000 s`**).
    3. **Comparación en tiempo constante:** firma verificada con `timingSafeEqual` sobre los 32 bytes binarios del digest HMAC-SHA256.
    4. **Aislamiento de tipos (`kind: 'access' | 'refresh'`):** el middleware `requireUserId` exige `kind === 'access'` (un `refreshToken` es rechazado con `401`), y `/v1/auth/refresh` exige `kind === 'refresh'` (un `accessToken` es rechazado con `401`).
    5. **Rotación de clave de firma (`kid` + `SessionKeyring`):** cada token incluye el identificador de clave `kid`; el servidor mantiene un llavero (`AUTH_TOKEN_ACTIVE_KID` + `AUTH_TOKEN_KEYS_JSON`) que permite firmar con la clave nueva y verificar tokens vigentes con la clave saliente hasta su expiración natural de 30 minutos.
  - **Refresh tokens rotativos y revocables en BD:** el backend **solo persiste el hash SHA-256** (`token_hash`) en `refresh_tokens` (`D36`). Cada llamada a `POST /v1/auth/refresh` invalida el token anterior (`replaced_by_hash`) y emite uno nuevo; si se detecta reutilización de un `refreshToken` ya rotado, se revocan todos los tokens activos del usuario.
  - **Protección contra fuerza bruta compatible con CGNAT móvil y proxy inverso:** bloqueo por par `(correo, IP)` (**5 intentos fallidos** en 15 minutos) y umbral por `IP` de **100 intentos fallidos** en 15 minutos con retardo progresivo (`backoffDelayMs`: 250 ms → 2000 ms), evitando bloquear a usuarios legítimos que comparten IP móvil tras CGNAT. Fastify configura `trustProxy` (`TRUST_PROXY=true` en despliegue detrás de proxy inverso).
  - **Almacenamiento seguro en Android (`SecureTokenStore.kt`, `D40`):** `accessToken` vive solo en memoria volátil (`@Volatile`); `refreshToken` se cifra con AES-256-GCM mediante clave maestra en **`AndroidKeyStore`** (`rbs_session_master_key_v1`) antes de persistirse, nunca en preferencias planas.
- TLS en todo; autenticación con tokens de corta duración y renovación.
- Claves de firma en KMS, rotación anual y `kid` en cada credencial.
- Verificación de integridad de la app con Play Integrity API.
- Límite de peticiones y registro de auditoría inmutable (`trg_audit_log_immutable`, D36).
- Datos mínimos: correo, país, suscripción, membresías. Sin ubicación, audio, documentos ni salud.
- Cumplimiento: GDPR, LGPD, CCPA; exportación y borrado de cuenta; política de privacidad y consentimientos separados. Consulta legal antes de lanzar (orientación general, no asesoría legal).

## 11. Unicidad de nombre y logo

**Nombre:** normalizar con minúsculas, quitar acentos, eliminar espacios y símbolos, mapear sustituciones leet (4→a, 3→e, 1→l/i, 0→o, 5→s, 7→t) y comparar por igualdad exacta y por distancia de edición baja dentro del país.

**Logo:** calcular pHash/dHash al subirlo; rechazar si la distancia de Hamming es menor al umbral frente a otro logo del mismo país. Pasar a revisión manual en casos dudosos. Botón de denuncia por imitación.

Aclaración: el registro en la app no sustituye una marca registrada.

## 12. Infraestructura y costos (orientativo)

| Componente | Opción de arranque | Nota |
|---|---|---|
| API | Contenedor o funciones serverless | Escalable por demanda |
| Base de datos | PostgreSQL administrado | Respaldos diarios |
| Colas/eventos | Pub/Sub de Google (RTDN) | Necesario para Play |
| KMS | KMS del proveedor | Claves de firma |
| Notificaciones | FCM + correo transaccional | |
| Almacenamiento | Bucket para logos | Solo logos |

Los costos dependen del proveedor y del volumen. Estimarlos antes de fijar el precio final. Hay planes gratuitos para empezar, pero no son indefinidos.

## 13. Pruebas obligatorias

1. Compra, renovación, fallo de pago, gracia, cancelación y reembolso.
2. Un invitado con 3 grupos y cupos `member_slot_1..3` asignados correctamente (ver D30).
3. Credencial caducada rechazada por la mesh sin internet.
4. Eventos RTDN duplicados o desordenados.
5. Dos grupos en una rodada con manifiesto firmado.
6. Colisión de nombre con variaciones leet y logo con cambios mínimos.
7. Transferencia de grupo con cancelación dentro de los 7 días.
8. Fallo de los 3 líderes: grupo suspendido, historial intacto.
