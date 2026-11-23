# Resultado — fix de FIX B: gracia visual + timeout por percentil

**Fecha:** 2026-11-23. **Ventana:** 01:49:42 → 02:24:41 (35 min, 36 muestras cada 60 s).
**APK:** `standardDebug`, SHA-256 `92E5CE96EC9F8E70D1AA00F8BDB40DD8F4DB765559F17907930A227B62C54DB7`.
**Rodada:** `2de260aa-3e24-493b-b9b8-0846366ba1c5`. shortIds TECNO `32191` / Redmi `22873`.
**Implementación:** gracia visual de 60 s en `LiveMapController` y timeout dinámico p95 (suelo 40,5 s, techo 90 s) en `MeshRelayEngine`.

## Conclusión

El fix **reduce el descenso de 5/36 muestras a 1/36** y la gracia se observa en 3 muestras. **No cumple estrictamente** el criterio de «HUD 2 riders en 36/36»: en el minuto 13 el HUD bajó a `1` al agotarse la gracia de 60 s. No hay regresión de UI: en el minuto 12 el HUD seguía en `2` con estado `Sin señal (gracia)` mientras la notificación ya indicaba `0 riders conectados · 1 sin señal`.

## Tabla de entregable

| Prueba | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- |
| Tarea 1 — gracia visual | Peer con gap > timeout sigue contando ~60 s con `sin señal` | `GRACIA=SI` en min 12, 22 y 34; HUD se mantuvo en `2` en esos tres; en min 12 la notificación ya era `0 conectados · 1 sin señal` y el HUD no bajó | `summary.txt`, `REDMI_min12.xml`…`min34.xml`, `REDMI_notification` en `summary.txt` |
| Tarea 1 — test de 50 s | Cuenta durante 60 s, luego `isLost` | `PeerSignalGraceTrackerTest`: 3/3 tests verdes | `PeerSignalGraceTrackerTest.kt` |
| Tarea 2 — p95 con techo/suelo | 80 s → 80 s; 30 s → 40,5 s; 120 s → 90 s | 5/5 tests verdes; ventana de 20 gaps y mínimo de 5 muestras | `PeerTimeoutCalibratorTest.kt` |
| Tarea 2 — integración | Motor usa timeout efectivo | `evaluatePeerTimeouts` compara contra `effectiveLostTimeoutMs()`; gaps por peer se registran en `updatePeerTrackLocked` | `MeshRelayEngine.kt:277-287,1621-1641,1713-1720` |
| Tarea 3 — HUD Redmi | `2 riders` en 36/36 | `2` en 35/36; `1` solo en min 13 (02:02:42) | `summary.txt`, `REDMI_min00.xml`…`REDMI_min35.xml`, `REDMI_minNN.png` |
| Tarea 3 — gaps BLE | Sin silencios que superen timeout+gracia | 13 gaps > 40,5 s; 2 > 90 s; máximo 147,8 s (02:09:41→02:12:09). El descenso de min 13 sigue a un gap de 75,8 s (02:01:41→02:02:56) que se registró *después* de la caída | `ANALISIS_adv_minuto.txt`, `REDMI_full_log.txt` |
| Tarea 3 — TECNO control | `2 riders` | 2 en 7/7 muestras legibles; en min 20 la UI mostraba el diálogo `SOS RECIBIDO POR MESH` en lugar del HUD | `summary.txt`, `TECNO_min20.xml`, `TECNO_full_log.txt` |
| GATT / fallback | Sin desconexiones ni fallback | `GattDisc=0`, `Fallback=0` en 36/36; `peerNode=32191` constante | `summary.txt`, `REDMI_full_log.txt` |
| Suite JVM | Verde | `BUILD SUCCESSFUL` — 108 suites / 569 tests / 0 fallos | salida de Gradle 2026-11-23 |

## Correlación de los tres eventos de gracia

