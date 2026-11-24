# Protocolo de campo 35 min — Fase 2 — 2026-11-24 (movimiento real)

## Resultado

**Mejora medible, pero insuficiente para dar por válida la Fase 1.** El Commit 4 sigue diferido.

| Dispositivo | Ayer v2 | Fase 1 (23 nov) | Fase 2 (24 nov) |
|---|---|---|---|
| TECNO `098813734B105395` | 8/8 | 7/8 | **8/8** ✅ |
| REDMI `b2c398bb` | 8/8 | 1/8 | **3/8** (cayó en +10, volvió en +35) ⚠️ |

Fase 2 eliminó la caída de TECNO y permitió que REDMI **se recuperara antes del cierre** (algo que no ocurrió en Fase 1), pero REDMI pasó 5 muestras consecutivas en `1 rider`. No se alcanzó la estabilidad 8/8 de ayer.

## Condiciones

- APK: `app-standard-debug.apk`, SHA256 `E4028FAF01F80DA4985048B754A3C2EC7505CD287A46614531D69C4EE502C833`, idéntica en ambos móviles.
- Build: `main` @ `90cdd78` (Fase 2: timeout y gracia adaptativos) + `292cd01` (harness UUID) + `8006a7a` (fix cripto). Recompilada con `RBS_PILOT_RIDE_ID` para conservar el UUID real; se detectó y corrigió que un build intermedio sin esa variable perdía el harness.
- Rodada real `43a2efba-8f15-46d7-9d88-4eb85655015d`, `state=active`, grupo `e3988eba-26e8-425a-80ac-2a65aeda8484`.
- Backend Fastify en `:3100` con credencial real, `adb reverse tcp:3000 → 3100`, túnel Cloudflare activo.
- Malla iniciada desde la UI nueva (`Iniciar Ruta Libre`) en ambos. Estado previo al cronómetro: REDMI veía `2 riders`; TECNO tardó ~2 min en converger y arrancó en `2 riders`.
- Ventana: 00:02:31 → 00:38:26. 8 muestras cada 5 min con movimiento real y separación variable 2–20 m.

## Muestras

Valores extraídos de los `*_t*.xml` con el parser corregido (`\d+ rider(?:s)?`). El `summary.txt` ya usa ese parser y cuenta `Heartbeat received from` como RX real.

| t (min) | TECNO HUD | reasm | hbtx | REDMI HUD | reasm | hbtx |
|---|---|---|---|---|---|---|
| 0 | 2 riders | 13 | 6 | 2 riders | 35 | 10 |
| 5 | 2 riders | 65 | 22 | 2 riders | 63 | 20 |
| 10 | 2 riders | 105 | 38 | **1 rider** | 64 | 47 |
| 15 | 2 riders | 152 | 55 | 1 rider | 64 | 71 |
| 20 | 2 riders | 198 | 72 | 1 rider | 65 | 96 |
| 25 | 2 riders | 239 | 89 | 1 rider | 65 | 121 |
| 30 | 2 riders | 286 | 106 | 1 rider | 66 | 147 |
| 35 | 2 riders | 334 | 123 | **2 riders** | 67 | 171 |

RSSI: TECNO `-66..-40 dBm`, REDMI `-82..-45 dBm`. Notificación de rodada activa presente en ambos durante toda la ventana.

Último `FragmentReassembled`: TECNO `00:38:16` (10 s antes del cierre); REDMI `00:36:08`. En REDMI el reensamblado se estancó entre `00:08:29` y `00:21:26` (~13 min), volvió brevemente, se estancó hasta `00:29:58` y se reactivó a las `00:36:08`, coincidiendo con la recuperación del HUD en +35. Sigue emitiendo (`HeartbeatTx` hasta 171): es el **camino de recepción** el intermitente, no el emisor.

## Análisis

- **Fase 2 funciona como fue diseñada**: con timeout de hasta 180 s y gracia de hasta 120 s (~5 min de tolerancia), TECNO no cayó en ninguna muestra y REDMI soportó huecos que en Fase 1 habrían sido definitivos, recuperándose al volver a reensamblar.
- **No corrige la causa raíz**: la cadencia de emisión sigue ~18–20 s/latido (TECNO 123 y REDMI 171 en 35 min), prácticamente igual que en Fase 1 (119 y 163). El `HeartbeatScheduler` nominal de 3/5/10 s no se está cumpliendo; ese desajuste sigue sin aislarse.
- Un hueco de recepción de ~13 min en REDMI supera cualquier tolerancia razonable del HUD. La asimetría reaparece en el mismo dispositivo que en Fase 1, con RSSI más débil (`-82..-45` vs `-77..-38`), apuntando a recepción BLE/fragmentación y no a la lógica de retención de posición ni al timeout.
- No hay crash, `FATAL`, ni fallos de crypto/GATT/`HeartbeatFrame`/PTT. Ninguno de esos subsistemas se modificó en Fase 2.

## Conclusión y siguiente paso

Fase 2 es una **mitigación útil pero no una validación**. La evidencia no sostiene cerrar Fase 1 ni avanzar a Commit 4. Quedan dos caminos, ambos requieren autorización nueva:

1. **Aislar la caída de cadencia** del `HeartbeatScheduler` (¿carga de la UI nueva, manifiesto real vía túnel, o perfil de potencia?) — ataca la causa raíz sin tocar GATT/crypto.
2. **Investigar la recepción en REDMI** (por qué `FragmentReassembled` se detiene 13 min con RSSI aceptable), que es el modo de fallo observado en las dos corridas con el harness real.

Estado del código al cierre: `main` @ `90cdd78`, 593 tests JVM en verde (0 fallos, 0 errores), `assembleStandardDebug` y `assembleStandardDebugAndroidTest` BUILD SUCCESSFUL. Sin `push` ni `amend`.
