# FASE 2 — Verificación controlada de audio (2026-11-27)

## Alcance

Verificación por `adb` (logs + capturas) del bootstrap global de audio y del ciclo PTT por
deep-link. **No sustituye** la audición humana, la pulsación de botones físicos de casco ni
la Prueba D en carretera, que siguen como `PENDIENTE`.

## Artefacto instalado

- APK: `android/app/build/outputs/apk/standard/debug/app-standard-debug.apk`
- SHA-256: `2C9D780F9CADF774982F3CDF74A4634C5DB25E2901F870EF0D80FB1D8F948391`
- Comando: `adb install -r` en TECNO (`098813734B105395`) y REDMI (`b2c398bb`).
- `dumpsys package`: `versionCode=1`, `versionName=0.1.0`, `targetSdk=35` en ambos
  (el `versionCode` no se incrementó en esta fase).
- Se verificó que `com.rutabikersync.app.test` (instalado por una selección amplia de APK
  en un intento anterior) quedó desinstalado de ambos dispositivos.

## Procedimiento reproducible

`powershell -File docs/evidence/fase2_audio_20261127/run_ptt_capture.ps1`

El script instala el APK, limpia logcat, lanza `MainActivity`, dispara
`rbs://ptt?action=start`, espera 5 s, dispara `rbs://ptt?action=stop`, espera 3 s y guarda
logs filtrados, logs crudos y capturas por dispositivo.

## Resultados observados

| Dispositivo | Bootstrap | PTT start | PTT stop | Capturas |
|---|---|---|---|---|
| TECNO | `I AppAudioBootstrap: hub de audio inicializado con alcance de aplicacion` (17:46:44.504) | `MainActivity: Deep Link rbs://ptt?action=start procesado`, `BleMeshService: ACTION_PTT_START recibido`, `PttAudioEngine: PTT: Iniciando transmisión de audio...`, `AudioRecord: start` | `ACTION_PTT_STOP recibido`, `PttAudioEngine: PTT: Transmisión finalizada por el usuario.` | `tecno-ptt-start.png`, `tecno-ptt-stop.png` |
| REDMI | `I AppAudioBootstrap: hub de audio inicializado con alcance de aplicacion` (17:47:09.748) | `MainActivity: Deep Link rbs://ptt?action=start procesado`, `BleMeshService: ACTION_PTT_START recibido`, `PttAudioEngine: PTT: Iniciando transmisión de audio...` | `ACTION_PTT_STOP recibido`, `PttAudioEngine: PTT: Transmisión finalizada por el usuario.` | `redmi-ptt-start.png`, `redmi-ptt-stop.png` |

## Limitación observada

Durante el PTT aparece de forma repetida:

`W BleMeshService: VoicePacketTx descartado: no hay relayEngine activo en RideService`

No se inició una rodada (no existe `rbs://ride` y el arranque exige manifiesto firmado y
sesión de club válida), por lo que **no se validó la transmisión de voz extremo a extremo**.
El hallazgo confirma que el micrófono y el motor PTT arrancan, pero el paquete de voz se
descarta sin sesión activa. La inteligibilidad y la recepción en el otro móvil requieren
presencia humana y rodada real: **`PENDIENTE`**.

## Archivos

- `run_ptt_capture.ps1`
- `<dispositivo>-boot-logcat.txt` / `-raw.txt`
- `<dispositivo>-ptt-start-logcat.txt` / `-raw.txt`
- `<dispositivo>-ptt-stop-logcat.txt` / `-raw.txt`
- `<dispositivo>-ptt-start.png` / `-ptt-stop.png`
- `<dispositivo>-install.txt`, `-launch.txt`, `-ptt-start-intent.txt`, `-ptt-stop-intent.txt`,
  `-package-version.txt`, `-package-dumpsys.txt`, `-uninstall-testapk.txt`, `-screencap.txt`
