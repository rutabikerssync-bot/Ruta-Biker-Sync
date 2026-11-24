# Protocolo de campo 35 min — 2026-11-23 (movimiento real)

## Resultado

**Fase 1 NO alcanza en esta corrida.** La estabilidad de ayer no se reprodujo:

| Dispositivo | `2 riders` (hoy) | `2 riders` (ayer, v2) |
|---|---|---|
| TECNO `098813734B105395` | 7/8 muestras (cayó en +5, volvió en +10) | 8/8 |
| REDMI `b2c398bb` | **1/8** (cayó en +5 y nunca volvió) | 8/8 |

## Condiciones

- APK: `app-standard-debug.apk`, SHA256 `ED0359AD0BCEF0310D2902E0C18E876D8707B1A618FB3D96F721EFF66C8AD59E`, idéntica en ambos móviles.
- Build: `main` @ `292cd01` (harness `PILOT_RIDE_ID`) + `8006a7a` (fix cripto).
- Rodada real `43a2efba-8f15-46d7-9d88-4eb85655015d`, shortIds del manifiesto: TECNO `26079`, REDMI `26216`.
- Backend Fastify en `:3100` con credencial real, `adb reverse tcp:3000 → 3100`, túnel Cloudflare apuntando a `:3100`.
- Instrumentación: `logcat -f /sdcard/rbs_field_log.txt -s BleMeshService:I BleAdvTransport:I RideService:I HeartbeatTx:I`.
- Ventana: 23:08:38 → 23:44:30. 8 muestras cada 5 min con movimiento real y separación variable 2–20 m.

## Muestras (valores corregidos)

Los valores de HUD se extrajeron de los `*_t*.xml`. El `summary.txt` crudo marcó `HUD=?` porque el script buscaba solo el literal `"riders"` (no `"1 rider"`), y `hbrx=0` porque buscaba un tag `HeartbeatRx` que no existe. Ninguno de los dos errores afecta a los logs crudos adjuntos.

| t (min) | TECNO HUD | reasm | hbtx | REDMI HUD | reasm | hbtx |
|---|---|---|---|---|---|---|
| 0 | 2 riders | 12 | 5 | 2 riders | 12 | 6 |
| 5 | 1 rider | 54 | 18 | 1 rider | 54 | 16 |
| 10 | 2 riders | 85 | 34 | 1 rider | 55 | 36 |
| 15 | 2 riders | 108 | 51 | 1 rider | 55 | 62 |
| 20 | 2 riders | 134 | 68 | 1 rider | 56 | 88 |
| 25 | 2 riders | 160 | 85 | 1 rider | 56 | 112 |
| 30 | 2 riders | 189 | 102 | 1 rider | 56 | 137 |
| 35 | 2 riders | 217 | 119 | 1 rider | 56 | 163 |

RSSI: TECNO `-61..-28 dBm`, REDMI `-77..-38 dBm`. Notificación de rodada activa presente en ambos durante toda la ventana.

Último `FragmentReassembled`: TECNO `23:44:17` (casi al cierre); REDMI `23:26:43`. REDMI siguió emitiendo (`HeartbeatTx` hasta 163) pero dejó de reensamblar paquetes del peer.

## Diagnóstico

**No es falta del Fix A de retención de posición.** `e5ed244` (`isPositionRetained`, `lastPositionEpochMs`, `positionAgeSeconds`) ya está funcionalmente en `main`; el cherry-pick de control solo habría cambiado dos comentarios y se descartó. Tampoco es crash, crypto, GATT, `HeartbeatFrame` ni PTT.

El cambio medible frente a ayer es la **tasa de emisión de heartbeats**, ~5× menor:

| | Ayer (35 min) | Hoy (35 min) |
|---|---|---|
| TECNO `HeartbeatTx` | 748 (~21,4/min) | 119 (~3,4/min) |
| REDMI `HeartbeatTx` | 815 (~23,3/min) | 163 (~4,7/min) |

Con el timeout recalibrado de 40,5 s y la gracia visual de 60 s (≈100 s de tolerancia), una cadencia de ~18–20 s por latido deja muy poco margen: cualquier hueco de recepción superior a ~100 s tira el HUD. Exactamente eso se observó en REDMI a partir de +5 min. La recuperación de TECNO en +10 min y su `reasm` creciente hasta el final indican que el enlace no estaba permanentemente roto, sino intermitente y asimétrico.

Diferencias de entorno que no se aislaron en una sola variable: APK distinta (`47D1DA94…` en el worktree `fixab` @ `d88ad76` vs `ED0359…` en `main`), backend `:3001/:3002` vs `:3100`, arranque de la malla desde la UI nueva con UUID/manifiesto real vs harness previo.

## Conclusión y siguiente paso

La evidencia sostiene que **hace falta redundancia (Fase 2)** o una nueva medición con el mismo harness de ayer antes de dar por válida la Fase 1. No se aplicó ningún cambio de código de malla, GATT, crypto ni PTT a partir de este resultado; cualquier ampliación del timeout o de la redundancia requiere autorización nueva.

Estado del código al cierre: `main` @ `292cd01`, 584 tests JVM en verde (0 fallos, 0 errores). Los dos runs de CI fallidos que se reportaron (`45261494715`, `45280150219`) eran el mismo test flaky `Phase1RulesTest`, ya corregido; el run posterior `45287326819` terminó en `BUILD SUCCESSFUL`.
