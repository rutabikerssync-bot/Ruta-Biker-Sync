# Plan de trabajo para continuidad — Audio SOS/señales y Mapa (MapLibre/Mapbox)

> Documento operativo para el siguiente agente, incluido un agente gratuito con menor contexto técnico.
>
> Fecha de elaboración: 2026-11-20.
>
> Propósito: completar primero los fixes en curso y después añadir audio real, señales audibles y mejoras de mapa sin romper malla BLE, PTT, GATT, privacidad, cifrado ni estabilidad probada.
>
> **No asumir que todo el trabajo descrito aquí ya está terminado.** Cada cierre exige pruebas y evidencia real. No escribir resultados que no se hayan ejecutado.

---

## 1. Cómo usar este documento sin cometer errores

1. Leer primero **Estado real**, **Reglas inviolables** y **Bloque 0: cerrar pendientes**.
2. No empezar audio ni mejoras MapLibre antes de tener el Bloque 0 con pruebas verdes.
3. Hacer cambios pequeños por hito, con sus tests junto al cambio. No empaquetar audio, mapa y más arreglos en un único commit gigante.
4. Separar cuatro conceptos:
   - **UI/roster online del club:** participantes visibles y RSVP.
   - **Malla BLE de rodada:** peers reales descubiertos por heartbeat.
   - **Posición geográfica:** GPS actual, nunca coordenada de fallback.
   - **Servidor:** PostgreSQL + Fastify es fuente canónica si el servidor es alcanzable.
5. No confundir **peer BLE**, **participante de roster**, **marker del mapa** y **UUID de usuario backend**. No existe actualmente una asociación completa `userId backend ↔ shortId BLE`.
6. No presentar una partida de pruebas, una simulación o una captura con datos genéricos como validación hardware de un peer real identificado.
7. Si algo falla, parar, guardar la salida real y seguir el siguiente hito solo cuando esté diagnosticado. **No ocultar fallos con tests tautológicos ni banners verdes de éxito.**

---

## 2. Estado real conocido al elaborar este plan

### 2.1 Commits ya existentes

- `f6e3b52` — modelo puro del ciclo RSVP/presencia/fases.
- `16e7898` — persistencia backend `onSiteAt`, fases y ventana de 1 h.
- `b01b481` — participantes del club en Rodadas, roster real + inicio del líder.
- `4ba368a` — restaura el botón clásico de cabina para miembros no líderes y evita que ellos invoquen `/v1/routes/:id/start`.

Contexto adicional: la regresión de Mi Club/cabina del miembro ya fue analizada y corregida. El líder arranca desde el roster; el miembro no líder recupera su botón clásico de cabina. No restaurar el bypass del líder.

### 2.2 Estado de los 3 fixes de usabilidad (Bloque 0)

Los 3 bugs de usabilidad de ruta ya tienen código y suites JVM validadas:

- `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapViewModel.kt`
  - Filtro `isRenderablePeerPosition` operativo: omite (0,0), NaN y fallback CDMX (`19.4326,-99.1332`) en peers remotos.
  - Self y peers con GPS real confirmados en sus coordenadas reales.
- `android/app/src/main/java/com/rutabikersync/app/feature/routes/RouteDeviationDetector.kt`
  - Histéresis completada: `DEVIATION_THRESHOLD_METERS = 150` y sostenida `>=10 s` (`DEVIATION_SUSTAIN_DURATION_MS = 10_000L`) antes de `isAlertActive=true`.
  - Proyección ortogonal con `t = ((apx * abx + apy * aby) / abLenSq).coerceIn(0.0, 1.0)` y distancia geodésica Haversine al punto proyectado.
  - Reinicio de ventana al reanudar, cambiar polilínea o parar.
- `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapScreen.kt`
  - `LaunchedEffect(deviationState.isAlertActive)` dispara `RecalculateRouteUseCase` solo una vez por transición sostenida, sin toque en pantalla y con feedback offline honesto.
  - `BackHandler` adaptado para cerrar overlays (`showMeshMonitor`, `showTacticalChat`, `showOfflineMapsScreen`) ordenadamente sin interrumpir la rodada ni el servicio BLE.
- Pruebas unitarias verdes:
  - `feature/livemap/PeerPositionValidityTest.kt` (2/2 PASSED).
  - `feature/routes/RouteDeviationHysteresisTest.kt` (3/3 PASSED).
  - `feature/livemap/RideScreenTransitionResilienceTest.kt` (2/2 PASSED).
  - Suite de usabilidad + regresión completa: 37/37 PASSED (`PeerPositionValidityTest`, `RouteDeviationHysteresisTest`, `RecalculateRouteUseCaseTest`, `LiveMapViewModelTest`, `RideServicePhase3Test`, `RideCabinaAccessTest`, `RideScreenTransitionResilienceTest`).
  - Suite global completa (incorporando `RouteEnginePhase4Test` con loader GPX inyectado): 42/42 PASSED, 0 fallos, 0 errores.

