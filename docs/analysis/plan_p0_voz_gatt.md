# Plan P0 — Voz PTT sobre GATT y suscripción reactiva de `LiveMapScreen`

**Estado:** IMPLEMENTADO EN ÁRBOL DE TRABAJO (P0.1–P0.4, autorización `Autorizar todo P0`). Tests y gate locales verdes; campo validado con **2 riders** y `rbs.gatt.multipeer=false` (prueba #5, voz bidireccional y audio audible). **Sin commit, sin push**; queda pendiente la prueba con 3 riders y el flag en `true`.
**Restricción cumplida:** no se cambió `HeartbeatFrame`, el formato cifrado de `VoicePacket`, el manifiesto firmado ni la lógica de liderazgo. Evidencia local: `docs/evidence/p0_voz_gatt_20261127/verificacion_local.md`.

## 1. Hallazgos confirmados en código (HEAD `b0a949c`)

| ID | Hallazgo | Evidencia | Impacto |
|---|---|---|---|
| H1 | El callback que entrega la dirección del peer a GATT se invoca **solo para tramas NO envelope**. Los heartbeats viajan en `SensitiveFrameEnvelope`, por lo que la dirección nunca llega a `GattVoiceTransport`. El engine sí registra el sufijo tras descifrar, pero no lo notifica al transporte. | `BleAdvTransport.kt:919-926`, `950-957`; `RideService.kt:500-514`; `MeshRelayEngine.kt:1761-1763` | `isVoiceReady=false`; la voz cae al fallback de advertising. |
| H2 | `GattVoiceTransport` mantiene **un solo enlace**: campos únicos `peerAddress`/`peerShortId` y rechazo explícito de un segundo peer. | `GattVoiceTransport.kt:91-93`, `221-228` | Con 3–5 riders solo se conecta el primero; los demás descartan voz. |
| H3 | El límite de backpressure es **32 solo si GATT está listo**; si no, vuelve a **2** y se descartan tramas. | `MeshRelayEngine.kt:380`, `1600-1613` | Descarte masivo (`VoicePacketTx descartado por backpressure`) cuando H1/H2 están activos. |
| H4 | `LiveMapScreen` lee `RideService.getActiveRelayEngine()` durante composición y sus `DisposableEffect` no incluyen `rideServiceState.rideId`. El engine puede sustituirse al reiniciar BLE. | `LiveMapScreen.kt:199`, `206-220`, `233-242`; `RideService.kt:1523` | PTT puede seguir apuntando al engine viejo tras reinicio o cambio de rodada. |
| H5 | `PttAudioEngine` usa por defecto una `clubPttKey` fija y ni `LiveMapScreen` ni `BleMeshService` le pasan una clave derivada del manifiesto. Además pueden coexistir dos instancias del motor. | `PttAudioEngine.kt:37-43`; `LiveMapScreen.kt:206-220`; `BleMeshService.kt:279-302` | Riesgo MASVS: clave predecible y posible doble reproducción / foco de audio duplicado. |
| H6 | `BleMeshService` avisa `VoicePacketTx descartado: no hay relayEngine activo` si dispara PTT antes de que el servicio enlace el engine. | `BleMeshService.kt:281-296` | Pérdida de las primeras tramas al arrancar. |

> Nota sobre H1: el comentario de `BleAdvTransport` evita deliberadamente derivar identidad desde un sobre cifrado. El plan respeta eso: se propone propagar **la dirección MAC del scan** sin inferir `shortId` del cifrado, y resolver la identidad solo con el `shortId` ya autenticado por el engine.

## 2. Objetivo

Conseguir voz PTT extremo a extremo validada en 2 riders y luego en 3–5, sin cambiar el formato de `VoicePacket`, sin tocar `HeartbeatFrame`, sin alterar claves de manifiesto ni liderazgo, y manteniendo el advertising legacy intacto para chat/SOS/posición.

## 3. Propuesta por fases

### P0.1 — Propagar dirección autenticada al transporte GATT *(bloqueante, habilita todo lo demás)*

- En `BleAdvTransport`, emitir la MAC completa para **todas** las tramas de scan, pero con `senderShortId = null` cuando el bytes sea un `SensitiveFrameEnvelope`. Así no se fabrica identidad a partir del cifrado.
- En `RideService`, Guardar `MAC por sufijo` y, cuando el engine haya validado el sobre y registrado `peerAddresses` (`MeshRelayEngine.kt:1761-1763`), resolver `shortId` con `resolveShortIdForAddressSuffix()` y llamar `gatt.onMeshPeerAddress(mac, shortId)`.
- Alternativa equivalente: añadir en `MeshRelayEngine` un `StateFlow`/callback `onAuthenticatedPeerAddress(shortId, suffix)` disparado justo después de la validación de manifiesto, y que `RideService` lo consuma. No modificar el desencriptado ni el `HeartbeatFrame`.
- **Pruebas:** extender `VoiceMeshRelayTest` y añadir un test de `BleAdvTransport` con un sobre cifrado simulado que compruebe: (a) MAC propagada, (b) `shortId=null`, (c) `onMeshPeerAddress` se llama solo tras autenticación.
- **Riesgo:** bajo. Sin cambio de crypto. **Requiere autorización** porque cablea GATT/voz.

### P0.2 — Soporte multipeer en `GattVoiceTransport`

- Sustituir los campos únicos por un registro `links: Map<UShort, VoiceLink>` con `role`, `address`, `mtu`, `reassembler` y contadores propios por peer.
- `setAllowedPeerShortIds()` debe seguir alimentándose del manifiesto (ya ocurre en `RideService.kt:498`), y `onMeshPeerAddress()` debe **añadir/actualizar** un enlace en vez de rechazar el segundo (`GattVoiceTransport.kt:225-228`).
- `sendVoiceFrame()` hace fan-out a todos los enlaces `isVoiceReady`, con backpressure **por enlace**; `deliverPayload()` incluye el `shortId` del enlace para que el engine no atribuya la voz al nodo local.
- Extraer un `GattVoiceLinkRegistry` puro (sin Android) para probar roles, allowlist y fan-out en JVM; la conexión GATT real se valida en dispositivos físicos.
- **Pruebas:** unitarias del registry + prueba de campo con 3 teléfonos. Mantener `MAX_PENDING_FRAMES=16` por enlace (`GattVoiceTransport.kt:73`).
- **Riesgo:** medio. **Flag de rollback** `rbs.gatt.multipeer` (default `false`) conservando el camino de un solo peer hasta validar en campo.

### P0.3 — `LiveMapScreen` reactiva al engine correcto

- Exponer `RideService.activeRelayEngineFlow: StateFlow<MeshRelayEngine?>` (o un `engineGeneration: Long` en `RideSessionState`) actualizado en cada punto donde cambia `activeController` (`RideService.kt:1791`, `1929`, `2053`, `2118`, etc.).
- En `LiveMapScreen`, hacer `collectAsState()` de ese flow y usarlo como clave de `remember`/`DisposableEffect`, junto con `rideServiceState.rideId`. La suscripción `onVoiceReceived` debe cancelarse y recrearse cuando cambie el engine.
- **Pruebas:** ampliar `RideScreenTransitionResilienceTest` para afirmar `unsubscribe` y nueva suscripción tras simular reinicio BLE/cambio de `rideId`.
- **Riesgo:** bajo; solo UI. No requiere tocar crypto.

### P0.4 — Clave PTT y dueño único del motor *(requiere aprobación de seguridad)*

- Sustituir el default `ByteArray(32) { it + 0xA5 }` por una clave derivada del manifiesto firmado o, en su defecto, **fallar cerrado** con `FAIL_CLOSED_PTT_KEY_UNAVAILABLE` en vez de cifrar con clave predecible.
- Unificar la propiedad del motor: elegir `BleMeshService` (servicio foreground) como único dueño y que `LiveMapScreen` solo le envíe intents/eventos, evitando dos `PttAudioEngine`, dos `JitterBuffer` y dos suscripciones RX.
- **Pruebas:** constructor sin clave real deshabilita PTT; test de suscripción RX única; revisión MASVS del flujo de clave.
- **Riesgo:** alto (crypto/voz). **No incluir en el mismo cambio que P0.1–P0.3** sin firma explícita.

## 4. Secuencia recomendada y definición de hecha

1. P0.1 + P0.3 juntos → ejecutar tests → prueba de campo 2 riders.
2. Si aparece `GattVoice READY` y audio audible en ambos sentidos, activar P0.2 con flag y probar 3 riders.
3. P0.4 solo tras revisión de seguridad separada.

**DoD para cerrar P0:**

- Logs en ambos móviles: `GattVoice READY role=...` y `voiceFramesSent`/`voiceFramesReceived` creciendo durante PTT.
- `voicePacketsDroppedBackpressure = 0` en una ráfaga continua de 60 s.
- Ausencia de `VoicePacketTx descartado` y de `VoicePacketTx abortado: no hay relayEngine activo`.
- Audio PTT entendible en ambos extremos, con `selfShortId` correcto (sin loopback).
- `.\gradlew.bat :app:testStandardDebugUnitTest`: verde, con los nuevos tests incluidos.
- `verify_release_security.ps1` con `RBS_PROD_URL=https://ruta-biker-sync.onrender.com`: `PASS`.
- Sin cambios en `HeartbeatFrame`, GATT crypto, claves de manifiesto ni liderazgo.

## 5. Verificación propuesta (sin ejecutar hoy)

```powershell
# Unitarias
cd android
.\gradlew.bat :app:testStandardDebugUnitTest --no-daemon

# Gate
$env:RBS_PROD_URL='https://ruta-biker-sync.onrender.com'
.\scripts\verify_release_security.ps1

# Campo (dispositivos físicos)
adb logcat | Select-String 'GattVoice READY|VoicePacketTx|VoicePacketRx|voiceFrames'
```

## 6. Estado de este documento

Plan preparado a partir de lectura directa de `RideService.kt`, `BleAdvTransport.kt`, `MeshRelayEngine.kt`, `GattVoiceTransport.kt`, `LiveMapScreen.kt`, `PttAudioEngine.kt` y `BleMeshService.kt`. Tras la autorización explícita del usuario se implementaron las cuatro fases y las correcciones #1–#4; el flag `rbs.gatt.multipeer` sigue en `false`. La prueba #5 validó 2 riders con voz bidireccional y audio audible confirmado. La revisión del diff añadió dos ajustes de robustez autorizados (holder idempotente y `purgeExpired()`), verificados con 735 tests y gate `PASS`; el campo no se repitió con el AAB final. No se activó el flag: el usuario confirmó que solo hay 2 dispositivos, por lo que la prueba de 3 riders queda bloqueada antes del cierre P0.
