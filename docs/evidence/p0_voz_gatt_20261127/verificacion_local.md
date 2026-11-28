# P0 voz/GATT — verificación local (2026-11-27)

Estado: **código implementado y verificado en local; NO confirmado, NO publicado, NO validado en campo**.
HEAD permanece en `b0a949c`. No se hizo `amend` ni `push`.

## Alcance implementado

| Fase | Cambio | Archivos principales |
|---|---|---|
| P0.1 | La MAC del scan se propaga siempre; el `shortId` solo se acepta tras validar manifiesto/firma. El engine notifica el sufijo autenticado y `RideSessionController` lo usa para abrir el enlace GATT diferido. | `BleAdvTransport.kt`, `MeshRelayEngine.kt`, `RideService.kt` |
| P0.2 | Transporte GATT por enlace con roles deterministas por `shortId`, fan-out y flag de rollback `rbs.gatt.multipeer` (default `false`). | `GattVoiceTransport.kt`, `GattVoiceLinkRegistry.kt`, `GattVoiceFramer.kt` |
| P0.3 | `LiveMapScreen` consume `RideService.activeRelayEngineFlow` y re-suscribe PTT al cambiar de engine/rodada. | `RideService.kt`, `LiveMapScreen.kt` |
| P0.4 | Clave PTT derivada por HMAC-SHA256 con dominio `rbs-ptt-v1` desde la clave real del manifiesto; motor PTT único en `PttAudioEngineHolder`; fail-closed sin clave. No se tocó el formato de `VoicePacket`, `HeartbeatFrame`, el manifiesto firmado ni el liderazgo. | `VoiceKeyDerivation.kt`, `PttAudioEngineHolder.kt`, `PttAudioEngine.kt`, `LiveMapScreen.kt`, `BleMeshService.kt` |

## Resultados reproducidos

```text
cd android; $env:RBS_PROD_URL='https://ruta-biker-sync.onrender.com'
.\gradlew.bat :app:testStandardDebugUnitTest :app:bundleStandardRelease --no-daemon
BUILD SUCCESSFUL in 3m 51s   EXIT=0

android/app/build/test-results/testStandardDebugUnitTest/TEST-*.xml
SUITES=134 TESTS=728 FAILURES=0 ERRORS=0

.\scripts\verify_release_security.ps1   (RBS_PROD_URL=https://ruta-biker-sync.onrender.com)
OK: sin cleartext y networkSecurityConfig presente en manifest(s) fusionado(s) standardRelease
OK: BleMeshService presente y exported=false
OK: EmergencyLockScreenActivity presente y exported=false
OK: PASS_REAL: BASE_URL release https sin placeholders
OK: sin credenciales seed en src/main y outputs/apk
verify_release_security: PASS   GATE_EXIT=0

cd backend; npm run typecheck                         EXIT=0
cd backend; npx vitest run test/public_legal_resources.test.ts
Test Files 1 passed (1) / Tests 5 passed (5)           EXIT=0
```

El AAB firmado se regeneró en
`android/app/build/outputs/bundle/standardRelease/app-standard-release.aab` con los cambios P0;
es un artefacto de `build/`, no se subió a Play Console.

`npm run test` completo del backend **no pasó en este entorno**: 20 archivos fallan por
`ECONNREFUSED 127.0.0.1:5433` (no hay PostgreSQL local de prueba). No es una regresión de P0,
que no modifica backend; queda registrado para no afirmar que la suite completa está verde.
Nota de proceso: esa ejecución exploratoria se lanzó junto con la de Android, contraviniendo
`docs/AGENTS.md` ("nunca en paralelo con PostgreSQL"); no se repitió y el error fue de conexión
a la base, sin relación con la compilación Android.

## Tests nuevos de P0 (14)

- `GattVoiceLinkRegistryTest`: allowlist, roles por `shortId`, rechazo del segundo peer con flag `false`, purga al cambiar manifiesto, fan-out con flag `true`.
- `AuthenticatedPeerAddressTest`: MAC propagada con `shortId=null` en sobres cifrados; notificación de sufijo solo tras HMAC válido y baja del listener.
- `P0VoiceKeyAndOwnerTest`: derivación estable y separada por dominio, fail-closed sin clave, sustitución de suscripción RX del holder sin duplicar tramas.
- `RideScreenTransitionResilienceTest`: engine nuevo al sustituir controller y cancelación/re-suscripción de voz.
- `GattVoiceFramerTest`: identificación del emisor desde la cabecera del framing y rechazo de frames inválidos.

