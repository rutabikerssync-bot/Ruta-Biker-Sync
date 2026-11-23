# Evidencia — normalización de Rodadas y Ruta Libre (2026-11-23)

**APK verificada:** `android/app/build/outputs/apk/standard/debug/app-standard-debug.apk`
**SHA-256:** `8F6F992B2C100B5FFD036DB755008E70871440363371222A5831424203B32BAC`
**Dispositivos:** TECNO `098813734B105395` (líder) y Redmi `b2c398bb` (miembro).
**Backend:** Fastify `http://127.0.0.1:3000`; club canónico `e3988eba-26e8-425a-80ac-2a65aeda8484` (`Rutas Demo Bikers`).

## Casos observados en hardware

| Caso | Secciones visibles en Rodadas | Ausencias verificadas | Captura / XML |
| --- | --- | --- | --- |
| Sin club (Redmi expulsado) | `Bitácora de Rodadas`, `Ruta Libre` / `Modo Libre`, subtítulo `Modo Libre (sin registro de club, sin gamificación)`, `Iniciar Ruta Libre`, `Rutas y Corredor sin señal` | No aparecen `Próxima Convocatoria de Club`, `+ Crear Ruta Oficial de Club`, `Rodada de Club`, `PARTICIPANTES`, `Cabina Activa` | `redmi_sin_club_final.png`, `sin_club_final.xml` |
| Sin club, antes del gate (comparación) | Aparecía además `Sin programar`, `No hay rodadas programadas` y `+ Crear Ruta Oficial de Club` | — | `redmi_sin_club_antes.png`, `redmi_sin_club_despues.png`, `sin_club_antes.xml`, `sin_club_ruta_libre.xml` |
| Con club, líder (TECNO) | `Bitácora de Rodadas`, `Próxima Convocatoria de Club`, `+ Crear Ruta Oficial de Club`, `Rodada de Club`, `PARTICIPANTES (1)`, `Iniciar Ruta`, `Ruta Libre` / `Modo Libre` | Sin `Cabina Activa` | `tecno_con_club_final.png`, `con_club_top.xml`, `con_club_roster.xml` |
| Con club, miembro (Redmi, antes de la expulsión) | `PARTICIPANTES (2)`, `Ruta Libre` / `Modo Libre` / `Iniciar Ruta Libre` | Sin `Cabina Activa`; `Llegamos` sustituido por `El líder confirma la llegada` | `redmi_con_club.png` |

## Estado del miembro expulsado

- `GET /v1/groups/e3988eba-26e8-425a-80ac-2a65aeda8484/members` → `leaders: [El Rutero Bikers]`, `members: []`.
- `GET /v1/groups` con el UID del Redmi → `ledGroups: []`, `memberGroups: []`.
- En **Mi Club** la app muestra `Unirse a un Club Motero` / `Escanear Código QR Oficial`.
- No se reincorporó al miembro; el escenario sin club queda disponible para nuevas pruebas.

## Regla aplicada

- `Próxima Convocatoria de Club` y `+ Crear Ruta Oficial de Club` quedan dentro de `if (primaryGroup != null)`.
- `Rodada de Club` (roster/RSVP/arranque del líder) exige `primaryGroup != null && rideRoster != null`.
- `Ruta Libre` sigue siendo incondicional; `Cabina Activa` no existe en código, strings ni UI.
- Se conservan para todos `Bitácora de Rodadas` (kilómetros del motociclista, esté o no en club) y `Rutas y Corredor sin señal` (descarga por Wi-Fi + ejecución offline con BLE).

## Comandos reproducibles

```powershell
.\gradlew.bat :app:testStandardDebugUnitTest :app:assembleStandardDebug --console=plain
adb -s <serial> install -r android\app\build\outputs\apk\standard\debug\app-standard-debug.apk
adb -s <serial> shell uiautomator dump /sdcard/ui.xml
adb -s <serial> exec-out screencap -p > <captura.png>
```

**Suite JVM:** 89 suites, 511 tests, 0 fallos, 0 errores (`--rerun-tasks`).
