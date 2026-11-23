# Resultado — descenso transitorio del HUD en Redmi (FIX B)

**Fecha:** 2026-11-23. **Ventana:** 00:49:09 → 01:24:16 (35 min, 36 muestras cada 60 s).
**APK:** `standardDebug`, SHA-256 `5EC51CB2073328B8419D083A9191E6A0A5A38AFAD238902700DE7CC7E4AFB33A`.
**Rodada:** `2de260aa-3e24-493b-b9b8-0846366ba1c5`. shortIds TECNO `32191` / Redmi `22873`.

## Conclusión

El descenso **sí se reproduce** (5 de 36 muestras con HUD `1 rider`). **No es un artefacto de UI ni un doble identity**: la notificación persistente pasa a `0 riders conectados en la mesh · 1 sin señal · BLE Real`, lo que confirma `isLost=true` real en el motor de malla. La causa es una serie de **gaps de llegada de advertisements BLE del peer superiores al `LOST_TIMEOUT_MS = 40_500 ms`** de FIX B.

## Tabla de entregable

| Prueba | Resultado esperado | Resultado obtenido | Evidencia |
| --- | --- | --- | --- |
| Observación de 35 min en Redmi (36 muestras, 1/min) | `2 riders` en 36/36 | `2` en 31/36; `1` en 5/36 (min 16, 17, 21, 22, 35) → **reproducible** | `summary.txt`, `REDMI_min00.xml`…`REDMI_min35.xml`, `REDMI_minNN.png` |
| Notificación persistente en los descensos | 1 peer BLE conexo | Min 16/21/35: `0 riders conectados en la mesh · 1 sin señal · BLE Real`. Min 17/22: ya `1 riders conectados…` mientras el HUD sigue en 1 (histéresis de 2 latidos) | `REDMI_notification_min16.txt`, `REDMI_notification_min21.txt`, `REDMI_notification_min35.txt` |
| Gaps de llegada BLE frente al timeout de FIX B | Ningún gap > 40,5 s | **13 gaps > 40,5 s**; máximos 149,2 s, 81,9 s, 78,4 s, 76,5 s, 68,7 s, 63,9 s, 63,3 s y 48,2–48,7 s. Cada descenso cae dentro de uno de esos gaps | `ANALISIS_adv_minuto.txt`, `REDMI_full_log.txt` |
| `GattVoice` desconectado | Sin desconexiones | `GattDisc=0` en 36/36; `role=SERVER localNode=22873 peerNode=32191` constante | `summary.txt`, `ANALISIS_causa.txt` |
| `peer lost` / `Auto-fallback BLE` | Ausentes | `PeerLost=0` y `Fallback=0` en 36/36; el único texto de pérdida es `1 sin señal` en la notificación | `summary.txt` |
| shortIds de malla | Constantes `22873` / `32191` | Constantes en todas las muestras con `GattVoice` | `summary.txt`, `ANALISIS_causa.txt` |
| HeartbeatTx del propio equipo | Continuo | `HBtx` 40–70 por minuto en 36/36 (emisión local intacta) | `summary.txt` |
| TECNO como control (8 muestras cada 5 min) | `2 riders` | `2` en 8/8 | `summary.txt`, `TECNO_min00.xml`…`TECNO_min35.xml` |
| Suite JVM | Verde | `BUILD SUCCESSFUL in 22s` (106 suites / 561 tests / 0 fallos en este estado del árbol) | salida de Gradle 2026-11-23 |

## Correlación entre gaps y descensos

| Descenso (min / hora host) | Gap que lo explica (`BleAdvTransport.AdvRx`) | Notificación en ese minuto |
| --- | --- | --- |
| min 16 / 01:05:09 | `head=125,-65`: 01:03:44 → 01:04:47 (**63,3 s**) | 0 conectados · 1 sin señal |
| min 17 / 01:06:08 | `head=125,-65`: 01:05:21 → 01:06:09 (**48,7 s**) | 1 conectados (recuperada; HUD aún 1 por histéresis) |
| min 21 / 01:10:09 | `head=89,89`: 01:07:34 → 01:10:03 (**149,2 s**) y `head=125,-65`: 01:09:21 → 01:10:25 (**63,9 s**) | 0 conectados · 1 sin señal |
| min 22 / 01:11:08 | `head=125,-65`: 01:10:25 → 01:11:10 (**45,6 s**) | 1 conectados (recuperada; HUD aún 1 por histéresis) |
| min 35 / 01:24:09 | `head=125,-65`: 01:23:27 → 01:24:15 (**48,2 s**) | 0 conectados · 1 sin señal |

