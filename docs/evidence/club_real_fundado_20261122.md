# Club real fundado desde el TECNO (2026-11-22)

## Qué ocurrió

Tras dar de alta al líder en Fastify (`b5d0228d-…`, ver
`url_tunel_vigente_fastify_20261122.md`), el tester escribió el nombre del club
en el formulario `Fundar tu Club Motero` y pulsó `Registrar y Fundar Club (MC)`.
La creación la hizo la app contra el endpoint oficial; **no se insertaron filas
a mano**.

El nombre elegido por el tester fue `Rutas Demo Bikers`. Aunque el nombre
contiene la palabra «Demo», **no es un club demo**: es el club real recién
fundado, con el líder real y la invitación real descrita abajo. No se ha
renombrado sin autorización.

## Resultado en la app (TECNO `098813734B105395`)

- Cabecera: `Rutas Demo Bikers` / `RUTAS DEMO BIKERS`.
- Identidad: `Venezuela (Distrito Capital) · Fundado 15/05/2022`.
- Presidente: `Omartinezpaz` (`1/6 Oficiales`).
- Pestañas: `Miembros (1)` y `Invitaciones (1)`.
- Fundador: `Omartinezpaz` — `Presidente · Fundador`.
- Banner previo en `Mis Grupos y Clubes`:
  `🟢 Fastify online (status: ok) · PostgreSQL 16`. Sin rastro del 403.

Capturas: `cabina_paso4_tecno_20261122/club_fundado.png` y
`cabina_paso4_tecno_20261122/uiClubFundado.xml`.

## Resultado en PostgreSQL

```
groups
  e3988eba-26e8-425a-80ac-2a65aeda8484 | Rutas Demo Bikers | VE | 2026-11-22 12:38:09-04

group_leaders
  e3988eba-26e8-425a-80ac-2a65aeda8484 | b5d0228d-8ab9-49fd-9cc7-efa452a484f2

invitations
  57a33bfa-f923-427f-82fc-ad680e99885f | invited_user=NULL | invited_by=b5d0228d-… | state=pending

memberships
  (sin filas todavía; la UI cuenta al fundador localmente)
```

La invitación pendiente es abierta por QR: `invited_user` quedó en `NULL`, que
es exactamente el comportamiento esperado de la migración
`0019_open_qr_invitations.sql`. El INSERT no falló.

## Pendiente

La aceptación de esa invitación desde el Redmi devuelve 500 con SQLSTATE
23503. Diagnóstico completo en `invitacion_aceptacion_23503_20261122.md`.
