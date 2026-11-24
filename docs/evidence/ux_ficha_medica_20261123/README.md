# Ficha Médica de Emergencia — franja táctica a sangre

## Cambio

- El encabezado de `Ficha Médica de Emergencia` pasa de un `Row` plano a una franja de atención médica táctica: rescate en carretera, cruz médica, chapa de grupo sanguíneo y botiquín IFAK, con Dark Slate, oro y rojo emergencia.
- Contenedor: `fillMaxWidth()` + `clip(RoundedCornerShape(12.dp))`.
- Imagen: `R.drawable.card_ficha_medica_emergencia` con `Modifier.matchParentSize()` y `ContentScale.Crop`: queda **detrás del título, la pastilla, grupo sanguíneo y donante de órganos**.
- Degradado idéntico al de la tarjeta de Bóveda vacía: `Color.Transparent` → `Color(0xFF0F172A).copy(alpha = 0.55f)` → `Color(0xFF0F172A)`. Sin overlay negro ni fondos translúcidos en los badges: la foto se ve nítida.
- Encabezado: `Box` de `height(85.dp)`, `Row` al frente con `padding(horizontal = 12.dp, vertical = 8.dp)`, título en blanco y glow rojo `0xFFEF4444` en el emblema.
- Badges de sangre y donante: sin fondo, borde oro sólido `0xFFD4AF37`, valores en blanco.

## Asset

- Origen: `C:\Users\ozmar\Downloads\Gemini_Generated_Image_fcf2zdfcf2zdfcf2.png` (2752×1536, 8.4 MB).
- Resultado: `android/app/src/main/res/drawable/card_ficha_medica_emergencia.webp` (1280×714, 102 KB, WebP q82).

## Verificación

- Suite JVM: **91 suites, 516 tests, 0 fallos, 0 errores** (`--rerun-tasks`).
- APK: `android/app/build/outputs/apk/standard/debug/app-standard-debug.apk`
- SHA-256: `92082FA6950B9CB184FA881B98F90C9F2622DC32DDBA6D4D95D8A799DA1B59CA`
- Dispositivo: TECNO `098813734B105395`.
- UI dump: `Ficha Médica de Emergencia`, `🟢 Ficha Completa`, `GRUPO SANGUÍNEO`, `O+`, `DONANTE ÓRGANOS`, `Registrado`.
- `tecno_ficha_medica.png`: foto nítida en la parte superior (media ~95, desviación ~55) y badges sin fondo que la dejan ver bajo el degradado inferior.