## Prueba de campo #1 — FALLIDA (2026-11-28 00:01)

Instalado `standardDebug` con `RBS_DEBUG_URL=https://ruta-biker-sync.onrender.com` en TECNO
(`098813734B105395`) y Redmi (`b2c398bb`), sin desinstalar la app del propietario. Flags:
`rbs.gatt.multipeer=false`. Logs: `ptt_field_TECNO.log` y `ptt_field_Redmi.log`.

| Observación | TECNO | Redmi |
|---|---:|---:|
| `GattVoice READY` | 0 | 0 |
| `GattVoice role=` (allowlist 1 peer) | 5 | 15 |
| `GattVoice CLIENT disconnected status=133` | 12 | n/a |
| `GattVoice session START ready=false` | 2 | 1 |
| `VoicePacketTx descartado por backpressure` | 46 | 9 |
| `VoicePacketTx descartado: no hay relayEngine activo` (mensaje engañoso) | 899 | 173 |
| `VoicePacketRx` | 3 | 8 |
| `BAD_DECRYPT` al descifrar voz | n/a | varios |

Causas identificadas:
1. **Rodadas distintas:** TECNO `rideId=ride-live-01`, Redmi `rideId=48be967a-...`. Cada equipo derivó una clave PTT distinta; eso explica `BAD_DECRYPT`.
2. **GATT nunca listo:** clientes con `status=133`; por tanto `MeshRelayEngine` usó la cola de advertising (`maxPending=3`) y descartó audio por backpressure.
3. El holder imprimía `no hay relayEngine activo` cuando el relay existía pero `emitVoicePacket` devolvía `false` por backpressure.

## Correcciones aplicadas para la prueba #2 (autorizadas por el usuario)

- `GattVoiceTransport.kt`: reintentos con `autoConnect=true` tras el primer intento directo (mitigación habitual de `status=133` con RPA), log de `serviceAdded`, dirección/shortId del scan, estado de conexión, servicios, MTU y CCCD, y reset del contador de intentos al marcar READY.
- `PttAudioEngineHolder.kt`: usa el relay vigente en cada envío y distingue “sin relay” de “rechazado por backpressure/transporte”.
- `LiveMapScreen.kt`: `startCabinaPtt()` devuelve `Boolean` y no abre la sesión PTT hasta `RideService.isPttVoiceReady()`, evitando `session START ready=false`.

Verificación local tras las correcciones: `134 suites / 728 tests`, `0 failures / 0 errors`;
`:app:assembleStandardDebug` exitoso con `BASE_URL=https://ruta-biker-sync.onrender.com`;
`verify_release_security: PASS`, `GATE_EXIT=0`. Reinstalado con `adb install -r` en ambos equipos.

## Prueba de campo #2 — FALLIDA (2026-11-28 00:14)

Logs: `ptt_field2_TECNO.log` y `ptt_field2_Redmi.log`. Ambos móviles usaron el mismo
`rideId=48be967a-0250-4504-86bb-5469e88aa65d` y el motor PTT se instaló con
`club=0x52425301` en los dos. Flag `rbs.gatt.multipeer=false`. Ya no aparecieron
`BAD_DECRYPT`, pero tampoco hubo voz.

| Observación | TECNO (CLIENT) | Redmi (SERVER) |
|---|---:|---:|
| `GattVoice READY` | 0 | 0 |
| `GattVoice role=` | 3 | 19 |
| `GattVoice CLIENT scanning` / `scanResult` / `connectGatt` | 4 / 4 / 4 | n/a |
| `GattVoice CLIENT connectionState` (ambos `status=133 state=0`) | 2 | n/a |
| `GattVoice CLIENT connected` / `servicesDiscovered` / `descriptorWrite` | 0 / 0 / 0 | n/a |
| `GattVoice SERVER advertising started` | n/a | 2 |
| `GattVoice SERVER connectionState` / `connected` / `disconnected` | n/a | 10 / 1 / 9 |
| `conexión sin enlace mapeado` | n/a | 1 |
| `GattVoice session START` | 0 | 0 |
| `VoicePacketTx descartado` / `VoicePacketRx` | 0 / 0 | 0 / 0 |

Causas probables:
1. **El cliente se queda en una dirección RPA vieja.** El primer intento directo devuelve
   `status=133`; el reintento con `autoConnect=true` queda pendiente sobre la misma MAC
   (`62:90:3B:68:1D:FE`) sin volver a escanear ni emitir `connectionState` (de 00:12:06 a
   00:13:39 no hay actividad GATT de cliente). Sin `servicesDiscovered`/MTU/CCCD no hay READY.
