# FASE 1 — doble `RideStarted` y desfase de `lastSeenEpochMs` (2026-11-24)

**Autorización:** el usuario autorizó explícitamente ambos fixes y su verificación en hardware.
**Estado:** los dos bugs están corregidos y verificados en TECNO y REDMI. **Fase 1 NO se declara validada**: la Prueba D completa (30–60 km/h, separación 5–20 m variable, pérdida/recuperación real y SOS controlado) sigue pendiente. Commit 4 diferido.

## Alcance

- No se modificaron GATT, cifrado, `HeartbeatFrame`, PTT, SOS, umbrales ni liderazgo.
- No se cambió `SESSION_TIMEOUT_MS`, `PeerPresencePolicy`, watchdog ni transporte.
- Sin `push`, sin `amend`. Commits atómicos por fix y uno separado de documentación/evidencia.
- El watchdog de `BleAdvTransport` (fase anterior) sigue sin commitear y **no se mezcla** en estos commits.
- Rodada: `43a2efba-8f15-46d7-9d88-4eb85655015d`. Grupo `e3988eba-26e8-425a-80ac-2a65aeda8484`.

## Tabla de entrega

| Bug | Archivo | Cambio | Test |
| --- | --- | --- | --- |
| Doble `RideStarted` | `core/mesh/RideStartDeduplicator.kt` (nuevo), `core/mesh/RideService.kt`, `ui/navigation/MainAppScaffold.kt` | Guard de 500 ms en `dispatchStartRide`, el punto común de los dos callers; `reset()` al detener; la UI no muestra error al caller suprimido | `RideStartDeduplicatorTest` (5 tests) |
| Desfase de `lastSeenEpochMs` | `core/mesh/MeshRelayEngine.kt` | A la entrada de `start()`, usa `System.currentTimeMillis()` como `nowEpochMs` del heartbeat; deja de usar `IncomingPacket.timestampEpochMs` | `MeshRelayIngressTimestampTest` (2 tests) |

## Causa raíz

1. **Doble arranque.** Al entrar en la cabina, `MainAppScaffold.startRideServiceNow` (botón / permisos / eventos de ciclo) y `LiveMapScreen.LaunchedEffect(Unit)` llaman a `RideService.dispatchStartRide` casi a la vez. En la captura anterior se midieron duplicados a 36, 58, 81 y 102 ms, con distinto hilo y mismo PID. Cada `Intent` llegaba a `onStartCommand` y emitía su propio `RideStarted`.
2. **Timestamp atrasado.** `MeshRelayEngine.start()` tomaba `pkt.timestampEpochMs` para `onRawPacketReceived`. Ese valor se escribe en `lastSeenEpochMs` y en el log `HeartbeatRx at=`. Bajo backpresura del `SharedFlow`, los paquetes se procesan muy después de haberse emitido: en TECNO se midió un desfase creciente de 884→935 s y luego 11,5 s en REDMI. La edad del peer contradecía la insignia de presencia.

## Tests

Comando canónico (wrapper `.lck` bloqueado; Gradle 8.10.2 extraído):

```
& "C:\Users\ozmar\.gradle\wrapper\dists\gradle-8.10.2-bin\a04bxjujx95o3nb99gddekhwo\gradle-8.10.2\bin\gradle.bat" :app:testStandardDebugUnitTest --rerun-tasks --no-daemon --console=plain
```

**BUILD SUCCESSFUL — 622 tests, 0 fallos, 0 errores, 0 omitidos** (615 anteriores + 5 + 2 nuevos).

## Build e instalación

```
& "C:\Users\ozmar\.gradle\wrapper\dists\gradle-8.10.2-bin\a04bxjujx95o3nb99gddekhwo\gradle-8.10.2\bin\gradle.bat" :app:assembleStandardDebug "-Prbs.pilot.rideId=43a2efba-8f15-46d7-9d88-4eb85655015d" --rerun-tasks --no-daemon --console=plain
```

- `BuildConfig.java`: `BASE_URL="http://127.0.0.1:3000"`, `PILOT_RIDE_ID` correcto.
- Se usa `standardDebug` (firmado con la clave debug estándar) porque `standardRelease` exige keystore/URL HTTPS reales y deja `PILOT_RIDE_ID` vacío; igual que en verificaciones anteriores.
- SHA-256 compilado e instalado, verificado por `pull` en ambos: `0BCD4CE332991A25957A990B12C5CD90B8A9DB2B4BCFC851488B4EB2E73DF35B`.
- `adb reverse tcp:3000 tcp:3100` activo en TECNO `098813734B105395` y REDMI `b2c398bb` (`UsbFfs`). Fastify `/health` → 200 en :3000 y :3100 del host.

## Verificación en hardware (rodada continua)

Fuentes originales en `docs/evidence/ble_rx_diagnostic_20261124/`:
`fase1_TECNO_log_20261124.txt`, `fase1_REDMI_log_20261124.txt`,
`fase1_TECNO_ui_20261124.txt`, `fase1_REDMI_ui_20261124.txt`,
`fase1_TECNO_pantalla_20261124.png`, `fase1_REDMI_pantalla_20261124.png`.
Muestreo con `sampler_fase1_5s.ps1` (cadencia real ~9 s por par de devices).

| Métrica | TECNO | REDMI |
| --- | --- | --- |
| `RideStarted` en toda la captura | **1** | **1** |
| `RideStart duplicado ignorado` | 0 | 0 |
| `fin de rodada` | 0 | 0 |
| Ventana del log | 16:23:29–16:33:40 (611 s) | 16:24:58–16:33:42 (524 s) |
| `HeartbeatTx` / `HeartbeatRx` | 55 / 33 | 49 / 25 |
| `at=` vs epoch del logcat | **delta 0 s en los 33 RX** | **delta 0 s en los 25 RX** (un caso de 1 s por redondeo) |
| `FragmentRxRaw` / `FragmentReassembled` | 830 / 86 | 424 / 47 |
| RSSI RX | −46…−57 dBm | −57…−60 dBm |
| `ScanWatchdog:` / `AuthMismatch` | 0 / 0 | 0 / 0 |
| Muestras UI con `2 riders` | **54/54** | 33/54 |
| `sin señal` / `Fuera de malla` en UI | **0** | **0** |

- TECNO mantuvo `2 riders | MONITOR MALLA | Señal` en las 54 muestras, de 16:25:27 a 16:33:23 (~8 min).
- REDMI mostró `1 rider` hasta 16:27:32, `2 riders` de 16:27:32 a 16:32:28 (~4 min 56 s) y `1 rider` después. No apareció `sin señal`: el cambio obedeció a pérdida real de sesiones completas, no a la insignia falsa. Tras 16:30:25 siguen llegando `FragmentRxRaw` (−61…−65 dBm) pero dejan de completarse sesiones; es la asimetría de reensamblado ya documentada, no un registro de presencia inconsistente.
- No hubo SOS, ACK ni pérdida/recuperación inducidas: no se manipuló a los conductores.
- El watchdog no disparó porque los fragmentos crudos siguen reiniciando el temporizador.

## Conclusión

- Los dos bugs autorizados están corregidos: **un solo `RideStarted` por arranque** y **`at=` / `lastSeenEpochMs` igual a la hora real de recepción** en ambos equipos, sin contradicción de insignia.
- La asimetría de reensamblado BLE y la validación de velocidad/separación/SOS siguen abiertas. No se declara Fase 1 validada ni se autoriza Commit 4.
