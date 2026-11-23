# P2 — Verificación hardware (2026-11-22)

APK: `app-standard-debug.apk` (`standardDebug`, firmada con debug keystore; `Signer #1 certificate DN: C=US, O=Android, CN=Android Debug`, SHA-256 `9b531c0f2ef184f15a6b9bdd673ff4e69c47ced9de370f5af21d28ce32f6c832`). Instalada en TECNO `098813734B105395` y Redmi `b2c398bb`.

Backend real: Fastify + PostgreSQL 16 en WSL, ruta `6935451a-86e7-40c8-b245-5a298cf74500` del club `e3988eba-26e8-425a-80ac-2a65aeda8484`. `adb reverse tcp:3000 tcp:3000` en ambos equipos. La caída de red se simuló en caliente con el deep link DEV `rbs://debug/set-base-url`, apuntando la API a `http://127.0.0.1:3999` (puerto sin escucha) y restaurando después a `:3000`, sin reiniciar la app para conservar el roster en memoria.

## Cambios atómicos

| Tarea | Archivo | Cambio | Test |
| --- | --- | --- | --- |
| T1 · H3 outbox | `android/app/src/main/java/com/rutabikersync/app/core/clubroutes/RideLifecycleOutbox*.kt`, `ClubRoutesRepository.kt`, `Phase1ViewModel.kt`, `AppDatabase.kt` | Outbox de eventos con PK UUID + `sequence`; sin mutación optimista; 403/409 terminales; reintento por WorkManager | `RideLifecycleOutboxTest` |
| T2 · M5 retorno | `feature/routes/RouteOrientation.kt`, `RecalculateRouteUseCase.kt`, `feature/livemap/LiveMapScreen.kt` | Inversión en lectura de waypoints de malla y ruta recalculada; emisor intacto | `RouteOrientationTest`, `RouteMeshReturnOrientationTest`, `RecalculateReturnDestinationTest`, `RecalculateRouteUseCaseTest` |
| T3 · M4 replay | `core/mesh/NonReplayingEventBus.kt` | `SharedFlow(replay=0)` al recrear cabina | `NonReplayingEventBusTest`, `RideServiceLifecycleReplayRegressionTest` |
| T4 · Disparador DEV | `feature/groups/RideLifecycleDevTrigger.kt`, `Phase1Screens.kt` | `DEV: completar rodada` por el mismo outbox | suite completa |
| T5 · Fix Room | `core/clubroutes/RideLifecycleOutboxEntity.kt` | Declara `index_ride_lifecycle_outbox_routeId` creado por `MIGRATION_5_6` | suite completa: 102 suites / 545 tests / 0 fallos |

## Ciclo ejecutado y evidencia

| Paso | Estado backend | Captura | UI XML |
| --- | --- | --- | --- |
| 0. Roster listo | todo `NULL` | `cycle_00_ready.png` | `cycle_00_ready_ui.xml` |
| 1. `START_OUTBOUND` | `outbound_started_at = 22:57:15.588837-04` | `cycle_01_start.png` | `cycle_01_start_ui.xml` |
| 2. `ARRIVE_DESTINATION` | `destination_reached_at = 22:59:03.20437-04` | `cycle_06_arrive_offline.png` | `cycle_06_arrive_offline_ui.xml` |
| 3. `START_RETURN` con red caída | `return_started_at = NULL` (sin mutar) | `cycle_08_return_offline.png` | `cycle_08_return_offline_ui.xml` |
| 4. Reintento del worker | `return_started_at = 23:04:51.695823-04`, outbox vacío | `cycle_09_return_online.png` | `cycle_09_return_online_ui.xml` |
| 5. Mapa en retorno (M5) | fase `En retorno` | `cycle_10_return_map.png` | `cycle_10_return_map_ui.xml` |
| 6. `COMPLETE_RIDE` (DEV) | `completed_at = 23:07:41.174853-04` | `cycle_15_complete.png`, `cycle_16_complete_state.png` | `cycle_15_complete_ui.xml`, `cycle_16_complete_state_ui.xml` |
| 7. Redmi sincronizado | roster con `En retorno` / `Ya en casa` | `redmi_cycle_final.png` | `redmi_cycle_final_ui.xml` |

Estado del outbox local durante la caída (BD copiada con su WAL):

```
ride_lifecycle_outbox [(id, routeId, 'START_RETURN', 1, ..., synced=0, attempts=2,
                        'Failed to connect to /127.0.0.1:3999')]
```

Logcat del reintento:

```
23:03:52.619  WM-WorkerWrapper: Starting work for ...RideLifecycleOutboxWorker
23:03:52.649  WM-WorkerWrapper: Worker result RETRY
23:04:52.619  WM-WorkerWrapper: Starting work for ...RideLifecycleOutboxWorker
23:04:52.649  WM-WorkerWrapper: Worker result SUCCESS
```

## `git show --stat`

```
02fce48 fix(ciclo): outbox de transiciones sin mutacion optimista
 12 files changed, 696 insertions(+), 75 deletions(-)
4f836a7 fix(mapa): invertir retorno de ruta de malla y recalculada
 7 files changed, 384 insertions(+), 14 deletions(-)
96d5d9c test(ciclo): verificar replay cero al recrear la cabina
 3 files changed, 62 insertions(+), 3 deletions(-)
a4bf1ee feat(dev): disparador DEV de cierre de rodada
 2 files changed, 54 insertions(+)
5da5b1b fix(ciclo): declarar indice de routeId creado por la migracion 5 a 6
 1 file changed, 5 insertions(+), 1 deletion(-)
```

Rama `feat/logo-oficial-escala`, `ahead 5`. Sin `push` y sin `amend`; el push lo ejecuta el usuario.
