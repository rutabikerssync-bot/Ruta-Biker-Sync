# Redundancia híbrida BLE ↔ Internet — decisión de planificación (2026-11-22)

## Auditoría verificada en el workspace seguro

Revisado en `D:\Ruta-Biker-Sync-piloto` (no en el repo base):

- Backend: existen `POST /v1/telemetry/heartbeat` (app.ts:3580) y
  `POST /v1/sos` (app.ts:3629). **No existen** `GET /v1/rides/:id/telemetry`,
  `POST/GET /v1/rides/:id/chat` ni `POST /v1/rides/:id/signals`.
- Android: `LiveMapViewModel` solo consume `MeshRelayEngine.peers` (línea 304);
  no hay lectura HTTP de posiciones de compañeros.
- Existen `BleMeshSosBridge`, `RoutePolylineMeshRelay`, `RsvpOutboxWorker` y
  `TacticalChatStore`. No existe `HybridTransportDispatcher`.

Conclusión: SOS y rutas son redundantes; telemetría en vivo, chat táctico y
señales rápidas son exclusivamente BLE. El diagnóstico compartido es correcto.

## Decisión

**Diferir a un pilar propio, después de la prueba de campo.** No se incorpora al
avance actual: añadir 3 endpoints, dispatcher, doble despacho, LRU y retención
efímero inflaría los archivos existentes y mezclaría responsabilidades.

## Alcance previsto del futuro pilar

1. **Corte 1 — solo lectura:** `GET /v1/rides/:id/telemetry` y pines `[4G]` en
   `LiveMapViewModel`, en archivo nuevo (`HybridTransportDispatcher.kt`). Sin
   tocar malla, GATT, cifrado ni liderazgo.
2. **Corte 2 — chat y señales:** requiere diseño R12 propio, TTL corto y
   credenciales FCM (no disponibles hoy).
3. **Restricciones:** se mantienen para GATT, `HeartbeatFrame`, cripto y
   liderazgo. El usuario autorizó levantar **solo para señales rápidas/PTT**,
   y aun así con autorización puntual antes de cada cambio.

No se ha escrito código de este pilar.

## Prueba de campo 2026-11-22

Grabadores internos armados con `setsid logcat -v threadtime -f` (sobreviven a
la desconexión de adb, verificado):

| Equipo | Archivo en `/sdcard/Download/` |
|---|---|
| TECNO `098813734B105395` | `rbs_campo_tecno_lider_20261122.log` |
| Redmi `b2c398bb` | `rbs_campo_redmi_miembro_20261122.log` |

Hitos: ruta ida/vuelta guardada, separaciones 5/25/50 m, respuesta del
convocado, cambio de ruta y recálculo, retorno, mensajes rápidos, chat táctico
y SOS. Al volver: detener grabación, `adb pull`, correlacionar por timestamp y
capturar evidencia en commits atómicos sin push.