## Causa técnica

`MeshRelayEngine.evaluatePeerTimeouts` (`MeshRelayEngine.kt:1610-1632`) marca `isLost = true` cuando
`(now - lastSeenEpochMs) >= LOST_TIMEOUT_MS` (40,5 s). `LiveMapViewModel.kt:619-621` cuenta para el HUD
solo los marcadores cuyo `routeStatus != OFF_MESH_LOST`, y `LiveMapViewModel.kt:507-509` asigna ese estado
cuando `track.isLost`. Por tanto un gap real de recepción superior a 40,5 s baja el HUD a `1 rider` y
cambia la notificación a `1 sin señal`. `updatePeerTrackLocked` (`MeshRelayEngine.kt:1748-1766`) refresca
`lastSeenEpochMs` en cada paquete y aplica la histéresis de 2 latidos al recuperar, lo que explica que en
min 17 y 22 la notificación ya estuviera recuperada mientras el HUD todavía mostraba 1.

FIX B derivó 40,5 s de un intervalo de llegada observado de 27 s en una sesión anterior (factor 1,5).
En esta sesión la distribución de pérdidas es distinta y supera ese valor; la lógica del timeout funciona
según su diseño, pero el parámetro no cubre los gaps medidos aquí. No hay logs de `HeartbeatRx`
instrumentados (`HBrx=0`), por lo que la métrica sustituta son los eventos `AdvRx` de
`BleAdvTransport.kt:761` (400 eventos en 35 min, dos flujos: `head=125,-65` y `head=89,89`).

## Descartado con evidencia

- **GATT:** sin eventos de desconexión en 36/36 muestras; `peerNode=32191` constante.
- **Fallback BLE:** `Auto-fallback BLE` = 0 en 36/36.
- **Doble identity:** la compuerta BLE de `HybridTransportDispatcher` sigue activa y los shortIds no cambian.
- **GPS:** en el descenso de min 16 el XML muestra `⏱ hace 289s` (posición retenida, comportamiento FIX A ya documentado), no una coordenada falsa.
- **UI:** la notificación del sistema confirma `isLost` real; no es un problema de pintado de marcadores.

## Fix propuesto (no aplicado; requiere autorización)

Ninguna de estas opciones toca GATT, crypto, `HeartbeatFrame` ni PTT:

1. **Gracia visual en el HUD (recomendado, bajo riesgo):** al pasar un peer a `isLost`, conservar su tarjeta
   con el estado `sin señal · hace Ns` durante una ventana corta (p. ej. 60 s) en lugar de retirarla del
   contador de inmediato; el dato de verdad sigue siendo la notificación (`0 conectados · 1 sin señal`).
   Esto elimina el parpadeo `2→1→2` sin relajar la detección de malla.
2. **Recalibración con distribución, no con promedio:** instrumentar el intervalo real de llegada por peer y
   derivar el timeout de un percentil alto (p. ej. p95) con un techo de seguridad (p. ej. 90 s). Los gaps
   de esta sesión son 48,2 / 63,3 / 63,9 / 149,2 s: un techo de 90 s cubre 4 de 5 episodios sin enmascarar
   silencios reales de >2 min.
3. **No subir el timeout a 150 s:** retrasaría 2,5 min la detección de isla (Decisión 3.4 C) y SOS,
   empeorando una función de seguridad para ocultar un problema de entrega de radio.

## Limitación de la captura

El script de muestreo no guardó los archivos `REDMI_DESCENSO_minNN_log.txt`: la fecha del dispositivo
devolvió nulo y la ventana puntual quedó vacía. Las capturas XML/PNG de los descensos sí se guardaron, y
el logcat continuo (`REDMI_full_log.txt`, 323.871 líneas) cubre toda la ventana y es la fuente del análisis.
No se afirma evidencia que no existe.

**Estado:** descenso reproducible y con causa documentada. FIX B: lógica correcta, parámetro de 40,5 s
**no validado para esta distribución de gaps**. La compuerta BLE permanece validada. Sin cambios de código,
sin `amend` y sin `push`.
