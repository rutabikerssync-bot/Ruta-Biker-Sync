# Data Safety — valores exactos para Play Console

**Estado:** FORMULARIO PREPARADO; **NO enviado**. Copiar los valores a *App content → Data safety*.
**Fecha:** 2026-11-27 · **Package:** `com.rutabikersync.app`
**Fuentes:** `docs/GOOGLE_PLAY_DATA_SAFETY.md`, `docs/play_store/data_safety.md` y `android/app/src/main/AndroidManifest.xml`.

> Regla crítica: **no declarar `ACCESS_BACKGROUND_LOCATION`**. El manifest vigente solo pide `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` con `FOREGROUND_SERVICE_LOCATION` para la rodada activa; Bluetooth usa `neverForLocation`.

## 1. Datos que recopila la app

| Categoría del formulario | Marcar | Finalidad exacta | ¿Se almacena en servidor? |
|---|---|---|---|
| Ubicación aproximada | **Sí** | Mapa y cálculo de distancias relativas del convoy durante la rodada. | **No**: se procesa en memoria y se difunde por BLE a participantes autorizados. |
| Ubicación precisa | **Sí** | Coordenadas de rescate en SOS y navegación de la rodada. | **No**: sin historial GPS en backend; arquitectura local/P2P. |
| Nombre / apodo, avatar, motocicleta | **Sí** | Perfil del piloto, lista del club y cabina. | **Sí**: `PATCH /v1/users/me`; visible para miembros autorizados del club. |
| Correo electrónico | **Sí** | Autenticación, recuperación de acceso y gestión de suscripción. | **Sí** (identidad/estado de suscripción). El backend no guarda datos del método de pago. |
| Identificadores de usuario / clave pública Ed25519 | **Sí** | Manifiesto firmado de la rodada y vinculación de la cuenta. | **Sí** (solo claves públicas; nunca privadas). |
| Información de salud (ficha médica: sangre, alergias, contacto de rescate, póliza) | **Sí** | Asistencia en emergencia, cifrada localmente; solo se comparte en SOS. | **No**: bóveda local cifrada en el dispositivo. |
| Grabaciones de voz (PTT) | **Sí, efímeras** | Avisos operativos de pilotos a pilotos por malla BLE. | **No**: no se guardan en la nube ni en disco; se purgan de la cola. |
| Fotos / documentos | **Sí** | Avatar y bóveda de documentos/póliza. | Avatar **sí**; documentos **no**, cifrados localmente. |
| Identificadores de dispositivo / token FCM | **Sí** | Notificaciones push de rodada, club y SOS. | **Sí**. |
| Compras / estado de suscripción | **Sí** | Suscripciones de Líder y Miembro gestionadas por Google Play. | **Sí** (token/estado); sin tarjeta en el backend. |
| Anuncios / publicidad | **No** | Sin SDK publicitario en la app. | — |

## 2. Cómo se usan los datos

- **Funcionalidad principal:** coordinación del convoy, mapa en rodada, detección/alerta SOS, perfil y bóveda, avisos push y PTT.
- **Ubicación:** solo durante la rodada activa, con servicio en primer plano y notificación persistente. El servicio se detiene al finalizar la rodada.
- **Comunicaciones:** P2P por BLE para posición/voz/SOS; HTTPS para autenticación, clubes,RSVP y suscripciones.
- **No se usa para publicidad, rastreo publicitario ni perfilado comercial.** No hay SDK de anuncios en el cliente Android.

## 3. Si se comparten

| Destino | Qué se comparte | Condición |
|---|---|---|
| Otros pilotos del club / participantes del manifiesto | Nombre visible, avatar, color de moto, coordenadas efímeras de rodada, clips PTT y ficha médica **solo en SOS** | Limitado a miembros autorizados o participantes de la rodada. |
| Proveedor de notificaciones (FCM) | Token de dispositivo necesario para entregar avisos | Necesario para notificaciones push. |
| Google Play | Estado/token de suscripción | Gestión de suscripciones. |
| Terceros con fines publicitarios | **Nada** | No se venden ni comparten datos con anunciantes. |

## 4. Cifrado en tránsito y en reposo

- **Tránsito:** **Sí, cifrado en tránsito**. Toda comunicación cliente-servidor usa HTTPS; `networkSecurityConfig` prohíbe cleartext. El documento interno indica TLS 1.3. **No se afirma certificate pinning** (no implementado).
- **En reposo:** ficha médica y documentos usan AES-256-GCM con claves en Android Keystore y derivación PBKDF2. El backend no recibe claves privadas.
- **Malla BLE:** comunicaciones protegidas con HMAC-SHA256 y clave efímera de rodada (`rideSecretKey`, 256 bits) según `docs/GOOGLE_PLAY_DATA_SAFETY.md`. No se modifica esa capa en esta entrega.
- **No declarar:** resistencia a adversario activo, anti-tamper/root detection ni anti-replay persistente (no implementados).

## 5. Borrado y portabilidad

- **Solicitud de eliminación:** **Sí**. La política documentada ofrece *“Eliminar Cuenta y Datos (Derecho al Olvido)”* desde el perfil; cancela membresías, purga registros y anonimiza la telemetría histórica (RGPD art. 17 / CCPA).
- **Portabilidad:** **Sí**, función *“Exportar mis Datos”* que entrega un JSON estructurado y firmado con credenciales, historial y membresías.
- **Canal alterno:** `soporte@rutabikersync.com`.
- **Antes de enviar el formulario:** verificar con la cuenta de revisor que el botón de eliminación/exportación esté visible en la build publicada; si no lo está, declarar solo el canal de correo y corregir la app antes de publicar.

## 6. Permisos a declarar en Console

Declarar: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, Bluetooth (`BLUETOOTH_SCAN` con `neverForLocation`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`), `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `CAMERA`, `USE_BIOMETRIC`, `INTERNET`, `WAKE_LOCK`.

**NO declarar:** `ACCESS_BACKGROUND_LOCATION` (eliminado del manifest).

## 7. Pasos en Console

1. App content → Data safety → *Start*.
2. Completar las tablas de los apartados 1–3 con los valores anteriores.
3. En *Security practices* marcar **Data is encrypted in transit** y **Data can be deleted on request**.
4. Guardar y adjuntar captura del formulario enviado en `docs/evidence/google_play_publicacion_20261127/` (PENDIENTE: requiere Console).
