# Matriz pantalla ↔ permiso ↔ degradación (T-08, B3-1/B3-3/B3-4/B3-5)

**Fuente:** inventario estático en `android/app/src/main/AndroidManifest.xml` y `docs/PERMISSIONS_USAGE_REPORT_T08.md` (grep de uso).  
**Alcance:** documento de análisis; **no se eliminan permisos** sin evidencia funcional adicional. Validación runtime/UI pendiente.

## Resumen de postura
- La app declara permisos de red, ubicación, Bluetooth, cámara, micrófono, notificaciones, biometría y foreground services para funciones de mapa/rodada, mesh/PTT, SOS, QR y bóveda.
- Solicitud en contexto y degradación deben verificarse por pantalla en dispositivos reales (pendiente de evidencia de ejecución).
- Ningún permiso fue eliminado en este ticket por falta de prueba exhaustiva de no uso en todos los flavors/flujos.

## Matriz (propuesta basada en evidencia estática y flujos conocidos)

| Pantalla / Flujo | Permisos implicados | Justificación funcional (esperada) | Degradación esperada ante denegación | Evidencia / estado |
|---|---|---|---|---|
| Mapa en vivo / Cabina / Rodada | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, `INTERNET`, `ACCESS_NETWORK_STATE` | Geolocalización del piloto, tracking y visualización de ruta/convoy | Mapa sin posición precisa, tracking deshabilitado o limitado; funciones de rodada en vivo degradadas | Declarados en manifest; uso detallado requiere verificación runtime |
| Mesh BLE / Descubrimiento cercano / PTT | `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH`/`BLUETOOTH_ADMIN` (<=30), `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `NEARBY_WIFI_DEVICES` (si aplica), `POST_NOTIFICATIONS` | Descubrimiento/enlace BLE, servicio foreground, alertas de estado | Mesh/PTT no disponible o parcial; app debe informar modo offline/limitado | Declarados; `BleMeshService` activo (T-04 restringido a `exported=false`) |
| PTT / Audio en malla | `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE` (si aplica), `MODIFY_AUDIO_SETTINGS` | Captura/transmisión de voz push-to-talk | PTT deshabilitado; resto de app usable | Declarado; validar en hardware |
| SOS / Emergencia / Lock screen | `ACCESS_FINE_LOCATION` (contexto), `CALL_PHONE` (si llamada ICE), `POST_NOTIFICATIONS`, `WAKE_LOCK` | Envío/recepción de alertas, acceso rápido a ficha de emergencia, llamada a contacto | Sin ubicación precisa en SOS, sin llamada directa o sin notificaciones según permiso | `EmergencyLockScreenActivity` restringida (T-07); validación funcional pendiente |
| Escaneo QR (invites/unirse) | `CAMERA` | Lectura de códigos QR | Entrada manual/código alterno si existe; escaneo no disponible | Declarado; confirmar pantallas concretas en QA |
| Bóveda / Documentos / Biometría | `USE_BIOMETRIC`/`USE_FINGERPRINT` (según API), acceso a almacenamiento/medios según implementación | Desbloqueo biométrico y gestión de documentos médicos/identidad | Fallback a PIN/clave o acceso denegado a funciones protegidas | `VaultRepository` y pantallas de bóveda; revisar permisos de almacenamiento reales |
| Notificaciones (FCM/estado) | `POST_NOTIFICATIONS` (Android 13+), `INTERNET` | Avisos operativos y de seguridad | Sin notificaciones visibles; estado solo in-app | Declarado; validar en Android 13+ |

## Eliminaciones propuestas (no aplicadas)
- **Ninguna eliminación automática en este cambio.** Requiere evidencia de no uso por flujo/flavor (incl. `fieldTest/uxTest`) y pruebas de regresión.
- Candidatos a revisar con evidencia adicional: permisos de almacenamiento/medios legados si la app usa exclusivamente APIs modernas/scoped storage; permisos Bluetooth heredados en minSdk/targets actuales.

## Almacenamiento local sensible (inventario preliminar, T-08)
- `SharedPreferences`: `rbs_encrypted_session_store.xml` (sesión/tokens cifrados), `rbs_biker_vault_local_store.xml` (bóveda/estado médico local cifrado según implementación).
- Reglas de backup/extracción actualizadas para excluir estos prefs y, de forma conservadora, dominios `database` y `file` en cloud-backup/device-transfer hasta completar inventario fino (puede afectar restauración de datos no sensibles; decisión de producto/UX pendiente).

## Validación pendiente (manual/QA)
- Confirmar solicitud en contexto, rationale y comportamiento ante denegación en cada pantalla.
- Probar degradación en Android 12+/13+/14 (Bluetooth, ubicación, notificaciones, cámara, micrófono).
- Inventariar bases de datos/archivos exactos y ajustar exclusiones de backup con granularidad si se requiere restauración selectiva.
