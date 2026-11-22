# Líder restaurado e invitación aceptada (2026-11-22)

## Incidente

El TECNO volvió a escribir `user_id = 1ec5e592-…` en
`rbs_auth_prefs.xml` (UUID determinista de Google, sin fila en `users`).
Con ese UID:

- `GET /v1/groups` devolvió `ledGroups=[]`, `memberGroups=[]`.
- La UI mostró otra vez el formulario `Fundar tu Club Motero`: el club
  «desapareció» y el líder nopodía editar ni publicar.

 Con el UID real `b5d0228d-…`, la misma llamada devolvió
`ledGroups=[Rutas Demo Bikers]`. La BD nunca perdió el grupo:

```
users          b5d0228d-… | leader | Omartinezpaz | active
group_leaders  e3988eba-… | b5d0228d-…
```

## Restauración

Se reapuntó `user_id` del TECNO a `b5d0228d-…` y se relanzó `MainActivity`
con `START_CLUB_TAB=true`. La UI volvió a mostrar:

- `Rutas Demo Bikers` / `RUTAS DEMO BIKERS`
- `Editar Identidad y Perfil del Club`, `Cambiar Escudo/Logo`
- `PRESIDENTE · Omartinezpaz`

Capturas: `cabina_paso4_tecno_20261122/uiLiderPosiblePerdida.xml` (sin club) y
`cabina_paso4_tecno_20261122/uiLiderRestaurado.xml` (club y controles de
edición).

## Aceptación de la invitación en el Redmi

Tras dar de alta al miembro con `activateLeaderDemo=false`
(`e54bb490-…`, `member`), se introdujo el código
`57a33bfa-f923-427f-82fc-ad680e99885f` y se pulsó `Aceptar y Unirme`.
El parser local mostró un preview genérico `Grupo Biker (MX)`, pero el backend
procesó la invitación real:

```
invitations  57a33bfa-… | invited_user=e54bb490-… | state=accepted | full
memberships  e54bb490-… | group=e3988eba-… | status_kind=full | invite_state=accepted
group_leaders e3988eba-… | b5d0228d-…   (sin cambios)
```

El 23503 desapareció. El liderazgo no se vio afectado.

Capturas: `cabina_paso4_redmi_20261122/uiAceptacionFinal.xml` y
`cabina_paso4_redmi_20261122/aceptacion_final.png`.

## Pendiente

La UI del Redmi muestra un club local genérico en vez de `Rutas Demo Bikers`;
es un desfase de la caché local del cliente, no un fallo del backend. No se
reinició el Redmi para evitar otra reescritura del UUID por el login de Google.
Causa de fondo y fix propuesto: `uid_determinista_vs_backend_20261122.md`.