### 2.3 Estado de pruebas y defectos pendientes

- **Histéresis (Cerrada en JVM)**: El fallo previo (`computed=0`) fue diagnosticado y resuelto: la fixture de prueba definía la ruta en el eje latitudinal y las desviaciones sobre la misma línea con longitud 0 (dentro del segmento). Corregido a desviación ortogonal en longitud (~55 m y ~334 m) con aislamiento de `RideScheduleRepository` en `setUp()`.
- **GpxManager (Resuelto en JVM con customAssetLoader, listo para Commit B separado)**: Se resolvió la carga en pruebas unitarias inyectando `customAssetLoader` en `DefaultRouteCatalog` para leer el archivo real `tovar_routes.json` sin depender de `App.instance.assets` nulo en JVM. La aserción valida `assertEquals(testRoute.name, importedRoute.name)` manteniendo íntegro el roundtrip con `<trkpt>`, elevación y distancias reales. Queda aislado estrictamente para el **Commit B**, sin mezclarlo en el Commit A de usabilidad de ruta.
- **Validación Física en Hardware (TECNO comprobado, pendiente bilateralidad Redmi)**:
  * TECNO (`098813734B105395`) validado en vivo con APK debug instalada: cabina activa sin centinela CDMX, HUD con `Convoy 2 riders`, transición a Monitor Malla (`MALLA ACTIVA`, peer en línea -51 dBm, PLR 0.0%) y retorno a LiveMap sin caída del FGS (`MeshRelayEngine.isRunning == true`).
  * Redmi (`b2c398bb` / `100.98.78.105`) bloqueado por daemon ADB (error 10061 conexión denegada por equipo destino, offline por USB). Documentado honestamente en `docs/evidence/fase5_redmi_adbd_block_20261120.txt`.
  * **No se considera cierre bilateral de calle completo** hasta recuperar enlace con Redmi y validar ambos dispositivos simultáneamente con GPS real y recálculo automático en desvío sostenido.

### 2.4 Trabajo previo no comprometido que NO debe colarse en los nuevos commits

El working tree puede contener correcciones previas independientes. No mezclarlas sin un commit explícito dedicado. Entre ellas estaban:

- `android/.../feature/groups/ClubRepository.kt` y `Phase1ViewModel.kt`
  - Reconciliación `grp-*` → UUID real y trato de error 404 en invitaciones.
- `backend/src/app.ts`
  - Guardias UUID previas (`INVALID_GROUP_ID`) que no son parte del trabajo de audio/mapa.
- `backend/src/db/pool.ts` y `backend/test/migrations_registry.test.ts`
  - Cambios anteriores de conexión/pool/registro.
- `backend/src/db/migrations/0019_open_qr_invitations.sql`
  - Invitaciones QR abiertas, ya ajeno al trabajo nuevo.
- Migración del ciclo actual: `0020_ride_lifecycle.sql`. **No crearla de nuevo ni reutilizar 0018/0019.**
- Evidencia antigua `docs/evidence/*.xml` / imágenes `fase5_motto*`. Esos archivos no son necesarios para cerrar las tareas de audio/mapa.

**Regla:** nada de `git add .` ni `git commit -a`. Añadir solo archivos del hito, revisar `git diff --cached --name-status` y dejar fuera cualquier cambio anterior no relacionado.

### 2.5 Bloqueos externos ya documentados

- Redmi ha presentado ADB `offline` / `not found` y rechazo `10061` en `100.98.78.105:5555` y por LAN.
- Tailscale puede seguir respondiendo mientras `adbd` está caído. Eso no prueba que la app o BLE funcione.
- Existe evidencia parcial TECNO de HUD “2 riders”, pero no una validación bilateral total de nombres/roles.
- No afirmar visibilidad bilateral completa, audio reproduciéndose o notificación 1→2 riders sin haberla observado.

---

## 3. Invariantes: no romper bajo ninguna circunstancia

### 3.1 Malla BLE y cifrado

- **No cambiar GATT**, UUIDs, estructura de `HeartbeatFrame`, formato binario `SosPacket`, firma HMAC, rideSecretKey, Ed25519, ChaCha20-Poly1305 ni Opus.
- No insertar audio dentro del stream GATT/hueco PTT. TTS/tonos son salida local del teléfono.
- No convertir el modo simulación en “malla real” para capturas. No forzar “peers reales” inyectando fake peers/paquetes.
- No usar coordenadas demo para simular cercanía de dispositivos.
- Preservar PTT y su foco de audio, deduplicación, relay y TTL.

