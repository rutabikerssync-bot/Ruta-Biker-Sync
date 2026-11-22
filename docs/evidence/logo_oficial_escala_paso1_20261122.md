# Paso 1 — Logo oficial y escala visual en RODADAS (2026-11-22)

Rama `feat/logo-oficial-escala`, creada desde `238a04d` (`feat/piloto-seguridad`).
Sin push. No se tocó `D:\Ruta-Biker-Sync`.

## 1. Origen del logo y discrepancia detectada

Archivo fuente indicado: `C:\Users\ozmar\Downloads\Gemini_Generated_Image_crdwu9crdwu9crdw.png`.

| Propiedad | Diagnóstico del plan | Medición real |
|---|---|---|
| Resolución | 2090 × 2336 | 2090 × 2336 ✅ |
| Modo | RGBA transparente | RGBA, **alfa 255 en el 100 % de los píxeles** ❌ |
| Fondo | transparente | fondo oscuro opaco (mediana RGB ≈ 29/24/30) ❌ |
| Asset previo | — | `ic_logo_ruta_biker_sync.webp`, 512 × 512, con alfa real (esquina transparente) |

El archivo fuente **no trae transparente**, así que copiarlo tal cual habría generado
un rectángulo oscuro dentro del badge circular. La herramienta de edición de imagen
disponible falló con `Invalid credential`, por lo que se usó un recorte determinista
y auditable, sin regenerar ni redibujar el emblema:

1. `cv2.grabCut` con fondo definitivo en márgenes del 6 %, primer plano probable en
   la región central y semillas de primer plano en píxeles con luminancia > 150 y
   calidez `R+G-2B > 22` (152 234 semillas), 6 iteraciones.
2. Conservación exclusiva del componente conectado de mayor área.
3. Desenfoque Gaussiano 3×3 del canal alfa.
4. Recorte al bbox del alfa `(126, 139, 1966, 2192)` y reescalado Lanczos a
   **1024 × 1143**, preservando la relación 1:1.116.
5. Exportación WebP con alfa, `quality=92`, `method=6`, `exact=True`.

Resultado: `android/app/src/main/res/drawable/ic_logo_ruta_biker_sync.webp`,
386 518 bytes, alfa 0–255, esquinas transparentes, cobertura de primer plano
47,46 % y cobertura central 85,6 %. El perfil por filas (angosto arriba, ancho en
el centro, angosto abajo) es coherente con un escudo alado; **la aprobación visual
final en el TECNO sigue pendiente** porque este entorno no puede inspeccionar la
imagen.

## 2. Cambios de código

| Archivo | Cambio |
|---|---|
| `ui/navigation/MainAppScaffold.kt` | Cabecera RODADAS: `badgeSize` 46 → **58 dp**, `iconSize` 34 → **46 dp**, `glowColor` y nuevo `borderGold` en `Color(0xFFC5A059)`. Descripción accesible corregida a «Logo oficial Ruta Biker Sync». |
| `ui/components/HeritageComponents.kt` | `HeritageEmblemBadge` acepta `borderGold: Color? = null`; cuando se provee, el bisel oscuro usa un degradado Heritage con ese tono. Las dos llamadas existentes (Bóveda, botiquín) mantienen su aspecto porque el parámetro por omisión es `null`. La rama `painter` sigue sin aplicar `tint`, por lo que el logo conserva sus colores originales. |
| `feature/splash/SplashScreen.kt` | `ContentScale.Fit` explícito y descripción accesible actualizada; el splash ya apuntaba al mismo recurso, así que hereda el nuevo asset sin cambiar su composición. |
| `feature/auth/WelcomeAuthScreen.kt` | El escudo del acceso pasa de `ic_gold_winged_crest` al logo oficial, con `ContentScale.Fit` para evitar deformación. |

`ContentScale.Fit` evita el estiramiento por la relación de aspecto no cuadrada;
el contenedor circular existente actúa como máscara.

## 3. Verificación

- `:app:testStandardDebugUnitTest --tests '*RideCabinaAccessTest'` → `BUILD SUCCESSFUL`.
- Suite JVM completa `:app:testStandardDebugUnitTest --rerun-tasks` →
  **491 pruebas, 0 fallos, 0 errores**, `BUILD SUCCESSFUL` (esta corrida no
  reprodujo el flake `UncaughtExceptionsBeforeTest` documentado previamente).
- Compilación de recursos y Kotlin de `standardDebug` correcta; el WebP con alfa
  se empaqueta sin errores de `aapt`.

## 4. Instalación en TECNO

```
RBS_PILOT_KEY=<clave local del probador> RBS_PILOT_BUILD=PILOTO-TECNO-2026 \
  .\gradlew.bat :app:assembleStandardDebug --no-daemon
adb -s 098813734B105395 install -r artifacts/pilotos/piloto-tecno-logo-paso1.apk
```

`BUILD SUCCESSFUL`, APK de 105 187 832 bytes, `Success` en la instalación.
Capturas y volcado UI en `docs/evidence/logo_paso1_tecno_20261122/`:
`splash.png`, `pantalla.png` y `ui.xml`. El volcado confirma el nuevo
`content-desc="Logo oficial Ruta Biker Sync"` en la pantalla de acceso; el TECNO
no tiene sesión iniciada, por lo que la cabecera RODADAS aún no es alcanzable
en ese dispositivo.

## 5. Pendiente

- Revisión visual en el TECNO: logo grande, nítido, sin halo oscuro y con bisel oro
  Heritage en la cabecera RODADAS; splash y acceso usando el mismo emblema.
- Si el recorte automático deja halo o recorta algún detalle, regenerar el asset
  con el archivo fuente original y ajustar los parámetros aquí documentados.
- Pasos 2–4 del plan maestro no ejecutados en este commit.
