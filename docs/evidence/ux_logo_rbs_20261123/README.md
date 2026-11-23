# Evidencia — Logo RBS 1.2 y hero de Soporte (2026-11-23)

## Objetivo
Reemplazar todos los logos predefinidos por `logo_RBS-1.2.png` y mostrar el
escudo a tamaño hero en Soporte, sin el fondo desenfocado y centrado.

## Fuente y derivados
- Maestra repo: `assets_branding/logo_RBS_master.png` (copia de `C:\Users\ozmar\Downloads\logo_RBS-1.2.png`).
- Recorte content-aware: `(40, 0, 810, 960)`; el emblema ocupa `x=66..786`, `y=27..936`.
- Generador: `scripts/adapt_logo.py` produce los 3 drawables, los 10 mipmaps y
  los 5 assets web. Conserva todos los nombres de archivo, así que no hubo
  cambios en Kotlin (excepto el hero nuevo), `AndroidManifest.xml` ni HTML.
- `ic_logo_rbs_transparent.png`: variante para Soporte con fondo transparente
  (GrabCut sobre la maestra, recorte al bounding box y canvas cuadrado centrado).
  No sustituye al WebP principal, que conserva su fondo para splash/login.

## Hero de Soporte
- `SupportScreen.kt`: `Box` de `220.dp` centrado con
  `Modifier.align(Alignment.CenterHorizontally)`, fondo `#0F172A`, borde
  `#D4AF37`, esquinas `16.dp`.
- Imagen `R.drawable.ic_logo_rbs_transparent` con `ContentScale.Fit`,
  `Alignment.Center` y `padding(12.dp)`.
- Sin opacidades ni overlays: el fondo verde/oliva del WebP desenfocado ya no
  aparece en esta pantalla.

## Verificación
- Suite: **92 suites / 520 tests / 0 fallos / 0 errores**,
  `BUILD SUCCESSFUL` con `--rerun-tasks`.
- `LogoAssetsContractTest.kt`: exige la maestra nueva, los 19 assets, el hero
  transparente, el tamaño 220 dp, el centrado y las dimensiones de los PNG.
- `android/app/src/test/resources/META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory`:
  corrige una condición de carrera latente del dispatcher `Main` en tests JVM
  (`UncaughtExceptionsBeforeTest` en `LiveMapViewModelTest`), sin tocar lógica
  de mapa, GATT ni transporte.
- APK final: SHA-256 `62AB7AE741D4CEBE540C00F20F50B1D2B47720CAA4EB3B4E06F23174F9591C80`.

## Hardware (TECNO `098813734B105395`)
- `tecno_splash_logo.png`: splash con el logo nuevo.
- `tecno_rodadas_logo.png`: cabecera de Rodadas.
- `tecno_soporte_logo_hero.png`: hero de Soporte. Bounds del logo
  `[164,284][556,676]`; centro horizontal exacto en `x=360` de una pantalla de
  `720 px`, sin fondo verde y con el borde dorado.