### 3.2 Permisos y fase

- Mantener liderazgo real del grupo: `principalLeaderId` o `leaderIds` **del grupo actual**. No permisos globales por `accountType`.
- Solo el líder invoca `/v1/routes/:id/start`.
- `GET /v1/routes/:id/roster` informa roster; **no es sustituto de descubrimiento BLE**.
- La regla de inicio sigue siendo: al menos un confirmado y todos los confirmados con `onSiteAt != null`.
- No se llama al iniciar ruta desde Mi Club para crear un espacio paralelo; la rodada se origina en el flujo Rodadas → mapa existente.

### 3.3 Datos y evidencia

- No inventar nombres, avatares, roles, UUIDs, membresías, timestamps, geolocalización o métricas.
- No afirmar que el fallo “quedó resuelto” si hay tests rojos o captura pendiente.
- No usar screenshots antiguas como prueba nueva. Documentar fecha/dispositivo/estado de cada captura.
- No modificar RLS para ocultar un error de permisos. Los accesos RLS deben conservar miembros aceptados o líderes del mismo club.
- No truncar `rbs` en tests de desarrollo. Si se toca DB de desarrollo manualmente, registrar antes/depúes y justificar.

### 3.4 Tokens, secretos y build

- No hardcodear token Mapbox en Kotlin ni en Git.
- `local.properties` ya define `mapbox.token`, y `BuildConfig.MAPBOX_ACCESS_TOKEN` se usa de forma configurable.
- Toda vista normal/offline debe seguir funcionando sin token si no se usa un feature exclusivo online.
- **No regenerar keystore.** La release v2 usa `C:\rbs-secrets\rbs-release-v2.jks`, pero sus credenciales pendientes no deben inventarse ni colocarse en el repo.
- Para esta continuidad se usa build debug firmada e instalación in-place. No borrar datos de app con limpieza innecesaria que pueda eliminar membresía/roster.

### 3.5 Shell y Git

- Shell no interactivo: nada de editor, pager, `nano`, `vim`, `less`, `git add -p`, `git rebase -i`.
- Usar Read/Write/Edit para archivos. No scripts ad hoc que arruinen UTF-8/CRLF/BOM.
- Para SQL en WSL usar here-doc, sin comillas frágiles de PowerShell.
- `git add -p` está prohibido; si hay que aislar hunks, usar patch selectivo no interactivo o añadir archivos nuevos del hito exclusivamente.
- Con autorización para commit: `git commit -m "..."`; sin amend y sin push. Revisar con `git --no-pager show --stat`.

---

## 4. Bloque 0 (obligatorio): cerrar las correcciones en curso

No empezar SOS audio / señales / atributos MapLibre hasta haber cerrado A, B, C y D.

### A. Trazado/polilínea y pruebas geométricas

1. Leer el `RouteDeviationDetector` actual completo, no reescribirlo a ciegas.
2. Confirmar `t = clamp((apx * abx + apy * aby) / abLenSq, 0, 1)` y luego Haversine del punto proyectado.
3. Reproducir distancia esperada para segmentos:
   - Dentro de ruta → 0.
   - Punto ~55 m → no alerta (<150 m).
   - Punto >200 m → alerta al pasar >=10 s sostenidos.
4. Si da 0 inesperadamente, imprimir `point`, cada pareja de segmento, `t` y `projected`. **No inventar un nuevo motor de rutas** antes de comprobarlo.
5. Revisar estado global: `RouteDeviationDetector` es `object` mutable. Toda prueba debe aislar `reset()` y `setActiveRoutePolyline(...)`. Comprobar también contaminación por otras fixtures y no borrar coordenadas reales del producto.

### B. Tests obligatorios del corte

Ejecutar secuencialmente, no en Gradle paralelo:

```powershell
cd D:\Ruta-Biker-Sync\android
.\gradlew.bat :app:compileStandardDebugKotlin
.\gradlew.bat :app:testStandardDebugUnitTest `
  --tests "*PeerPositionValidityTest" `
  --tests "*RouteDeviationHysteresisTest" `
  --tests "*RouteEnginePhase4Test" `
  --tests "*RecalculateRouteUseCaseTest" `
  --tests "*LiveMapViewModelTest" `
  --tests "*RideServicePhase3Test" `
  --tests "*RideCabinaAccessTest"
