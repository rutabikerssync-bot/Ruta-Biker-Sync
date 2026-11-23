# FIX B · Gracia visual del HUD y timeout recalibrado

Fecha: 23 nov 2026. Rama: `feat/paso-a-ui`. Build verificado: `app-standard-debug.apk`,
SHA-256 `326E002B036034AC88040D77D1A091CC2DBF70305F2E1C04C746EF073ED68159`.

## Dispositivos

| Dispositivo | Serial | Estado |
|---|---|---|
| TECNO | `098813734B105395` | APK instalada, cockpit abierto con `START_RIDE_PREVIEW=true` |
| Redmi | `b2c398bb` | APK instalada, cockpit abierto y luego `am force-stop` para simular pérdida de señal |

Túnel: `adb -s <serial> reverse tcp:3000 tcp:3000` en ambos dispositivos.

## Hitos capturados

| Hito | Hora local | Archivo | Resultado |
|---|---|---|---|
| Cockpit TECNO inicial | 18:19 | `tecno_00_opened.png/.xml` | Perfil del club; sin crash |
| HUD TECNO con peer | 18:21 | `tecno_01_hud.png/.xml`, `tecno_02_hud.png/.xml` | **2 riders** |
| HUD Redmi con peer | 18:22 | `redmi_01_hud.png/.xml` | **2 riders** |
| Redmi detenido (`am force-stop`) | 18:23:13 | `tecno_logcat_gracia.txt` | El peer deja de emitir heartbeats |
| TECNO a +99 s | 18:24:52 | `tecno_03_t95.png/.xml` | **2 riders** → gracia activa, no cae a 1 |
| TECNO a +168 s | 18:26:01 | `tecno_04_t160.png/.xml` | **1 rider** → venció timeout + gracia |
| Logcat TECNO | 18:26 | `tecno_logcat_hud.txt`, `tecno_logcat_gracia.txt` | Sin `FATAL` / `AndroidRuntime` del app |

## Conclusión

El HUD **no bajó a 1 rider durante la ventana de gracia** tras la pérdida real de señal de un
peer en BLE (Redmi detenido), y bajó a 1 únicamente después de vencerse el timeout recalibrado
(≤ 90 s) más la gracia de 60 s. La transición observada (99 s → 2 riders, 168 s → 1 rider) es
coherente con el rango esperado de 40,5–90 s + 60 s.

Nota: la etiqueta `✕ Sin señal` del marcador se dibuja en el canvas de Compose y no aparece
como nodo accesible en el dump de `uiautomator`; el criterio verificable en hardware fue el
contador `Convoy` del HUD.

## Comandos de reproducción

```powershell
adb -s 098813734B105395 install -r android\app\build\outputs\apk\standard\debug\app-standard-debug.apk
adb -s b2c398bb install -r android\app\build\outputs\apk\standard\debug\app-standard-debug.apk
adb -s 098813734B105395 reverse tcp:3000 tcp:3000
adb -s b2c398bb reverse tcp:3000 tcp:3000
adb -s 098813734B105395 shell am start -n com.rutabikersync.app/.MainActivity --ez START_RIDE_PREVIEW true
adb -s b2c398bb shell am start -n com.rutabikersync.app/.MainActivity --ez START_RIDE_PREVIEW true
adb -s b2c398bb shell am force-stop com.rutabikersync.app
# Capturar TECNO a los 95 s y a los 160 s
```
