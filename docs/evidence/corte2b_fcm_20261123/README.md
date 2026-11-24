# Corte 2B — Evidencia hardware de FCM live (2026-11-23)

## 1. Confirmación de FCM live (no Stub)

Comando (helper temporal dentro de `backend/`, heredero de `"type": "module"`):

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS='D:\Ruta-Biker-Sync\backend\service_account.json'
& 'D:\Ruta-Biker-Sync\backend\node_modules\.bin\tsx.cmd' 'D:\Ruta-Biker-Sync\backend\corte2b_fcm_send.mts'
```

Salida real:

```text
🔥 FCM_LIVE: Firebase Admin SDK inicializado con GOOGLE_APPLICATION_CREDENTIALS.
FCM_IS_LIVE=true
tecno_sos_bg success=1 failure=0 id=projects/rut…
redmi_chat_bg success=1 failure=0 id=projects/rut…
tecno_chat_bg success=1 failure=0 id=projects/rut…
redmi_sos_bg  success=1 failure=0 id=projects/rut…
```

La credencial se leyó **solo** de `GOOGLE_APPLICATION_CREDENTIALS`. `backend/service_account.json` sigue ignorado por git y no fue commiteado.

> Nota de compatibilidad: `firebase-admin@14.5.1` ya no expone `admin.credential.cert` ni `app.messaging()`. Se corrigió en los commits `1bb2147` y `7553813` para usar `admin.cert` y `getMessaging` de `firebase-admin/messaging`. Sin esos dos fixes el SDK caía a Stub o lanzaba `TypeError`.

## 2. Dispositivos y build

| Dispositivo | Serial | Token FCM | Estado de la app |
| --- | --- | --- | --- |
| TECNO | `098813734B105395` | 142 chars, registrado vía `/v1/users/fcm-token` | background real (`stopped=false`) |
| Redmi | `b2c398bb` | 142 chars, registrado vía `/v1/users/fcm-token` | background real (`stopped=false`) |

APK: `android/app/build/outputs/apk/standard/debug/app-standard-debug.apk`, instalado con `adb install -r` en ambos dispositivos. `adb reverse tcp:3000 tcp:3000` activo en ambos.

**Caveat verificado**: con `am force-stop` Android deja la app en estado `stopped=true` y el sistema **no entrega** FCM (primer intento sin `NotificationRecord`). Se corregió lanzando `MainActivity` y pulsando HOME (`input keyevent 3`), confirmando `stopped=false` con `dumpsys package`. Esa es la configuración usada en toda la evidencia válida.

## 3. Push real con app en background — rondas cruzadas

| Ronda | Dispositivo | Tipo enviado | `data.type` | Canal observado en `dumpsys notification` | importance | Resultado |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | TECNO | SOS proximidad | `sos` | `sos_channel` | 4 (high) | ✅ |
| 1 | Redmi | Chat táctico | `tactical_chat` | `direct_message_channel` | 3 (default) | ✅ |
| 2 | TECNO | Chat táctico | `tactical_chat` | `direct_message_channel` | 3 (default) | ✅ |
| 2 | Redmi | SOS proximidad | `sos` | `sos_channel` | 4 (high) | ✅ |

Registros literales extraídos de los `dumpsys`:

```text
TECNO ronda 1:
NotificationRecord(... pkg=com.rutabikersync.app ... tag=FCM-Notification:2359247694 importance=4
  ... Notification(channel=sos_channel ...))

Redmi ronda 1:
NotificationRecord(... pkg=com.rutabikersync.app ... tag=FCM-Notification:35891926 importance=3
  ... Notification(channel=direct_message_channel ...))

TECNO ronda 2 (conviven ambos canales):
... tag=FCM-Notification:2359247694 importance=4 ... channel=sos_channel
... tag=FCM-Notification:2359274435 importance=3 ... channel=direct_message_channel

Redmi ronda 2 (conviven ambos canales):
... tag=FCM-Notification:35921059 importance=4 ... channel=sos_channel
... tag=FCM-Notification:35891926 importance=3 ... channel=direct_message_channel
```

Archivos de soporte:

- `push_round1_background_send.log`, `push_round2_background_send.log` — salida real del envío FCM.
- `tecno_round1_background_dumpsys.txt`, `redmi_round1_background_dumpsys.txt`.
- `tecno_round2_background_dumpsys.txt`, `redmi_round2_background_dumpsys.txt`.
- `tecno_round1_background.png`, `redmi_round1_background.png`, `tecno_round2_background.png`, `redmi_round2_background.png` — capturas de pantalla.
- `*_background_logcat.txt` — logcat completo de cada captura (sin PII de medical sheet; el payload SOS viaja con `lat/lng`, `distanceKm` y `bearing`).

La diferencia de `importance` (4 vs 3) confirma que el canal SOS suena con prioridad/alarma y el canal de chat no, sin necesidad de escuchar el timbre físico.

## 4. Guardas del endpoint con el código commiteado

Instancia limpia en `:3001` (la de `:3000` era un proceso anterior con código viejo):

```text
POST /v1/notifications/send  sin groupId/messageId
→ HTTP 400 {"error":"VALIDATION_ERROR", ... "groupId":["Required"],"messageId":["Required"]}

POST /v1/notifications/send  emisor = Redmi (no miembro de e3988eba…)
→ HTTP 403 {"error":"NOT_CLUB_MEMBER","message":"El emisor no es miembro del club indicado"}
```

Archivos: `backend_acl_nonmember_403.json`, `acl_nonmember_payload.json`.

Rate limit `10/h` e idempotencia por `messageId` quedan cubiertos por `backend/test/phase7_fcm_notifications.test.ts` (tests 2.6 y 2.7), que usan `notification_send_receipts` de la migración `0023`.

## 5. Tests y deep-links

| Suite | Comando | Resultado |
| --- | --- | --- |
| Backend (focalizada) | `npm test -- test/phase7_fcm_notifications.test.ts test/migrations_registry.test.ts` | 15/15 |
| Backend (completa) | `npm test -- --no-file-parallelism` | 172/172 |
| Typecheck | `npm run typecheck` | OK |
| Android JVM | `.\gradlew.bat :app:testStandardDebugUnitTest --console=plain --rerun-tasks` | BUILD SUCCESSFUL |
| APK | `.\gradlew.bat :app:assembleStandardDebug --console=plain` | BUILD SUCCESSFUL |

`RutaBikerMessagingServiceTest.test 6` verifica los deep-links del mapper real `NotificationRouting`: `START_RIDE_PREVIEW` + `SOS_ALERT`/`RIDE_ID`, `START_CLUB_TAB` + `GROUP_ID`, `START_TACTICAL_CHAT` + `GROUP_ID` y `START_RIDE_PREVIEW` para `CONVOY_SIGNAL`.

## 6. Alcance no tocado

Sin push a remoto, sin `amend`. No se modificaron GATT, cifrado ChaCha20/Ed25519, `HeartbeatFrame`, PTT, liderazgo ni los assets ajenos del working tree.