2. **El servidor no marca READY cuando la MAC de conexión no está mapeada.** Redmi recibió una
   conexión de `66:2F:F5:F2:65:90` que no estaba en `serverLinksByAddress`; el fallback “se
   resolverá por frame” no llega a ejecutarse porque el cliente nunca escribe el primer frame.
3. **El gateo de PTT es silencioso.** No hay `session START`, `PTT: Iniciando` ni métricas de voz;
   no se puede distinguir entre “no se pulsó PTT” y “PTT bloqueado por enlace no listo”.

## Correcciones aplicadas para la prueba #3 (autorizadas por el usuario)

- `GattVoiceTransport.kt`: se eliminó `autoConnect=true`. Todo intento es directo sobre una MAC
  recién escaneada; al fallar se cierra el GATT, se limpia su dirección y se reanuda el scan con
  backoff acotado (3–12 s). Watchdog de 8 s si no llega `connectionState`.
- `GattVoiceTransport.kt` (servidor): las conexiones con MAC no mapeada se guardan como
  pendientes; el primer frame con `shortId` autorizado resuelve el enlace y marca READY. También
  marca READY al aceptar la suscripción CCCD de un enlace ya mapeado. Si no llega frame en 8 s,
  se cancela la conexión pendiente.
- `GattVoiceTransport.kt` (cliente): fallos de `discoverServices`, characteristic y CCCD cierran
  y reintentan; un `requestMtu` fallido ya no bloquea la escritura del CCCD.
- `LiveMapScreen.kt`: log `PTT bloqueado: isPttVoiceReady=false` cuando el gate impide hablar.

Verificación local: `134 suites / 728 tests`, `0 failures / 0 errors`;
`:app:assembleStandardDebug` y `:app:bundleStandardRelease` exitosos; `BASE_URL` debug apunta a
Render; `verify_release_security: PASS`, `GATE_EXIT=0`. Reinstalado con `adb install -r`.

## Prueba de campo #3 — FALLIDA (2026-11-28 00:29)

Logs: `ptt_field3_TECNO.log` y `ptt_field3_Redmi.log`. Mismo `rideId`, mismo
`club=0x52425301`, flag `false`. El usuario pulsó PTT repetidamente y solo vio
“PTT en espera: enlace GATT aún no está listo”.

| Observación | TECNO (cliente, node=26631) | Redmi (servidor, node=14733) |
|---|---:|---:|
| `GattVoice allowedPeers` | `[14733]` | `[26631]` |
| `GattVoice role=` | **0** | 4 (`SERVER`) |
| `GattVoice CLIENT scanning` / `connectGatt` | **0 / 0** | n/a |
| `GattVoice READY` | **0** | **0** |
| `GattVoice SERVER advertising started` | n/a | 1 |
| `conexión sin enlace mapeado` | n/a | 1 |
| `frame de resolución timeout` | n/a | 1 |
| `PTT bloqueado: isPttVoiceReady=false` | 5 | 4 |
| `FAIL_CLOSED_PTT_KEY_UNAVAILABLE` | 1 (pre-manifiesto) | 2 (pre-manifiesto) |

Causa raíz: **el cliente nunca creó el enlace**. `onMeshPeerAddress()` depende de que
`MeshRelayEngine` autentique un heartbeat completo; en TECNO el heartbeat de Redmi
llegó incompleto (`FragmentRxRaw session=1 idx=0/1 total=4`, sin `FragmentReassembled`),
así que no hubo identidad autenticada, no se llamó `onMeshPeerAddress()`, no se creó el
`VoiceLink` cliente y por tanto nunca arrancó el scan GATT. TECNO sí recibía el anuncio
del servidor (`AdvRx bytes=2 head=57,-115` = shortId 14733 big-endian) pero no tenía
enlace con el que emparejarlo. En Redmi el enlace servidor sí se creó desde su heartbeat,
pero la única conexión entrante llegó con MAC no mapeada y expiró a los 8 s sin frame.

El `status=133` de la prueba #2 quedó resuelto; el bloqueo ahora está **aguas arriba**,
en la creación del enlace, no en la conexión GATT.

## Corrección #3 (autorizada) — enlaces desde el manifiesto

Autorización explícita del usuario: “Aplicar desde manifiesto (Recommended)”.