| Minuto / hora | Estado | Gap que lo explica | Resultado |
| --- | --- | --- | --- |
| 12 / 02:01:41 | `HUD=2 GRACIA=SI`, notificación `0 conectados · 1 sin señal` | `head=125,-65`: 02:01:41 → 02:02:56 (**75,8 s**) | La gracia evitó la bajada inmediata |
| 13 / 02:02:42 | `HUD=1 GRACIA=NO` | El mismo gap de 75,8 s seguía activo; el timeout aún era el suelo de 40,5 s porque el gap grande todavía no se había registrado | Gracia agotada: cobertura 40,5 + 60 = 100,5 s < silencio observado (~121 s desde 02:00:55) |
| 22 / 02:11:42 | `HUD=2 GRACIA=SI`, notificación `0 conectados · 1 sin señal` | `head=125,-65`: 02:09:41 → 02:12:09 (**147,8 s**); `head=89,89`: 02:09:24 → 02:10:49 (**85,2 s**) | Tras registrar el gap previo de 75,8 s el timeout efectivo subió y el HUD no bajó en ninguna muestra; no se puede descartar una caída breve entre muestras (02:11:56→02:12:09) |
| 34 / 02:23:41 | `HUD=2 GRACIA=SI` | `head=89,89`: 02:22:15 → 02:24:00 (**104,9 s**) | HUD estable durante la gracia |

## Causa del único descenso

En min 12 el motor ya había marcado `isLost` con el timeout de suelo (40,5 s). La gracia de 60 s mantuvo `2 riders` y mostró `◌ Sin señal (gracia)`, pero el silencio de radio continuó más allá de los 100,5 s combinados y en min 13 el marcador pasó a `OFF_MESH_LOST`. Es el comportamiento previsto de los parámetros autorizados: **60 s de gracia y techo de 90 s no cubren un silencio de ~2 min**. No es un fallo de pintado: la notificación persistente decía `0 conectados · 1 sin señal`.

El calibrador necesita 5 muestras y usa una ventana de 20 gaps. El gap de 75,8 s se registró al recuperarse en 02:02:56, demasiado tarde para el descenso de min 13. A partir de ahí el timeout efectivo pudo subir (inferido del comportamiento de min 22; **no se instrumentó un log del valor exacto**, por lo que no se afirma una cifra medida).

## Descartado

- **GATT:** `GattDisc=0` en 36/36; `role=SERVER localNode=22873 peerNode=32191` constante.
- **Fallback BLE:** `Fallback=0` en 36/36.
- **shortIds:** sin cambios (`22873` / `32191`).
- **Doble identity:** compuerta BLE sigue activa; la notificación distingue `conectados` de `sin señal`.
- **UI:** en min 12 la notificación ya reportaba pérdida mientras el HUD seguía en 2; la gracia es intencional.

## Limitaciones de la captura

- Los `REDMI_minNN_log.txt` son ventanas de hasta 3.000 líneas; en min 20 no incluyeron latidos aunque el log continuo sí registra 13 `HeartbeatTx` en 02:09. El log continuo (`REDMI_full_log.txt`, ~39 MB) es la fuente de verdad.
- En min 20 TECNO mostró un diálogo de SOS recibido (shortId `22873`) en lugar del HUD; por eso `HUD=SIN_HUD` en esa única muestra de control. No es una bajada del contador. El PNG de esa muestra quedó vacío y se excluyó; el XML conserva el diálogo.
- Los gaps se miden sobre `AdvRx` de `BleAdvTransport`, que incluye dos `head` (`89,89` y `125,-65`) sin discriminar de forma definitiva qué flujo corresponde a cada tipo de paquete. Las cifras son observables de radio, no una medida directa del timeout efectivo.
- No se instrumentó log del timeout efectivo ni del inicio/fin de gracia; las correlaciones se infieren de UI, notificación y logcat.

## Estado

Fix aplicado y verificado en hardware con resultado **parcial**: 5 descensos → 1 descenso en 35 min; gracia visible y funcionando, timeout recalibrado sin superar el techo de 90 s. Sin cambios en GATT, crypto, `HeartbeatFrame` ni PTT. Sin `amend` y sin `push`.
