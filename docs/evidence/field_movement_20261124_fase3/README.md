# Fase 3 — captura de campo 35 min (perfil POSITION 1000/120/1)

## Alcance

- Rodada real: `43a2efba-8f15-46d7-9d88-4eb85655015d`, activa en backend.
- APK idéntico en ambos móviles: `app-standard-debug.apk`, SHA256
  `8177754F16CC09F4B30DDB48518206AEB0C0BE9142112B0FFCE38D759B716046`,
  reconstruido con `RBS_PILOT_RIDE_ID` real.
- Perfil aplicado: `POSITION 1000/120/1`; `VOICE/PTT` sigue `300/20/2`; estándar `1000/120/2`.
  Sin cambios en GATT, cifrado, `HeartbeatFrame`, wire format, SOS ni transporte.
- Participantes reales: TECNO `098813734B105395` / UID `b5d0228d-8ab9-49fd-9cc7-efa452a484f2`
  (leader) y REDMI `b2c398bb` / UID `e54bb490-754a-4aa6-9b4c-8b7b043e9dff` (member).
- Ventana: 2026-11-24 05:38:47 → 06:14:39. 8 muestras cada 5 min; archivos `summary.txt`,
  `*_tNN.xml`, `*_log_tNN.txt` y capturas `*_min0.png` / `*_min35.png`.

## Resultados

| Dispositivo | HeartbeatTx p50 | Reasm recibidos | Reasm p50 | Último reasm | HUD |
|---|---:|---:|---:|---|---|
| TECNO | 13,5 s | 252 | 7,6 s | 06:14:22 | `2 riders` en 8/8 |
| REDMI | 7,6 s | 13 | 14,3 s (p95 378,5 s) | 05:59:13 | `1 rider` desde t=+5 min |

- No hubo gaps de TX superiores a 40 s en ninguno de los dos dispositivos. La cadencia es
  estable y compatible con el timeout recalibrado (suelo 40,5 s). El perfil `1000/120/1`
  mejora la cadencia frente al perfil estándar y evita el RX roto del ensayo `300/20/1`.
- La recepción es asimétrica: TECNO sigue reensamblando heartbeats de REDMI durante toda la
  ventana (252 frames, último a 06:14:22), mientras REDMI deja de recibir frames de TECNO
  después de 05:59:13 (13 frames en total, 4 gaps > 40 s). El HUD de REDMI cae a `1 rider`
  a partir de la muestra de +5 min y no se recupera durante los 30 min restantes.
- RSSI en ventana: TECNO `-82..-43 dBm`, REDMI `-89..-50 dBm`. No hay evidencia de un
  desplome de señal que explique por sí solo la pérdida unilateral; apunta a un problema
  direccional de advertising/reensamblado, no de umbral de timeout.
- `ConvoySignalRx` confirma tráfico en ambos sentidos solo al inicio (TECNO 2, REDMI 1).

## Salvedades

- La velocidad mostrada en el HUD fue `0 km/h` en las 16 lecturas. Aunque el protocolo
  previsto era movimiento con separación variable 2–20 m, **las capturas no prueban el
  desplazamiento**: no se puede afirmar que el escenario de riders en movimiento quedó
  cubierto. Esta limitación se registra explícitamente y debe repetirse si se quiere
  validar el criterio "rider en movimiento con pérdidas intermitentes" en campo.
- `HeartbeatRx=0` en el resumen es artefacto del filtro de logcat (el tag no estaba
  incluido); la recepción real se cuantifica con `FragmentReassembled`.

## Incidencia de estado reproducida

Durante la captura se observó, al mismo tiempo:

- HUD de TECNO: `2 riders`.
- HUD de REDMI: `1 rider`.
- Notificación en ambos móviles: `0 riders conectados en la mesh · 1 sin señal · BLE Real`.
- Mapa: marcador en `OFF_MESH_LOST` mientras el HUD seguía contando al peer.

Detalle en `incidencia_estado_caido.txt` (se conserva como evidencia sin commitear).
La causa es la interpretación dispar de `PeerTrack.isLost`: la gracia solo se aplicaba al
conteo del HUD, mientras mapa, notificación, isla y anuncios usaban el valor crudo.

## Conclusión

- **Fase 1 sigue sin validar**: la captura no cubre desplazamiento demostrable y la malla
  presenta pérdida unilateral sostenida en REDMI. No procede cerrar Fase 1 ni hacer Commit 4.
- **Fase 3 es parcialmente válida**: estabilidad de cadencia y ausencia de gaps > 40 s quedan
  demostradas; la coherencia de estados queda corregida en código por la máquina de estados
  `ACTIVE / STALE_GRACE / LOST` (commit de código separado), con tests de regresión.
- Los umbrales T y G no se modificaron: timeout recalibrado (mínimo 40,5 s, techo 180 s) y
  gracia derivada (2× timeout, tope 120 s). SOS, seguridad y transporte siguen independientes
  de la clasificación visual.

## Comandos de verificación

- Android: `.\gradlew.bat --no-daemon :app:testStandardDebugUnitTest` → 606 tests, 0 fallos.
- Backend (sin cambios en este punto): `PG_TEST_URL=... npm test` → 172 tests, 0 fallos.
