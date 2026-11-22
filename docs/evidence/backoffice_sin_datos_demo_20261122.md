# Backoffice sin datos demo y base virgen (2026-11-22)

Entorno: worktree `D:\Ruta-Biker-Sync-piloto`, rama `feat/piloto-seguridad`,
backend desde WSL Ubuntu contra PostgreSQL 16 en `127.0.0.1:5433` (base `rbs`),
túnel Cloudflare activo guardado en `docs/evidence/piloto_tunnel_url.txt`
(ignorado por git). Se mantienen la clave de piloto y la clave admin solo en
variables de entorno; sus valores no se registran.

## Motivo

Con la base vacía, los endpoints `/v1/admin/users` y `/v1/admin/groups`
devolvían 6 usuarios y 5 clubes ficticios, y `/v1/admin/analytics/overview`
devolvía cifras fijas (1420 usuarios, 84 clubes, 2180 rodadas, etc.). Esos
fallbacks hacían imposible analizar el comportamiento real del piloto.

## Cambios en `backend/src/lib/admin.ts`

- Eliminados los arrays `DEMO_GROUPS`, `DEMO_USERS`, `DEMO_REPORTS`,
  `DEMO_TICKETS` y `DEMO_SOS_LOGS` y todas sus rutas de fallback.
- Los listados devuelven únicamente filas de PostgreSQL:
  `/v1/admin/users`, `/v1/admin/groups`, `/v1/admin/support/reports`,
  `/v1/admin/support/tickets`, `/v1/admin/audit/sos`.
- Los detalles y mutaciones que no encuentran fila devuelven `null`/`false`
  en vez de modificar objetos en memoria: detalle de club, cambio de estado
  de club/usuario, prórroga de gracia, resolución de reporte y detalle de ticket.
- `createSupportTicket` y `addTicketMessage` propagan errores de BD en lugar de
  insertar tickets falsos en memoria.
- `getAdminAnalyticsOverview` ya no tiene bloque de métricas fijas. Sin datos,
  todas las cifras son 0: se eliminaron los estimados de cancelación SOS (35 %),
  latencia fija (4.8 min) y heartbeats estimados (140 por métrica); el ratio de
  finalización y el ratio offline son 0 cuando no hay rodadas.

## Verificación

- `npx tsc --noEmit -p tsconfig.json`: sin errores.
- Suite backend completa con PostgreSQL real:
  `npx vitest run --no-file-parallelism` → **29/29 archivos, 176/176 pruebas**.
- Nueva prueba de regresión en `backend/test/admin_hardening.test.ts`: con un
  executor que devuelve cero filas, todos los listados son `[]` y la analítica
  devuelve 0 en usuarios, clubes, rodadas, SOS y malla.
- Después de la suite se ejecutó `logs/_truncate_all.sql` y
  `logs/_check_all_empty.sql`: **41/41 tablas del esquema público en 0**, sin
  avisos `NONEMPTY`.

Respuestas del túnel tras reiniciar el backend con el código final (BD vacía):

| Endpoint | Resultado |
|---|---|
| `GET /v1/admin/users` (claves) | 200 `{"users":[],"total":0}` |
| `GET /v1/admin/groups` (claves) | 200 `{"groups":[],"total":0}` |
| `GET /v1/admin/support/reports` (claves) | 200 `{"reports":[]}` |
| `GET /v1/admin/support/tickets` (claves) | 200 `{"tickets":[],"total":0}` |
| `GET /v1/admin/audit/sos` (claves) | 200 `{"logs":[]}` |
| `GET /v1/admin/analytics/overview` (claves) | 200, todas las cifras en 0 |
| `GET /soporte` | 200 |
| `GET /health` | 200 |

Conclusión: el backoffice y `/soporte` muestran solo lo que existe en la base.
El piloto arranca sin usuarios, clubes, rodadas ni telemetría, listo para
registrar datos reales. Las apps siguen detenidas; al abrirlas se repoblará
`pilot_devices` con telemetría legítima.
