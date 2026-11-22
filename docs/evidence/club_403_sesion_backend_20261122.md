# Incidente: creación de club con HTTP 403 (2026-11-22)

## 1. Síntoma reportado

En el Redmi `b2c398bb`: «⚠️ Error del servidor (403). Creación de club revertida.»

## 2. Causa raíz

`Phase1ViewModel.createNewGroup()` crea el grupo en local de forma optimista y
despacha `POST /v1/groups` en segundo plano. Si el backend responde con error,
`repository.removeGroup(grp.id)` revierte el grupo local y muestra el banner.

El backend (`backend/src/app.ts`, `POST /v1/groups`) devuelve 403 en dos casos:

- `FORBIDDEN` — «Usuario inactivo o inexistente»: el `uid` de la sesión no
  existe en `users` o no está activo.
- `LEADER_REQUIRED` — la cuenta existe pero `account_type <> 'leader'`.

Estado verificado al reproducirlo:

- `SELECT count(*) FROM users` → **0**. La limpieza de datos demo (`d327fae`)
  borró los usuarios, pero el móvil conservó su sesión local en
  `rbs_auth_prefs.xml` (`is_authenticated=true`, `user_id` persistido).
- `ClubApiClient.executeRequest()` envía `x-user-id` con ese `uid`; como no hay
  fila en `users`, `requireUserId` lo acepta como UUID válido y el handler
  responde `403 FORBIDDEN`. No hay reintento de autenticación porque el
  reintento solo se dispara en 401.
- El perfil local tiene `account_type=MEMBER`, así que aunque se forcingara el
  re-login, `attemptSilentReauth()` enviaría `activateLeaderDemo=false` y el
  backend crearía otra cuenta `member`, que volvería a dar 403 por
  `LEADER_REQUIRED`.

Conclusión: **no es un fallo de la regla de liderazgo ni de la red**. Es una
sesión local huérfana respecto a una base de datos que quedó vacía tras la
limpieza demo.

## 3. Acción correcta (no ejecutada desde este entorno)

1. Cerrar sesión en el Redmi y volver a entrar con la cuenta del piloto para
   que `/v1/auth/login` registre el usuario real en el backend (sin datos
   inventados).
2. En la pantalla de rol, elegir **Líder de Club** si se va a probar la
   creación de clubes: el login enviará `activateLeaderDemo=true` y el backend
   creará la cuenta con `account_type='leader'`.
3. Reintentar la creación de club.

No se ha insertado ningún usuario, suscripción ni club de forma manual: la BD
sigue virgen y no se ha fabricado evidencia.

## 4. Por qué no se arregló en código

- Cambiar `ClubRepository.createGroup()` para que acepte miembros violaría la
  regla R4.1 y está fuera del alcance autorizado.
- Insertar un usuario `leader` directamente en PostgreSQL sería inventar datos
  de piloto y rompería la condición de BD virgen.
- Tocar `activateLeaderDemo` o la pantalla de roles alteraría el alta de
  cuentas y requiere decisión del dueño del piloto.

## 5. Estado

Pendiente de que el usuario ejecute el re-login en el dispositivo. El fix del
túnel (PR #4) y el Paso 2 (PR #3) no dependen de este incidente.
