# Limpieza de datos demo en móviles y estado de la validación de campo (2026-11-22)

Worktree `D:\Ruta-Biker-Sync-piloto`, rama `feat/piloto-seguridad`, sobre el commit
`d327fae`. Backend en WSL con PostgreSQL 16 (`127.0.0.1:5433`) y túnel Cloudflare
activo. Claves de piloto y tags de build solo en variables de entorno; no se registran.

## 1. Datos demo eliminados del cliente Android

| Archivo | Cambio |
|---|---|
| `feature/badges/GamificationRepository.kt` | La siembra inicial dejó de crear 1250 km, 320 XP, 12 rodadas, 4 meses de racha, 9 insignias y 2 honores ficticios. Migración única `pilot_clean_v2` reinicia esos valores en instalaciones previas sin borrar la URL/key del túnel. |
| `feature/badges/GamificationProfileScreen.kt` | Parámetros por defecto honestos: `Piloto`, `Sin club`, 0 XP, 0 km, 0 relays, 0 SOS y vitrina vacía. El catálogo canónico sigue disponible solo para tests. |
| `feature/profile/ProfileScreen.kt` | Perfil inicial sin alias, correo ni motocicleta inventados. Se eliminaron los fallbacks `Capitán Oscar`, `BMW R1250GS`, `piloto@rutabikersync.com`, `CO` y el club fijo `Águilas del Camino MC`. |
| `feature/groups/Phase1ViewModel.kt` | `ensureClubLoaded` ya no inyecta el club `Aguilas del Camino` con dos miembros falsos; solo selecciona clubes cargados desde el backend. |
| `feature/groups/ClubRepository.kt` | Reportes de moderación y votaciones de líder arrancan vacíos (antes `rep-demo-01`, `vote-demo-01` con peers inventados). |
| `feature/groups/Phase1Screens.kt` | Fallbacks de líder/código QR/pass de club pasan a `Sin líder registrado` / vacío / `Sin club`. El botón del panel DEV ahora dice «Restablecer vista real (sin datos demo)». |
| `feature/auth/WelcomeAuthScreen.kt` | Eliminados los accesos rápidos «Cuentas de prueba oficiales» con correos e identidades de prueba. |
| `feature/auth/RoleSelectionScreen.kt` | El rol ya no se preselecciona por correos hardcodeados; lo elige siempre la persona. |
| `feature/routes/RideHistoryScreen.kt` | Eliminados los botones «Demo» y «Cargar 3 Rodadas Demo». `loadDemoHistory()` queda únicamente como fixture de pruebas unitarias, sin acceso desde la UI ni desde intent de `MainActivity`. |
| `feature/routes/RideScheduleRepository.kt`, `RideScheduleDomain.kt` | `clubName` por defecto vacío en rodadas registradas. |
| `res/values*/strings.xml` | Texto del panel DEV actualizado en español, inglés y portugués. |

## 2. Compilación e instalación

```
.\gradlew.bat :app:assembleStandardDebug --no-daemon --rerun-tasks
```

con `RBS_PILOT_KEY` / `RBS_PILOT_BUILD` propios por probador:

| Dispositivo | Serial | APK | Tamaño |
|---|---|---|---|
| TECNO | `098813734B105395` | `artifacts/pilotos/piloto-tecno.apk` | 104419640 bytes |
| Redmi | `b2c398bb` | `artifacts/pilotos/piloto-redmi.apk` | 104419640 bytes |

Ambos instalados con `adb install -r` → `Success`. Se conservaron las preferencias
del túnel; la migración `pilot_clean_v2` solo resetió la gamificación.

## 3. Verificación en dispositivo

Capturas y volcados UI en `docs/evidence/mobile_demo_cleanup_20261122/`:

- `ui_tecno.xml`, `ui_perfil_tecno.xml`: pantalla de acceso sin cuentas de prueba.
- `ui_redmi.xml`, `ui_perfil_redmi.xml`: bitácora en cero — `Sin registros activos`,
  `0.0 Km Totales`, `0.0 Km en Malla`, `+0 XP Obtenido`, `0 Rodadas`, sin botón Demo.
- `perfil_*.png`, `perfil2_*.png`: capturas de ambas pantallas.
- Búsqueda de `Comandante Águila|Capitán Oscar|Águilas del Camino|Halcones del Valle|
  1680 km|Rodadas Demo|Cuentas de prueba|Xiaomi Note 13|TECNO (Líder)|Rider Ozmar`
  en los 4 volcados: **0 coincidencias**.
- Base de datos tras abrir y detener las apps: `users=0`, `groups=0`, `rides=0`,
  `pilot_devices=0`, `pilot_device_events=0`. Sigue virgen.

## 4. Pruebas

- Dirigidas a las áreas tocadas: **64 tests, 0 fallos, 0 errores** (`BUILD SUCCESSFUL`)
  con `--tests` para `GamificationProfileScreenTest`, `RideHistoryScreenTest`,
  `ProfileSupportTest`, `Phase1RulesTest`, `PeerPositionValidityTest`,
  `LiveMapViewModelTest`, `UniqueAnnouncerPhase3Test`, `PeerHealthMonitorTest`,
  `MeshValidationPhase0Test` y `HeartbeatBatteryPhase3Test`.
- Suite JVM completa: **491 tests, 1 fallo por flake preexistente**. El error es
  `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest` causado por
  `Module with the Main dispatcher had failed to initialize / main looper is not
  available`, originado en `UniqueAnnouncerPhase3Test.kt:212`. Cambia de test
  víctima entre corridas. Se reprodujo **idénticamente en un worktree limpio en
  HEAD** (`d327fae`, sin estos cambios): mismos 491 tests y mismo fallo en
  `LiveMapViewModelTest`. No es una regresión de esta limpieza.

## 5. Validación física 5–50 m — NO EJECUTADA

No fue posible realizar la prueba de campo en este momento: este entorno no tiene
los dos dispositivos en movimiento ni puede generar una separación real variable
entre 5 y 50 m. **No se ha simulado ni inventado evidencia de esa prueba.**

Queda pendiente para cuando puedan realizarse recorridos reales:

1. Separación variable 5–50 m entre TECNO y Redmi con malla BLE activa.
2. Registrar distancia medida, packets/heartbeats, latencia y pérdidas por tramo.
3. Guardar logs y capturas en `docs/evidence/field_movement_20261122_v3/`.
4. Confirmar con `/v1/support/devices` y `/soporte` que la telemetría recibida
   corresponde a esos dos dispositivos reales.

El push y el PR se realizan con esta limitación documentada, según autorización
explícita del usuario.
