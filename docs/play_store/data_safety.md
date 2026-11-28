# Borrador — Data Safety (Google Play Console)

**Estado:** BORRADOR para copiar en Play Console. **NO enviado.**
**Fecha:** 2026-11-27 · **Package:** `com.rutabikersync.app`

> Regla aplicada: declarar solo lo que el código y el manifest respaldan. En particular, **no declarar `ACCESS_BACKGROUND_LOCATION`**: `AndroidManifest.xml:17-22` ya no lo pide. La ubicación se usa en primer plano durante la rodada activa con servicio en primer plano y notificación persistente; Bluetooth usa `neverForLocation`.

## 1. Datos recopilados

| Categoría | ¿Se recopila? | Finalidad | ¿Se almacena en servidor? | Detalle verificable |
|---|---|---|---|---|
| Ubicación aproximada | Sí, efímera | Mapa y distancias relativas del convoy durante la rodada | No | Foreground service `RideService`; se difunde por BLE a participantes del manifiesto. |
| Ubicación precisa | Sí, efímera | Coordenadas de rescate en SOS y navegación | No | No hay historial GPS en backend; `docs/GOOGLE_PLAY_DATA_SAFETY.md`. |
| Nombre / apodo, avatar, motocicleta | Sí | Perfil, lista del club y cabina | Sí | `backend/src/app.ts` (`PATCH /v1/users/me`); visible para miembros autorizados del club. |
| Correo electrónico e ID de usuario | Sí | Autenticación, membresías y suscripción de líder | Sí | Estado de suscripción gestionado por Google Play; el backend no guarda datos del método de pago. |
| Ficha médica (sangre, alergias, contacto, póliza) | Sí | Asistencia en emergencia | No | Bóveda local cifrada en el dispositivo; se comparte solo en SOS mediante paquete sellado. |
| Voz PTT | Sí, efímera | Avisos operativos entre pilotos | No | `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE`; clips efímeros en malla BLE. |
| Fotos / documentos | Sí | Avatar y bóveda de documentos | Avatar sí; documentos no | Documentos cifrados localmente. |
| Identificadores de dispositivo / token FCM | Sí | Notificaciones de rodada, club y SOS | Sí | Módulo `backend/src/lib/fcm.ts`. |
| Claves Ed25519 públicas | Sí | Manifiesto firmado de la rodada | Sí | Sin claves privadas en backend. |
| Anuncios / publicidad | No | — | — | Sin SDK de anuncios en la app; los banners propios son del sitio web. |

## 2. Datos compartidos

- **Con otros usuarios de la app:** nombre visible, avatar, color de moto, coordenadas efímeras de rodada, clips PTT y ficha médica solo en SOS, siempre limitados a los participantes del manifiesto o miembros del club.
- **Con terceros:** no se venden ni se comparten con fines publicitarios. El proveedor de notificaciones (FCM) recibe el token necesario para entregar avisos.
- **Con Google Play:** estado de suscripción de líderes.

## 3. Seguridad y retención

- Tránsito: HTTPS con `networkSecurityConfig` que prohíbe cleartext. Sin certificate pinning.
- Almacenamiento: bóveda local AES-256-GCM con claves AndroidKeyStore y PBKDF2; backend con acceso restringido.
- Retención: mientras la cuenta esté activa; el usuario puede exportar o eliminar desde Perfil. La baja anonimiza datos y conserva solo un registro de auditoría con identificador técnico y fecha.
- No se afirma resistencia frente a adversario activo: no hay anti-tamper/root detection ni anti-replay persistente.

## 4. Permisos a declarar en Console

`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, Bluetooth (`BLUETOOTH_SCAN` con `neverForLocation`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`), `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `CAMERA` (bóveda), `USE_BIOMETRIC`, `INTERNET`, `WAKE_LOCK`.

**No declarar:** `ACCESS_BACKGROUND_LOCATION` (eliminado del manifest).

## 5. Pasos en Console

1. App content → Data safety → completar las tablas anteriores.
2. Marcar "Data is encrypted in transit" y "Data is deleted on request".
3. Guardar y adjuntar captura en `docs/evidence/...` (PENDIENTE: requiere Console).
