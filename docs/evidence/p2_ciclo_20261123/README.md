# Evidencia P2 — ciclo H3 + M5 + M4 (23 nov 2026)

Prueba controlada con un móvil TECNO (líder, usuario `b5d0228d-…484f2`) y observación secundaria en Redmi (`b2c398bb`, `e54bb490-…9dff`, **sin reincorporarlo al club**). Backend Fastify local en `http://127.0.0.1:3000`; túnel `adb reverse tcp:3000 tcp:3000` en ambos dispositivos; APK `app-standard-debug.apk` firmada con debug keystore (misma estrategia de Fase 5/FIX B).

## Escenario

Ruta activa `d03593b8-a5ae-4e56-a8d2-56bdc474d1f1`, grupo `e3988eba-26e8-425a-80ac-2a65aeda8484`, salida `2026-11-24T16:27:00.000Z`.

| Paso | Acción | Resultado verificado |
|---|---|---|
| 00 | Arranque con caché local | `PARTICIPANTES (1)`; tarjeta bajo scroll (no era “Sin programar” real). `tecno_00_home.xml`, `tecno_01_baseline_roster.{png,xml}`, `backend_01_baseline.json`. |
| 01 | `Asistiré` → `En el sitio` | Backend `status=confirmed`, `onSiteAt=2026-11-23T23:05:39.916Z`. `tecno_02_after_rsvp.xml`, `tecno_03_after_onsite.xml`. |
| 02 | `Iniciar Ruta` | `outbound_started_at=2026-11-23T23:06:16.746Z`. Cabina abierta; no quedó fila en outbox. `tecno_04_start_offline.png/xml` (el primer tap cayó fuera del botón tras el scroll; el segundo acertó). |
| 03 | Caída de red controlada antes de `arrive-destination` | `adb reverse --remove` no bastó por conexión HTTP reutilizada; se pausó el proceso backend con `kill -STOP 275` (WSL). Al tocar `Llegamos`: UI `⏳ ARRIVE_DESTINATION pendiente de sincronizar.` y fila en `ride_lifecycle_outbox` (`event=ARRIVE_DESTINATION`, `synced=0`). Backend inalcanzable y **sin** `destination_reached_at`. Sin mutación optimista. `tecno_06_arrive_offline.{png,xml}`, `backend_04_start_offline.json`. |
| 04 | Restauración `kill -CONT 275` | Reintento automático: `destination_reached_at=2026-11-23T23:07:57.952Z`, outbox drenado, mensaje de pendiente desaparece. `tecno_07_arrive_retry_synced.{png,xml}`, `backend_07_after_retry.json`, `tecno_07_logcat_retry.txt`. |
| 05 | `Iniciar Retorno` (M5) | `return_started_at=2026-11-23T23:08:58.850Z`. Cabina en retorno con orientación inversa resuelta en lectura (`RouteOrientation` / `RecalculateRouteUseCase`), sin mutar la ruta cacheada. `tecno_08_before_return.xml`, `tecno_09_return_cockpit.{png,xml}`, `backend_09_after_return.json`. |
| 06 | Recreación de UI (M4) | `am force-stop` + `am start`: la tarjeta muestra `Ya en casa` y `Abrir mapa (Retorno)`; **no** se re-ejecutaron `start/arrive/return`, outbox vacío y timestamps del backend idénticos. `tecno_10_ui_recreated_no_replay.{png,xml}`, `backend_10_after_ui_recreate.json`. |
| 07 | `complete` desde panel DEV | `completed_at=2026-11-23T23:11:51.454Z`; outbox vacío. El botón de retorno pasó a “Mantén presionado para detener” (flujo productivo de parada, no usado para este cierre). `tecno_13_dev_panel.{png,xml}`, `tecno_14_after_complete.{png,xml}`, `backend_14_completed.json`. |

## Detalles de instrumentación

- Consultas SQLite sobre `ruta_biker_sync.db` + `-wal` copiados con `adb exec-out run-as … cat` (la redirección `>` de PowerShell corrompe binarios). Esquema verificado: `CREATE INDEX index_ride_lifecycle_outbox_routeId ON ride_lifecycle_outbox (routeId)`.
- La tabla de caché es `club_routes_cache` (columnas camelCase `startsAt`, `status`), no `club_routes`.
- Logcat filtrado por PID de app (`$appPid`, nunca `$pid` en PowerShell) porque `core/clubroutes` no emite `Log.*`; se conserva `tecno_07_logcat_retry.txt`.
- Redmi `b2c398bb` se mantuvo en `Ruta Libre / Modo Libre` (`redmi_00_observation.{png,xml}`), sin alta en el grupo `e3988eba-…8484` ni cambio de usuario (`e54bb490-…9dff`). Su logcat (`redmi_p2_logcat.txt`) no muestra `FATAL`/crash del app durante la observación.
- Las transiciones 403/409 quedan cubiertas por los tests JVM de `RideLifecycleStatePolicy` (`shouldMutateRoster` solo con `Applied`), no se repitieron en hardware para no alterar el club.

## Suite y build

```
.\gradlew.bat :app:testStandardDebugUnitTest --console=plain --rerun-tasks
BUILD SUCCESSFUL — Suites=103 Tests=552 Failures=0 Errors=0 Skipped=0
.\gradlew.bat :app:assembleStandardDebug --console=plain
BUILD SUCCESSFUL
```

## Alcance

Los seis commits de P2 no tocan GATT, crypto, `HeartbeatFrame`, PTT ni la lógica de RSVP de Fase 5. Redmi solo se usó para observación de estabilidad/logcat; no se le dio alta en el grupo ni se cambió su identidad.