```

Resultados requeridos:

- Peer (0,0) no se dibuja remotamente.
- Fallback CDMX no se dibuja remotamente.
- Peer con GPS real sí se dibuja en lat/lon real.
- >200 m + tiempo <10 s ⇒ no alerta / no recálculo.
- >200 m sostenido >=10 s ⇒ exactamente un recálculo real en mocks de use case, con posición actual y destino original.
- 50 m ⇒ no recálculo, ni siquiera tras 15 s.
- Parada voluntaria no revive banner viejo; recálculo reinicia window.
- Offline ⇒ ruta cacheada conservada y texto offline, sin red simulada ni reintento infinito.
- Regresión de participantes y RideService no degradada.

### C. Posibles gotchas del entorno

- No ejecutar Gradle en paralelo con otro Gradle en el mismo proyecto: ya produjo conflictos incrementales.
- `--rerun-tasks` está permitido para forzar compilación limpia si quedó estado incremental dañado. No borrar indiscriminadamente el repositorio ni las bases de datos.
- No usar `--offline` pensando que eso probará network. Offline aquí es ejecución local, no simulación de red.
- Eliminar información diagnóstica temporal (`DEBUG_HYST`) al quedar estabilizados los tests, conservando verificaciones concretas.

### D. Aislar fallos no relacionados

Si `GpxManager` falla `Debe contener track points`, ejecutar esa prueba aislada antes de cambiarla. No modificarla simplemente para “pasar”. No usarla como excusa para abortar el cierre sin diagnóstico, pero tampoco mezclar funcionalidad GPX con la histéresis en un mismo commit.

---

## 5. Hito 1 — Audio real para SOS

### 5.0 Código actual aprovechar, no duplicar

- `feature/sos/SosCountdownManager.kt`
  - Cuenta regresiva de 20 s con vibración por segundo.
  - Confirmación tiene patrón Morse **visual/háptico** existente `· · · — — — · · ·` (líneas ~160–169).
  - Cancelación tiene vibración corta y vuelve a Idle.
- `core/announcer/TtsAnnouncer.kt`
  - TTS real con foco `AUDIO_FOCUS_MODE_DUCKING`, vibración y desempate global.
  - Debe seguir usándose para locución verbal; no sustituirlo por un reproductor paralelo sin coordinación.
- `core/audio/AudioDuckingManager.kt` y `core/mesh/audio/AudioFocusController.kt`
  - Ya hay abstracciones para ducking/foco. Revisar antes de agregar otra capa duplicada.
- `LiveMapScreen.kt`
  - SOS manual ~1538–1545, recibido por `bleMeshService.incomingSosMeshEvent` y diálogo ACK.
- `RideService.ACTION_CANCEL_SOS`, `ACTION_STOP_RIDE`, `ACTION_FORCE_STOP_RIDE`
  - Existen rutas reales de parada: conectarlas al apagado inmediato del audio, sin tocar la criptografía ni la emisión.

### 5.1 Arquitectura mínima propuesta

Crear, en el módulo apropiado (preferiblemente `core/audio` o `core/announcer`):

```kotlin
interface SosTonePlayer {
    fun start()
    fun stop()
}
```

El concreto Android usará `ToneGenerator` o MediaPlayer liviano opcional. **No añadir una dependencia externa solo para beeps.** `ToneGenerator` es suficiente para este hito; no hay que descargar archivos MP3 desde Internet.

Responsabilidades:

- Preguntar foco transitorio con may ducking, sin alterar la propiedad de PTT.
- Reproducir secuencia programada, nunca Thread/Hander sin control.
- `stop()` debe ser idempotente: libera tono/player, cancela Job y abandona foco.
- Silenciar señales rápidas durante SOS para no solapar más ruido.
- Restaurar prioridad para TTS/PTT al terminar.
- No meter comunicación BLE dentro del player; es solo salida local observable.

### 5.2 Sonido solicitado por evento

| Evento | Comportamiento auditivo |
| --- | --- |
| Cuenta regresiva (20 s) | Beep breve opcional por tick, tenue; últimos 5 s algo más marcado. No duplicar TTS del mismo evento. |
| SOS confirmado, local o remoto | Patrón Morse audible: 3 cortos + 3 largos + 3 cortos; repetición controlada mientras está activo. Prioridad SOS sobre señales. |
| Cancelado / falsa alarma | Un beep corto distinto; detener inmediatamente cualquier patrón activo. |
| ACK recibido/enviado | Detener patrón y aviso breve de confirmación, una única vez por ACK real. |
| Detener rodada / salir de cabina | Detener tono y foco en `onDispose` + acción real de detención. |
| Silencio de usuario / DND / volumen 0 | No forzar bypass; mantener feedback visual+háptico. Documentar el comportamiento. |

Valores de referencia, no requerimientos para hardcodear infinitamente:

- corto: 150–250 ms; largo: 450–600 ms; gpa entre elementos ~150 ms; pausa repetición ~1–2 s.
- Mantener tests de patrón puro, no depender de altavoz en JVM.

### 5.3 AudioFocus y PTT sin regresiones

- No mantener un `audioFocusRequest` abierto para siempre: desbloquear cuando termine ciclo o `stop`.
- Si PTT está hablando, darle prioridad y no provocar eco solapado ni capturas del micrófono. El tono no se captura para PTT.
- No combinar `QUEUE_ADD` TTS repetido + sirena continua de forma incoherente. Definir una sola política: TTS anuncia estado, sirena alerta. Si hay choque, `UniqueAnnouncer` sigue el desempate y los tonos respetan ese canal.
- Reutilizar la abstracción existente antes de inventar un tercer foco.

### 5.4 Tests requeridos y Resultados Obtenidos

- **SosTonePlayerTest (7/7 PASSED)**:
  - `testMorsePattern333SequenceEmittedCorrectly`: secuencia exacta 3 cortos (150ms), 3 largos (450ms), 3 cortos (150ms).
  - `testStartIsIdempotentAndDoesNotDuplicatePlayback`: llamadas concurrentes o redundantes a `start()` no duplican el loop.
  - `testStopIsIdempotentAndReleasesAllResources`: llamadas redundantes a `stop()` apagan el tono y liberan el foco sin excepciones.
  - `testStopMidCycleAbortsImmediately`: interrupción inmediata a mitad de pulso cancela el ciclo y desactiva el emisor.
  - `testPttActiveBlocksToneStart`: PTT en transmisión bloquea el arranque del tono SOS para no contaminar el micrófono.
  - `testFocusDeniedPreventsPlayback`: denegación de AudioFocus evita la reproducción.
  - `testSosToneHubCoordinatesLifecycleAndSinglePlayer`: coordinación centralizada entre UI y servicio.

- **Suites de Regresión de Audio, SOS y Malla (22/22 PASSED)**:
  - `SosTonePlayerTest`: 7/7 PASSED.
  - `UniqueAnnouncerPhase3Test`: 3/3 PASSED.
  - `VoiceClipMeshTest`: 3/3 PASSED.
  - `PttAudioEngineHandoffTest`: 2/2 PASSED.
  - `TacticalChatMeshIntegrationTest`: 2/2 PASSED.
  - `SosEmergencyTest`: 5/5 PASSED.

- **Suites de Rutas y Cabina (42/42 PASSED)**:
  - `PeerPositionValidityTest` (2/2), `RouteDeviationHysteresisTest` (3/3), `RecalculateRouteUseCaseTest` (4/4), `LiveMapViewModelTest` (7/7), `RideServicePhase3Test` (15/15), `RideCabinaAccessTest` (4/4), `RideScreenTransitionResilienceTest` (2/2), `RouteEnginePhase4Test` (5/5).
  - **Total global verificado: 64/64 tests PASSED, 0 fallos, 0 errores.**

### 5.5 Evidencia hardware de audio (TECNO 098813734B105395)

- APK compilada con `-Prbs.debug.url=https://omartinezpaz.tail6f67b0.ts.net` e instalada in-place vía ADB.
- Activación en vivo y resolución del ciclo SOS observada en pantalla con banner `✓ Emergencia SOS resuelta`.
- FGS `RideService` y transmisión de malla `HeartbeatTx` preservados sin interrupciones (`packetsEmitted=58`, `elapsedSec=170s`).
- Estado Redmi: continúa bloqueado por daemon ADB (10061); no se afirma validación bilateral hasta recuperar enlace.

