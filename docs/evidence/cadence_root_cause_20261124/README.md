# Causa raíz de la caída de cadencia de heartbeats — 2026-11-24

## Hallazgo

`HeartbeatScheduler` **no está ralentizando el tick**. El bucle de `RideService` sigue siendo
`emitHeartbeatNow(); delay(currentIntervalMs)` y el intervalo nominal sigue siendo 3 s en
`PowerProfile.NORMAL`. Lo que ocurre es que `emitHeartbeatNow()` ahora **bloquea ~9 s** antes
de poder programar el siguiente `delay`, porque el envío BLE se ha vuelto fragmentado.

| | Ayer v2 (`d88ad76`) | Hoy (`HEAD` @ `90cdd78`) |
|---|---|---|
| Heartbeats en ~38 min | 936 | 133 |
| Gap mediano | 3,0 s | **18,0 s** (min 12, max 27) |
| Bytes por heartbeat | 20 B (`PACKET_SIZE_BYTES`) | **52 B** (`SensitiveFrameEnvelope`) |
| Fragmentos BLE | 1 (≤ 24 B, sin `FragmentTx`) | **4** (`FragmentTx … bytes=52 fragments=4 maxBytes=24`) |
| Tiempo en `sendMutex` | ~0 (ráfaga HW asíncrona) | 4 frag × 2 rep × (1000 + 120) ms = **8,96 s** |

## Cadena causal

1. Los commits de cifrado (`bb76801`, `f386332`, `328b36b`) añadieron
   `MeshRelayEngine.sendMeshPacket()`, que envuelve todo paquete `POSITION` (y `PEER_EVENT`/SOS)
   en `SensitiveFrameEnvelope.wrap(key, KIND_HEARTBEAT, packet.toByteArray())`.
2. El sobre añade `HEADER_BYTES = 4 + NONCE(12)` y `TAG(16)` al paquete de 20 B:
   **20 + 16 + 16 = 52 B**. Confirmado en el log de campo (`FragmentTx bytes=52`).
3. `MAX_MANUFACTURER_PAYLOAD_BYTES = 24`, con 16 B útiles por fragmento → **4 fragmentos**.
4. `BleAdvTransport.send()` toma `sendMutex` y llama a `sendFragmented()`, que emite cada
   fragmento dos veces (`FRAGMENT_REPEATS = 2`) y espera `FRAGMENT_BURST_MS (1000) +
   FRAGMENT_GAP_MS (120)` tras cada uno: **~9 s serializados**. Ese mutex lo comparten también
   los reenvíos y eventos de par, por lo que la espera observada sube a ~18 s.
5. El bucle de heartbeats llama a `emitLocalPosition()` → `sendMeshPacket()` → `transport.send()`
   y **espera** a que termine antes de `delay(3000)`. Resultado: 3 s nominales + ~9 s de envío
   + contención ≈ 18 s.

No hay cambio en `HeartbeatScheduler`, en `HEARTBEAT_INTERVAL_MS`, en el perfil de potencia ni
en `MeshPacket.PACKET_SIZE_BYTES` (20 B en ambas versiones). Tampoco hay cambio en el modo de
entrega: `LEGACY_1M/BURST` en los dos días. La regresión es puramente el **coste de airtime del
sobre AEAD aplicado a cada heartbeat**.

## Por qué Fase 2 solo mitigó

`PeerGapTimeoutPolicy` y `PeerGracePolicy` derivan su tolerancia de la cadencia real (3× y 2×
con techos de 180 s / 120 s). Con cadencia real de 18 s eso da ~54 s de timeout y ~108 s de
gracia, suficiente para los huecos cortos (TECNO 8/8) pero no para el hueco de ~13 min en REDMI.
Fase 2 amortigua el síntoma sin eliminar la causa, tal como se registró en
`docs/evidence/field_movement_20261124_fase2/README.md`.

## Restricciones

No se modificó GATT, cifrado (ChaCha20/Ed25519/`SensitiveFrameEnvelope`), `HeartbeatFrame` ni
PTT. `sendFragmented()` y sus constantes son compartidas con `sendVoice()` (PTT), así que
cualquier cambio en esas constantes globales afectaría también a voz.

## Opciones de corrección (requieren decisión)

| Opción | Qué cambia | Riesgo |
|---|---|---|
| **A. Perfil de ráfaga específico para POSITION** (recomendada) | Pasar `burstMs/gapMs/repeats` propios desde `sendMeshPacket()` para heartbeats, dejando VOICE/PTT y los demás flags intactos. Con 300/20/1: 4×320 ms ≈ 1,3 s, compatible con el tick de 3 s. | El valor 1000/120/2 se eligió en campo porque 300 ms perdía fragmentos; habría que revalidar recepción en REDMI. No toca crypto/GATT/PTT/HeartbeatFrame. |
| **B. Desacoplar bucle y envío + coalescer** | `RideService` no espera al burst y descarta heartbeats obsoletos si aún hay uno en vuelo. | No reduce el airtime real (9 s por heartbeat); la cola crecería. No viable sin A. |
| **C. No envolver POSITION en el sobre AEAD** | Restaura 20 B y un solo fragmento. | **Violaría la autorización de cifrado P0** y degradaría seguridad. Solo con autorización explícita nueva. |
| **D. Aceptar ~18 s y mantener Fase 2** | Sin cambios de código. | Fase 1 sigue sin validar; Commit 4 continúa diferido. |

Recomendación: **A**, acotada a `POSITION` y con nueva corrida de campo de 35 min antes de
considerar Commit 4. No implementada: pendiente de autorización.

## Estado

Evidencia: `TECNO_log_t35.txt`, `REDMI_log_t35.txt` y `summary.txt` (misma carpeta).
Sin `push` ni `amend`. Ningún archivo de código modificado por este análisis.