- `GattVoiceTransport.setAllowedPeerShortIds()` crea ahora los `VoiceLink` con los shortId
  del manifiesto firmado (ya autenticados), en vez de esperar a `onMeshPeerAddress()`.
- El nodo con rol `CLIENT` arranca el scan GATT de inmediato y empareja el servidor por el
  shortId big-endian anunciado; el nodo con rol `SERVER` arranca el advertising de inmediato.
- Se registran los peers en `GattVoiceLinkRegistry` para que `sendTargets()` no descarte
  los enlaces. Con `multipeerEnabled=false` se mantiene un solo enlace (rollback P0.2).
- No cambia `HeartbeatFrame`, el formato de `VoicePacket`, el manifiesto firmado ni el liderazgo.

Verificación local: `134 suites / 728 tests`, `0 failures / 0 errors`;
`:app:assembleStandardDebug` y `:app:bundleStandardRelease` exitosos; `BASE_URL` debug apunta a
Render y `PILOT_RIDE_ID=""`; `verify_release_security: PASS`, `GATE_EXIT=0`. Reinstalado con
`adb install -r` en TECNO y Redmi, sin desinstalar la app del propietario.

## Prueba de campo #4 - PARCIAL (2026-11-28 00:44)

Logs: `ptt_field4_TECNO.log` y `ptt_field4_Redmi.log`. Mismo `rideId`, flag `false`.
`GattVoice enlace desde manifiesto role=CLIENT/SERVER` apareció en ambos, y el cliente
TECNO llegó a `READY role=CLIENT mtu=517` con CCCD `status=0`, sin desconexiones.
La dirección TECNO→Redmi funcionó: 256 `VoicePacketTx` y 256 `VoicePacketRx`, sin
`BAD_DECRYPT`, sin backpressure y sin descartes.

El sentido Redmi→TECNO falló por completo: 3 `session START ready=true` en el servidor,
900 `notifyCharacteristicChanged devolvió false` y 0 paquetes recibidos en TECNO.
El servidor solo se marcó `READY role=SERVER mtu=23` al llegar el primer frame, 106 s
después del `connect`. El watchdog de 8 s había cancelado la conexión entrante a los
8 s (`frame de resolución timeout`), y los eventos de MTU/CCCD de conexiones aún no
mapeadas se descartaban; por eso el servidor notificaba sobre una conexión que ya no
consideraba activa y con MTU 23. Hubo un `PTT bloqueado` temprano en Redmi, antes de
que el primer frame resolviera el enlace; es el gate funcionando, no un fallo de audio.

## Corrección #4 (autorizada) - conservar conexión pendiente y su MTU/CCCD

Autorización explícita del usuario: "Aplicar corrección #4 (Recommended)".

- `GattVoiceTransport.kt`: se eliminó el watchdog que cancelaba conexiones entrantes
  sin enlace mapeado a los 8 s. La conexión se conserva hasta que el primer frame con
  `shortId` autorizado resuelve el enlace, hasta la desconexión real o hasta `stop()`.
- `GattVoicePendingConnection.kt` (nuevo, sin tipos Android): captura por MAC el MTU
  negociado y el CCCD habilitado aunque la conexión aún no esté mapeada. Al resolver
  por frame (o al mapear por heartbeat) se aplica ese MTU al enlace y se registra el
  estado de la suscripción en el log.
- No cambia `HeartbeatFrame`, el formato de `VoicePacket`, el manifiesto firmado ni
  el liderazgo. `multipeer=false` sigue limitado a un enlace.

Verificación local de la corrección #4: `135 suites / 731 tests`, `0 failures / 0 errors`;
`:app:assembleStandardDebug` y `:app:bundleStandardRelease` exitosos; `BASE_URL` debug apunta a
Render y `PILOT_RIDE_ID=""`; `verify_release_security: PASS`, `GATE_EXIT=0`. Reinstalado con
`adb install -r` en TECNO y Redmi, sin desinstalar la app del propietario.

## Prueba de campo #5 — bidireccional técnicamente PASS (2026-11-28 00:59)

Logs: `ptt_field5_TECNO.log` y `ptt_field5_Redmi.log`. Mismo
`rideId=48be967a-0250-4504-86bb-5469e88aa65d` y `rbs.gatt.multipeer=false`.

- TECNO: `GattVoice READY role=CLIENT mtu=517 peer=14733` a las 00:59:47.091, con
  `allowedPeers=[14733] multipeer=false` y `enlace desde manifiesto role=CLIENT peer=14733`.
