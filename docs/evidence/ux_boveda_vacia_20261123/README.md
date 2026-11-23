# Bóveda vacía — cabecera blindada a sangre

## Cambio

- La tarjeta de estado vacío de Documentos ya no es un `Box` plano con `padding(28.dp)`.
- `card_boveda_vacia.webp` ocupa **toda la tarjeta**: `Modifier.matchParentSize()`, `ContentScale.Crop`, `Alignment.Center`.
- Degradado superior→inferior: `Color.Transparent` → `Color(0xFF0F172A).copy(alpha = 0.55f)` → `Color(0xFF0F172A)`.
- Texto y botón se mantienen anclados abajo con `padding(horizontal = 16.dp, vertical = 12.dp)`:
  - `Tu Bóveda está vacía`
  - `Guarda tu licencia, seguro y ficha médica para que estén protegidos y disponibles cuando los necesites.`
  - `Registrar Primer Documento`

## Asset

- Origen: `C:\Users\ozmar\Downloads\card-boveda.png` (2750×1536, 6.9 MB).
- Resultado: `android/app/src/main/res/drawable/card_boveda_vacia.webp` (1280×715, 67 KB, WebP q82).

## Verificación

- Suite JVM: **90 suites, 514 tests, 0 fallos, 0 errores** (`--rerun-tasks`).
- APK: `android/app/build/outputs/apk/standard/debug/app-standard-debug.apk`
- SHA-256: `AB346F666C32BCB068687F7EE463EE15BC9D18597C7A4CCA91848B6AE4756C11`
- Dispositivo: TECNO `098813734B105395`.
- `tecno_boveda_vacia.png`: imagen cubriendo toda la tarjeta, fundido a Dark Slate y botón visible.
- El botón `Registrar Primer Documento` abre `Agregar Documento a la Bóveda` (`Cancelar` / `Cifrar y Guardar`).
