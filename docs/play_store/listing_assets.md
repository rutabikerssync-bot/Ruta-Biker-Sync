# Assets de ficha — icono, gráfico y capturas

**Estado:** ASSETS PREPARADOS; **NO subidos a Play Console**.
**Fecha:** 2026-11-27 · **Dispositivo de captura:** `b2c398bb`, Android 16 / SDK 36, `1220x2712` nativo.

## 1. Icono de la app (512×512)

| Campo | Valor |
|---|---|
| Archivo a subir | `docs/play_store/assets/icon_512.png` |
| Origen | `android/app/src/main/res/drawable/ic_logo_rbs_transparent.png` |
| Dimensiones verificadas | `512×512`, modo `RGBA` |
| Tamaño | `320,471` bytes (< 1 MB) |
| Estado | **EXISTENTE**; copiado a `assets/` para el upload |

Google Play: icono cuadrado de 512 px, PNG/JPEG, ≤ 1 MB. No se generó uno nuevo porque ya existía uno válido en el repositorio.

## 2. Gráfico promocional (1024×500)

| Campo | Valor |
|---|---|
| Archivo a subir | `docs/play_store/assets/feature_graphic_1024x500.png` |
| Dimensiones verificadas | `1024×500`, modo `RGB` |
| Tamaño | `153,238` bytes |
| Estado | **GENERADO localmente** |

El servicio externo de generación de imágenes falló con `Invalid credential`; no se reintentó. El gráfico se compuso localmente con Pillow a partir del logo existente: fondo azul/charcoal, línea de ruta ámbar, motivos de malla y el logo. No contiene datos inventados ni texto de marca nuevo.

## 3. Capturas de pantalla

Mínimo 2, máximo 8. Se prepararon **4 capturas reales** del dispositivo físico, recortadas al centro y redimensionadas a `1080×2160` (ratio 1:2, dentro de los límites de Play).

| Orden | Archivo | Contenido | Dimensiones |
|---:|---|---|---|
| 1 | `docs/play_store/assets/screenshots/01_rodadas.png` | Inicio/Rodadas, Bitácora y entrada a Ruta Libre | `1080×2160` |
| 2 | `docs/play_store/assets/screenshots/02_perfil.png` | Perfil del piloto y progreso (sin correo ni datos sensibles) | `1080×2160` |
| 3 | `docs/play_store/assets/screenshots/03_miclub.png` | Pestaña Mi Club / gestión del club | `1080×2160` |
| 4 | `docs/play_store/assets/screenshots/04_privacidad.png` | Perfil → Ajustes → Privacidad y términos, pestaña Privacidad | `1080×2160` |

- Log de captura: `docs/play_store/assets/screenshots/capture_log.txt`.
- Las capturas se obtuvieron con `adb shell screencap` + `adb pull` para evitar corrupción binaria por redirección de PowerShell.
- Revisar visualmente antes de subir: no se incluyó la pantalla de ajustes con correo, ni overlays de depuración.
- Requisitos Play aplicables: PNG/JPEG 24 bits, 320–3840 px, ratio 1:2 a 2:1, ≤ 15 MB. Las 4 cumplen.

## 4. Cómo subirlas en Console

1. Store presence → **Store listing** → *App icon*: subir `assets/icon_512.png`.
2. *Feature graphic*: subir `assets/feature_graphic_1024x500.png`.
3. *Phone screenshots*: subir las 4 capturas en el orden de la tabla.
4. *Graphic assets* → guardar.
5. Adjuntar captura de la ficha terminada en `docs/evidence/google_play_publicacion_20261127/` (PENDIENTE; requiere Console).

## 5. Textos de la ficha

Los textos ya están en `docs/play_store/listing.md`: título `Ruta Biker Sync`, descripción corta y completa, categoría `Deportes`, contacto `soporte@rutabikersync.com` y sitio `https://ruta-biker-sync.onrender.com`. La URL de política de privacidad debe completarse tras verificar `/privacy.html` con `docs/play_store/render_deploy.md`.