- Redmi: capturó `mtu pendiente ... mtu=517` y `CCCD pendiente` a las 00:59:48, y después
  `enlace resuelto por frame peer=26631 address=52:73:EF:3A:34:B9 mtu=517 cccd=true` y
  `GattVoice READY role=SERVER mtu=517 peer=26631` a las 01:00:05.725. No apareció
  `frame de resolución timeout`, `conexión sin enlace mapeado` ni `notifyCharacteristicChanged devolvió false`.
- TECNO→Redmi: 257 `VoicePacketTx` (frames 0..256, `last=true`) y 257 `VoicePacketRx`
  (frames 0..256, `last=true`).
- Redmi→TECNO: 412 `VoicePacketTx` (frames 0..411, `last=true`) y 412 `VoicePacketRx`
  (frames 0..411, `last=true`), seguidos de `Fin de recepción de audio PTT.` en TECNO.
- Sin `BAD_DECRYPT`, `Error descifrando`, `descartado`, `abortado`, backpressure,
  desconexiones, `status=133` ni `PTT bloqueado`. Ambos `session START ready=true`.
- `FAIL_CLOSED_PTT_KEY_UNAVAILABLE` aparece 2 veces por equipo durante el arranque, antes de
  instalar la clave del manifiesto; no bloqueó ninguna de las dos sesiones.
- Único aviso: `GattVoice TX fallo peer=26631: Timed out waiting for 1000 ms` a las
  01:00:28.799, después del `session END` de Redmi. Como los 412 frames, incluido `last=true`,
  llegaron a TECNO y se registró el fin de recepción, encaja con un timeout del callback de
  confirmación de la última notificación, no con pérdida de audio; se documenta sin cambiar código.

El usuario confirmó audio audible en TECNO sin loopback. Queda así validado el transporte de
voz bidireccional con 2 riders y `rbs.gatt.multipeer=false`.

## Ajustes posteriores a la revisión del diff (autorizados)

Tras la revisión del bloque P0 se corrigieron dos riesgos detectados antes del commit:

- `PttAudioEngineHolder.kt`: `install()` es idempotente cuando no cambian club, shortId, relay
  y clave, por lo que una cabina o el servicio ya no liberan un motor que pueda estar
  transmitiendo. Expone `engineFlow`; `BleMeshService` y `LiveMapScreen` leen el motor vigente
  en lugar de guardar referencias obsoletas. Los callbacks de envío se registran como listeners
  y el holder zeroiza la clave al liberar.
- `GattVoiceTransport.kt`: se restauró `link.inbound.purgeExpired()` antes de alimentar el
  reensamblador en ambas direcciones. En `HEAD` se purgaba; el refactor a un reensamblador por
  enlace lo había perdido, permitiendo sesiones parciales sin TTL durante toda la rodada.

Estos ajustes son de robustez/ciclo de vida: no cambian `HeartbeatFrame`, el formato de
`VoicePacket`, el manifiesto, el liderazgo ni el flag `multipeer=false`. La evidencia de campo
#5 corresponde al binario anterior a estos dos ajustes; no se ha repetido el campo con el AAB
final.

Verificación local de los ajustes: `135 suites / 735 tests`, `0 failures / 0 errors`;
`:app:assembleStandardDebug` y `:app:bundleStandardRelease` exitosos; `BASE_URL` debug apunta a
Render y `PILOT_RIDE_ID=""`; `verify_release_security: PASS`, `GATE_EXIT=0`.

## Pendiente obligatorio antes de cualquier commit de cierre

1. ~~Validar 2 riders con `rbs.gatt.multipeer=false`.~~ Completado en la prueba #5:
   `READY` en ambos, 257/257 y 412/412 frames con `last=true`, sin backpressure, descartes,
   abortos ni BAD_DECRYPT, y audio audible confirmado en TECNO.
2. Repetir con 3 riders y `SharedPreferences["rbs_flags"]["rbs.gatt.multipeer"]=true`.
   **Bloqueado por falta de un tercer dispositivo** (confirmado por el usuario: solo 2
   dispositivos disponibles). No activar el flag ni afirmar el cierre P0 sin esa prueba.
3. Mantener el flag en `false` hasta completar el paso 2; cualquier error de backpressure,
   engine ausente, audio no audible o enlace duplicado durante la prueba de 3 riders exige
   rollback y documentación.

No se desinstaló la app del propietario en TECNO/Redmi. Los APK instalados son `standardDebug`
apuntando a Render, por lo que sí pueden bajar el manifiesto real; siguen siendo debug, no el AAB de Play.
