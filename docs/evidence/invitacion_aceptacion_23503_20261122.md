# Error 23503 al aceptar la invitación (2026-11-22)

## Síntesis del diagnóstico pedido

### 1. Payload que viaja (logcat de ambos móviles)

Sin líneas de `invited_user`, `invitedUserId`, `POST … invitations` ni `23503`
en ninguno de los dos dispositivos. El cliente no traza el cuerpo de la
petición, así que el logcat no aporta el payload. **Pero la BD sí lo conserva**:

```
invitations
  57a33bfa-f923-427f-82fc-ad680e99885f
  invited_user = NULL
  invited_by   = b5d0228d-8ab9-49fd-9cc7-efa452a484f2   (líder real)
  group_id     = e3988eba-26e8-425a-80ac-2a65aeda8484   (Rutas Demo Bikers)
  state        = pending
```

### 2. Usuarios reales en PostgreSQL

11 filas: 10 `*.e2e.*@rbs.mx` de pruebas y el líder real
`b5d0228d-8ab9-49fd-9cc7-efa452a484f2 | omartinezpaz@gmail.com`.

**El miembro del Redmi NO existe en `users`:**
`select count(*) from users where id='f2850920-3014-3990-8d87-106a245c2365'` → `0`.

### 3. Handler `POST /v1/groups/:id/invitations` (`backend/src/app.ts:2462`)

```ts
const targetUserId = (invitedUserId && invitedUserId !== uid) ? invitedUserId : null;
...
INSERT INTO invitations (id, group_id, invited_user, invited_by, status_kind,
                         invitation_type, state, expires_at)
VALUES ($1, $2, $3, $4, $5, $6, 'pending', $7)
[invId, groupId, targetUserId, uid, statusKind || 'full', invitationType, expiresAt]
```

No hay default inválido: si no viene `invitedUserId`, se inserta `NULL`.
Coincide con la fila real de arriba.

## Corrección del diagnóstico inicial

La hipótesis de un **ID demo fantasma en el INSERT** no se sostiene con estos
datos:

- El título `Rutas Demo Bikers (VE)` es el **nombre que el tester escribió** al
  fundar el club real (ver `club_real_fundado_20261122.md`), no una referencia
  a un usuario/club demo borrado.
- El `invited_user` de la invitación es `NULL`, no un ID sintético. La FK acepta
  `NULL`, por eso el INSERT devolvió 201 y la invitación existe.
- Por tanto, la **Capa 1** (validar `invited_user` antes del INSERT) y la
  **Capa 2** (omitir `invitedUserId` en QR abierto) no aplican: el backend ya
  hace ambas cosas correctamente.

## Causa real: el UPDATE de aceptación

El mensaje de PostgreSQL dice `insert or update on table "invitations"`, que es
la redacción que usa también un `UPDATE`. En `backend/src/app.ts` hay dos
escrituras de `invited_user` durante la aceptación:

```ts
// app.ts:2689 (invitación a rodada)
UPDATE invitations SET state = 'accepted', invited_user = $1 WHERE id = $2
// app.ts:2717 (hermandad full/prospect)
UPDATE invitations SET state = 'accepted', invited_user = $1 WHERE id = $2
```

En ambos casos `$1` es `uid`, el `x-user-id` del aceptante. En el Redmi ese UID
es `f2850920-3014-3990-8d87-106a245c2365`, que tiene **0 filas en `users`**.
La FK `invitations_invited_user_fkey → users(id)` rechaza el UPDATE y devuelve
500 / `23503`.

Es la misma familia de bug que el 403 del líder: el perfil local de Google
existe en el móvil pero nunca se registró en Fastify. Al líder ya se dio de
alta; al miembro todavía no.

## Perfil real del Redmi (ya presente en el dispositivo)

```
user_email        = ozmartinezpaz@gmail.com
user_display_name = The Developer
account_type      = MEMBER
user_id           = f2850920-3014-3990-8d87-106a245c2365   (no registrado)
base_url          = https://reputation-improved-definition-absence.trycloudflare.com
```

## Vía de corrección sin tocar código

Dar de alta al miembro con el endpoint oficial
`POST /v1/auth/login`, usando sus datos reales y
`activateLeaderDemo=false` (permanece `MEMBER`, **sin promover**), y apuntar su
`user_id` local al UUID devuelto. Después se reintenta la aceptación desde la UI.

**No se han modificado `app.ts`, ni el cliente, ni filas a mano.** Queda
pendiente de autorización para actuar sobre el Redmi.
