# Evidencia FIX A — retención de posición con GPS expirado (2026-11-21)

Rodada local de 35 min (18:47:26 → 19:23:40, 8 muestras cada 5 min) con TECNO KI5k
(`098813734B105395`, shortId `9120`) y Redmi Note 13 Pro 5G (`b2c398bb`, shortId
`11034`) a ~1 m. Mismo APK `app-standard-debug.apk` compilado con el Fix A y con
`Ed25519SosCrypto.kt` en su versión `HEAD`.

## Qué valida

- **Retención de posición (Fix A): OK.** En TECNO aparece el chip ámbar `⏱ hace Ns`
  desde el minuto 5 y crece monótonamente hasta `1484 s` en el minuto 35. El pin
  sigue siendo la última coordenada real recibida de Redmi; no se sustituyó por
  `(0,0)` ni por CDMX. Redmi descartó sus propios fixes por precisión
  (`accM=265`, `accM=194`), por lo que publicó el centinela y TECNO ejerció la
  retención.
- **`ridersCount` con heartbeat vivo: OK en JVM** (test dedicado). En campo no fue
  estable: el HUD osciló entre `2 riders` y `1 rider`, y al cierre ambos dispositivos
  muestran `1 rider`.

## Limitación encontrada (fuera del alcance autorizado)

`HeartbeatRx` en TECNO llega cada **~27 s** (`gaps_sec=27,27,27,27`), muy por encima
del timeout de 15 s de `PeerHealthMonitor`. El peer se declara perdido por timeout,
no por GPS: es el comportamiento correcto del monitor timeout-only. La cadencia
(`FRAGMENT_REPEATS`, 4 fragmentos × 2 repeticiones de un heartbeat cifrado de 52 B)
queda como **Fix B**, pendiente de autorización. Este commit no la toca.

## Nota sobre `ride_timeline.tsv`

La columna `riders` se extrajo con un regex que solo reconocía la forma plural
`\d+ riders`. Las celdas vacías corresponden a `1 rider` (singular), no a ausencia
de datos. Los volcados `final_ui_*.xml` confirman el estado final `1 rider`.

## Archivos

- `ride_timeline.tsv`: muestreo cada 5 min.
- `tecno_min*.png`, `redmi_min*.png`: capturas por muestra.
- `logcat_tecno.txt`, `logcat_redmi.txt`: logcat filtrado al cierre.
- `final_ui_tecno.xml`, `final_ui_redmi.xml`, `final_logcat_*.txt`: estado final.
- `heartbeat_gaps.txt`: intervalos observados de `HeartbeatRx` en TECNO.

## Uso posterior (FIX B fase 1)

Este `heartbeat_gaps.txt` es la fuente de `MeshRelayEngine.OBSERVED_HEARTBEAT_ARRIVAL_INTERVAL_MS = 27_000L`. El timeout de pérdida se deriva ahora como `27 s × 3/2 = 40,5 s` (antes 15 s), con `LOST_THRESHOLD_BEATS` calculado a partir de ese timeout. Como esta fase **no modifica la radio** (`FRAGMENT_REPEATS`, ráfagas ni scheduler), la medición sigue siendo representativa.