---

## 6. Hito 2 — Señales rápidas audibles del mapa

### 6.1 Lo que existe hoy

En `LiveMapScreen.kt`, `showSignalsDialog` lista:

- Obstáculo en la vía.
- Reducir velocidad.
- Reagrupar convoy.
- Parada de gasolina.
- Retén / precaución.

Hoy el click lleva a `actionFeedback = "📢 Señal emitida..."` y cierra diálogo. No hay que afirmar que ya se propaga BLE si el código no acaba en una emisión real ni en recepción comprobada en otro equipo.

### 6.2 Propuesta lean

1. Extraer enum/tipo único de señal, p. ej. `ConvoySignalKind`, para que texto, ícono, tono y futura emisión usen el mismo ID.
2. Tono corto distinto por señal con el mismo reproductor/audiofocus del sistema, no un player nuevo por click.
3. No repetir sirena SOS por señales informativas. Si hay SOS activo, estas señales no interrumpen su canal.
4. Notificar por TTS solo si era ya comportamiento previsto, evitando duplicate voice + beep + visual que se pisen.

Sugerencia legible:

- Obstáculo: 1 tono medio.
- Reducir velocidad: 2 tonos cortos urgentes.
- Reagrupar convoy: 2 tonos neutrales.
- Gasolina: 1 tono grave calmado.
- Retén/precaución: 3 tonos cortos de atención.

