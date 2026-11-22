# Corte 1 de redundancia BLE ↔ 4G — telemetría en vivo (2026-11-22 16:30 -04:00)

## Alcance implementado

Se cerró el circuito mínimo de lectura HTTP sin tocar GATT, cifrado Ed25519/ChaCha20,
`HeartbeatFrame`, PTT ni liderazgo.

### Backend

- `POST /v1/telemetry/heartbeat` acepta `rideId?: UUID` opcional y retrocompatible.
  Si se envía, valida que el usuario sea participante o directivo anfitrión de la rodada.
- Nuevo `GET /v1/rides/:id/telemetry?maxAgeSeconds=30&limit=50`:
  - exige autenticación y pertenencia a la rodada;
  - devuelve la muestra más reciente por usuario dentro de la ventana de frescura;
  - responde `{ rideId, sampledAt, maxAgeSeconds, riders: [...] }`.
- Sin migración nueva: `ephemeral_telemetry.ride_id` ya existía y está indexado.

### Android

- `ClubApiClient.sendTelemetryHeartbeat(..., rideId)` y `fetchRideTelemetry(rideId)`.
- `HybridTransportDispatcher.kt` (archivo nuevo): fusión BLE + HTTP con TTL de 30 s,
  precedencia de BLE cuando existe equivalente, shortId sintético y perfil redactado
  cuando no hay mapeo `userId ↔ shortId`. Nunca escribe en el motor BLE ni marca peers perdidos.
- `LiveMapViewModel`: `currentBlePeerSnapshots()` y `setHybridPositions()`; los pines HTTP
  se convierten a `RiderMarkerUiModel` con `telemetrySource = HTTP_4G`.
- `RiderMarker`: chip `[4G]` junto al aviso de antigüedad. No se cambió avatar, anillo,
  icono de dirección, `MapView`, zoom, pan, rotación, estilo ni attribution.
- `LiveMapScreen`: el heartbeat HTTP usa solo el fix GPS real y reciente guardado en
  `rbs_geo_cache`, nunca la cámara. El dispatcher solo se inicia si `rideId` es UUID válido.

## Verificación

- Backend `npm run typecheck`: limpio.
- Backend suite completa con PostgreSQL real: **30 archivos / 181 pruebas, 0 fallos**
  (4 pruebas nuevas en `hybrid_telemetry_ride.test.ts`).
- Android `:app:testStandardDebugUnitTest`: **90 suites / 510 tests, 0 fallos, 0 errores, 0 omitidos**.
- Android `:app:assembleStandardDebug`: **BUILD SUCCESSFUL**.

## Commits atómicos

1. `cf94691 backend(telemetry): añadir lectura de posiciones por rodada`
2. `769a8d9 android(network): exponer telemetria de rodada por HTTP`
3. `66e7721 android(mesh): añadir fusionBLE y HTTP para telemetria`
4. `308fdaa android(livemap): mostrar pines 4G en telemetria hibrida`
5. `8d51f3b android(livemap): cablear redundancia HTTP con UUID real`

Sin `amend`, sin `push`.

## Limitación operativa pendiente

La app todavía inicia la cabina con `rideId = "ride-live-01"`, que **no es UUID**.
Por decisión deliberada, el dispatcher no inventa ni reutiliza identificadores: mientras
no exista una rodada real con UUID, el downlink HTTP queda desactivado y el mapa sigue
funcionando solo con BLE. Para la prueba con terceros falta:

1. crear una rodada real desde el backend (`POST /v1/rides`) y usar su UUID en la cabina;
2. reiniciar el backend desde el workspace piloto para que sirva el endpoint nuevo;
3. actualizar la URL del túnel en ambos móviles si cambia;
4. entonces verificar pines `[4G]` con un móvil sin cobertura.

El APK quedó compilado pero **no instalado** en esta entrega.