No hardcodear textos diferentes en otro lado: usar el mismo mapa de la UI evita incongruencias de etiquetas y sonido.

### 6.3 Si se agrega propagación por malla

**Es trabajo separado, aunque esté en el mismo Hito solo después de audio local.** Antes de implementarla:

1. Revisar `MeshRelayEngine`, paquetes texto/señales existentes y su dedupe.
2. No blindamente añadir nuevo tipo BLE o UUID GATT.
3. Reutilizar un canal de control compatible si existe, con su criptografía y validación actuales.
4. Probar por lo menos emisor→receptor real TECNO/Redmi con respuesta observable y sin loop relay.
5. Mostrar al receptor mismo signalKind (semántica correcta) sin fabricar autores/metadata.

No afirmar propagación por network si solo se cambió el banner local.

### 6.4 Tests de señales

- Mapeo completo único (`signalKind` → textos + tono).
- Cada click causa una única notificación local y hora.
- No se reproducir sonido en loop por recomposición de Compose.
- `onDispose` corta tonos pendientes.
- Si hay propagación: receptor autentica el evento una vez, diferentes nodos conservan dedupe/hops y signalKind se interpreta igual.
- Prioridad: SOS interrumpe/antepone comunicaciones informativas sin romper PTT.

---

## 7. Hito 3 — Mejoras de mapa con MapLibre (Mapbox APIs reales, no dependencias nuevas ficticias)

### 7.1 Verificación técnica previa

El proyecto usa **`org.maplibre.gl:android-sdk:11.5.2`**, ya integrado, más PMTiles/route styles propios y `RouteStrokeStyle`. Noconfundir con una migración externa a Mapbox Directions/Google Maps.

`LiveMapScreen.kt`, `MapLibreCorridorSurface` observa problemas concretos:

- `uiSettings.isAttributionEnabled = false`.
- `isLogoEnabled = false`.
- `isCompassEnabled = false`.
- `isRotateGesturesEnabled = true`, zoom/scroll también true.
- Cámara por `CameraPosition.Builder().target(...).zoom(state.cameraZoom)`.
- Overlay Compose manual: `lonDelta * 120f`, `latDelta * 120f`, offsets de 32 px y normalizaciones de posición. **Ese modelo do manual no es una proyección geográfica exacta.** Puede desplazar visualmente, pero no debe confundirse con posición geográfica real.

No importar dependencias Maven nuevas “Mapbox” solo porque el usuario dice Mapbox API. Lo correcto es emplear las clases reales MapLibre presentes.

### 7.2 Mejoras requeridas por utilidad, no decorativas

#### A. Posición precisa mediante `Projection`

Preferir proyección del mapa en vez de la fórmula manual del overlay:

- En el `MapView` ya renderizado, obtener la posición de pantalla usando la API actual de MapLibre para convertir `LatLng` → PuntoF (usar la función `Projection` disponible en 11.5.2; verificar firma real antes de escribir).
- Usar dimensión real de vista/contenedor para mapear a Compose.
- No estimar separación medidora en grados artificialmente derivados (que hoy distorsiona distancia aparente y comportamiento de zoom).
- Solo si necesita desagrupar gente a ~0 m: agrupar visualmente con badge `"2 riders"` o mini-fanout de píxeles. **Nunca cambiar su lat/lon ni extrapolar trayectoria ficticia.**

Aceptación: dos riders en la misma coordenada no deben aparecer en dos municipios o a >2 km solo porque están a 1 m en la calle. Si están en la misma posición, el render/coalición debe indicarlo correctamente no moverlos geográficamente a propósito.

#### B. Viewport y enfoque

Usar datos reales:

- Bounds desde waypoints + local + peers con GPS válido. Construir `LatLngBounds` solo si hay puntos.
- Padding (ej. 48–72 dp) para bounds; no inventar un bounds de CDMX/Táchira.
- Si no hay puntos reales, mostrar mapa context con estado “sin fix GPS”, no centrar por defecto en otra ciudad.
- Respetar FOLLOW_LEADER (10 s ya implementado); no recentrar/zoom de vuelta cada tick y cancelar enfoque manual del rider.
- Evitar salto de zoom al recalcular ruta: hacer `moveCamera/easeCamera` con posición real, conservar modo.

#### C. Estilo/atribución

- `isAttributionEnabled`: actualmente **false**. Activar OSM attribution conforme a metadata/estilo usado, con opción de pinch/pequeño desplegable para no tapar HUD.
- `isLogoEnabled`: está false; decidir si se necesita logo de MapLibre según estilo/contrato pero no olvidar lo anterior ya previsto como licencia.
- No eliminar atribución ni obligar a descargar un estilo online estando offline.
- Colores 60/30/10: Dark Slate/base, tarjetas secundarias, cobre/ámbar solo CTA. Emerald/Rojo únicamente semántica SOS/conectividad. No cambiar el mapa físico solo para adaptarlo a cobre sin contraste.

#### D. Controles/gestos

- Revisar `isRotateGesturesEnabled`: hoy true, no cambiarlo sin criterio de usabilidad. Mantener rotación de brújula si es útil para el rider.
- Zoom/scroll ya están activos, no deshabilitarlos sin requerimiento.
- Añadir opción de habilitar brújula/encapsulado user control cuando no interfiera HUD, no en medio de acciones de guantes.
- No deshabilitar `MapView.onStart/onResume/onPause/onStop/onDestroy`: es lifecycle del mapa, no del servicio BLE.

#### E. Capas de ruta

Seguir usando `GeoJsonSource`, `FeatureCollection`, `LineString` de MapLibre con los IDs de `RouteStrokeStyle`. Hacer zoom/line-width adecuados al zoom del mapa sin calcular un corredor ficticio. No meter GPX/descargar ruta nueva al mejorar vistas.

#### F. Features online solo como opt-in

Si se requiere algo Mapbox exclusivo online (geocodificación vector tiles online, Directions SDK, etc.):

- Documentar una `@OptIn` configuración segura del tipo `useOnlineMapboxFeatures = token válido && red`.
- Fallback siempre a PMTiles/route cache si offline o token ausente.
- Revisar BRouter offline: no cambiar ese motor sin un plan separado de tests de coherencia vial.
- Mantener backend/rutas y dev-simulación fuera del release secreto.

### 7.3 Pruebas de mapa

- Validación puntos: sea cual sea zoom, misma coordenada queda dentro mismo tile; no hay corrimiento rotacional arbitrario lat/lon dedicado “para evitar solapado” que la separe a kilómetros.
- Bounds vacíos → no acceso a variable no inicializada ni centro fake.
- Bounds de rutas/peers válidos → cámara incluye todos con padding y no re-centrar cada tick.
- `FOLLOW_LEADER` sigue caducando 10 s como prueba ya presente, ignorando zoom manual entretanto.
- `isAttributionEnabled = true` sin ocultar la licencia bajo HUD/marcadores.
- Proyección MapLibre no cambia las coordenadas persistentes de `RiderMarkerUiModel`.

### 7.4 Evidencia de mapa requerida

- Caso 1: ambos a 1 m, mini-compacto/correcto local+peer real, no aparición a 2 km.
- Caso 2: encuadre waypoint+peers con bounds real, no desde CDMX.
- Caso 3: atribución OSM visible y no solapada al menos en captura indicada.
- Caso 4: zoom como usuario no provoca saltos de recenter cada segundo.
- Caso 5: desactivación de online sigue mostrando estilo offline/route sin pantalla blanca.

---

## 8. Orden específico de ejecución y commits sugeridos

> Cada commit solo con autorización del usuario. Sin amend/push.

1. **`fix(routes): GPS de peers e histéresis/recálculo correctos`**
   - Bloque 0 completado, pruebas verdes reales.
2. **`fix(mesh): verificar cabina resistente a cambios de pantalla`**
   - Solo si se prueba una causa real adicional del foco. Si es overlay inocuo, documentarlo sin reescribir servicio.
3. **`feat(audio): audio SOS con foco, ACK y parada segura`**
   - Solo Hito 1 + pruebas + capturas de comportamiento audible.
4. **`feat(signals): tonos coherentes para señales de convoy`**
   - Audio local de Hito 2. La emisión por malla, si se implementa, va en commit independiente con idempotencia y no GATT nuevo.
5. **`feat(map): proyección MapLibre y viewport correcto`**
   - Proyección real, bounds, OSM attribution, nudging/gestión de cámara y tests.
6. **Docs finales con evidencia**
   - `HISTORY_NOTE.md` actualizado con estado exacto. Capturas nombre/día/dispositivo claras.

No combinar estos hitos en una rama de 2.000 cambios mezclados. Fijar working tree previo en cada uno y no añadir fixes anteriores.

---

## 9. Hardware: protocolo correcto para no bloquear ni falsificar pruebas

### Antes de empezar

- TECNO `098813734B105395`, Redmi `b2c398bb`, misma sesión/club servidor.
- Wi-Fi/Tailscale funcional para backend/roster, **pero la malla es BLE local**. Poder alcanzar Fastify no prueba malla BLE.
- Asegurar no grabación mutua necesaria entre dispositivos y permiso real de location/BT/notifications según SDK.
- Observar modo real vs in-app sim:
  - No validar conectividad BLE real si `isUsingSimulatedTransport=true`.
  - En logs del servicio hay distinción entre FGS real e in-app simulation. No ocultarla en documentos.
- Pareja física a ~1 m, Bluetooth encendido y teléfonos desbloqueados mientras se inicia.
- Reiniciar app solo si se necesita, sin clear data.

### Paso 1: presencia/gestión de ciclo

- Líder hace Iniciar Ruta desde roster solo cuando todos los confirmados están En sitio.
- Miembro entra por Cabina clásica después del fix, no ejecuta `/start`.
- Capturar roster antes/después para separar backend y BLE.

### Paso 2: Mapa / GPS

- Validar que hay fix GPS real o caché real local. Centinelas no cuentan.
- Confirmar que el mapa no fabrica otro municipio para el peer y no usa CDMX por defecto.
- Guardar lat/lon imprimida por app con cuidado de no exponer historia/datos de terceros.

### Paso 3: Cambio de pantalla

Entrar/salir Monitor Malla varias veces y verificar:

- Mismo `lifecycleState = RUNNING` y engine real activo.
- No detener foreground real mientras usuario navega overlay.
- Notificación/malla y HUD de peers no caen arbitrariamente a 1.

Si ocurre: log antes/durante/después de cada overlay, no cambiar código solo porque navegar “parece” relacionado.

### Paso 4: Audio

- SOS manual local y recepción en peer real con color/etiqueta correcta.
- Cancelación/detención inmediata.
- PTT sigue operativo antes/después de tonos, sin cortar micrófono ni foco colgado.
- Realmente escuchar: no declarar testing sólo por color de HUD.

### Paso 5: Desvío/recálculo

- Fuera de ruta <150 m temporal: no recalcular por jitter.
- >150 m sostenido >=10 s: recálculo automático sin toque.
- Offline: mensaje correcto y cache sin intento falso de ruta nueva.
- Foto/video/log de `RecalculateRouteOutcome`.

### Si Redmi vuelve a fallar ADB

- No repetir 10 veces reboot/factory reset/parings como superstición.
- Si `offline`: pedir desbloqueo y aceptar autorización USB.
- Si `10061`: informar que Tailscale sigue arriba pero adbd no escucha/rechaza, guardar salida e intentar USB directo estable, no modificar permisos app ni reinstalar para resolver un problema de enlace.
- Con bloqueo real, informar igual que `fase5_cabina_redmi_block_20261120.txt` y no marcar hardware como aprobado.

---

## 10. Formato obligatorio de evidencia y documentación al cerrar

Por cada hito registrar en `docs/HISTORY_NOTE.md`:

```markdown
### HITO N — tema y fecha

**Cambio (archivos):** lista exacta.

**Diagnóstico:** causa real demostrada, no hipótesis sin probar.

**Pruebas:** tareas ejecutadas + resultado + XML si aplica.

**Hardware:** qué se hizo en cada dispositivo y modo real/simulado.

**Capturas/logs:** ruta real a archivos nuevos.

**Pendiente:** lo que falta, sin ocultarlo ni redeclararlo como hecho.
```

No editar docs para afirmar commits no creados. Cada sección de commit se escribe después de crear el commit, con SHA real.

---

## 11. Regla final para el siguiente agente gratuito

**Primero corregir y probar, luego ampliar.**

SOS sonido sí puede escucharse emergente y luego detenerse; señales deben ser cortas y nunca borrar/atropellar PTT; mapa debe proyectar lat/lon reales, nunca espaciar visualmente una posición a kilómetros solo porque el avatar solapa.

Mantener el código pequeño, interfaces inyectables y descripción de salida honesta. La cercanía en mapa sale de coordenadas reales, la persistencia de malla sale de un Foreground Service funcionando y la recalculación automática sale de desviación sostenida real, no de timers/banners o respuestas inventadas para impresionar una captura.

**Verificación**: `.\gradlew.bat :app:testStandardDebugUnitTest --tests "*PeerPositionValidityTest"` debe pasar (0 fallos).
