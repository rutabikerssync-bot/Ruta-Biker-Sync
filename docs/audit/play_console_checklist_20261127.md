# Checklist ejecutable de Google Play — Fase 4 (2026-11-27)

**Objeto:** dejar trazabilidad de lo que se pudo verificar en el repositorio y de lo que **solo** puede completarse en Play Console o con secretos/URL reales. No se inventan cuentas, testers, URL productiva ni resultados de Console.

**Leyenda:** `VERIFICADO` = evidencia en repo · `NO CUMPLE` = evidencia en contra · `PENDIENTE` = requiere acción externa · `NO VERIFICABLE` = depende de Console.

## 1. Estado reproducible en este repositorio

| Requisito | Estado | Evidencia / comando | Acción restante |
|---|---|---|---|
| API 36 obligatoria (target/compile SDK) | **NO CUMPLE** | `android/app/build.gradle.kts:15` `compileSdk = 35`, `:46` `targetSdk = 35`. `Get-ChildItem C:\Users\ozmar\AppData\Local\Android\Sdk\platforms -Directory` → solo `android-35` y `android-37.0`. | Instalar `platforms;android-36` con Android SDK Manager, subir ambos valores a 36, ejecutar tests y assemble, verificar AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01 / MapLibre 11.5.2. **No se subió a 36 sin el SDK instalado.** |
| Firma release / upload key | **PARCIAL** | `C:\rbs-secrets\rbs-release-v2.jks` existe. `build.gradle.kts:93-139` falla en configuración si faltan las cuatro variables; nunca usa firma debug. | Definir fuera de Git `RBS_KEYSTORE_PATH`, `RBS_KEYSTORE_PASSWORD`, `RBS_KEY_ALIAS`, `RBS_KEY_PASSWORD`. No versionar secretos; no regenerar la clave sin decidir historial de firma. |
| URL productiva y gate de release | **NO CUMPLE** | `.\scripts\verify_release_security.ps1` → 3 `OK` (sin cleartext, `networkSecurityConfig`, componentes `exported=false`) y `FAIL_PLACEHOLDER` con `BASE_URL=https://rbs-pending.invalid`. Evidencia: `docs/evidence/google_play_fase4_20261127/verify_release_security.log`. | Proveer `RBS_PROD_URL` HTTPS real (`-Prbs.prod.url` alternativo) y repetir el gate hasta `PASS` completo. |
| AAB firmado | **NO ENCONTRADO** | Sin `*.aab` en disco (`Get-ChildItem android -Recurse -Filter *.aab`). | Tras API 36 + URL real + secretos: `.\gradlew.bat :app:bundleStandardRelease --no-daemon`; registrar SHA-256; subir a pruebas internas. |
| `versionCode` / `versionName` | **VERIFICADO** | `build.gradle.kts:47-48` → `1` / `0.1.0`. | Mantener `1` en el primer upload e incrementar en cada AAB posterior. |
| Política de privacidad pública | **PREPARADA / NO PUBLICADA** | `backend/public/privacy.html`, `backend/public/terms.html`, `backend/public/account-deletion.html`; `backend/public/index.html` ya no enlaza `#` en ese bloque. `backend/test/public_legal_resources.test.ts` verifica enlaces y contenido. | Desplegar el backend con URL HTTPS pública, copiar la URL en App content → Privacy policy y en la ficha de tienda. **La URL pública no existe todavía.** |
| Recurso web de borrado | **PREPARADO / NO PUBLICADO** | `backend/public/account-deletion.html` documenta in-app `Perfil → Eliminar Cuenta y Datos`, correo de soporte y el endpoint autenticado `DELETE /v1/users/me`. No hay formulario web con borrado automático. | Publicar la página y confirmar en Console que el recurso cumple el requisito de cuenta. |
| Data Safety | **PENDIENTE** | Solo borrador `docs/GOOGLE_PLAY_DATA_SAFETY.md`; `CHECKLIST.md` corregido a `[~]`. | Enviar el formulario con los datos reales: FCM, MapLibre, ubicación efímera de rodada, ficha médica local, voz P2P, exportación y borrado. |
| IARC / clasificación | **NO ENCONTRADO** | Sin evidencia en repo. | Completar cuestionario IARC en App content. |
| Anuncios y audiencia | **NO ENCONTRADO** | Sin SDK de ads; hay banners propios en la web. | Declarar si los banners propios cuentan como *house ads*; declarar grupo de edad y si aplica la política Families. |
| Acceso de revisores / sign-in | **NO ENCONTRADO** | App con autenticación; sin cuenta de prueba documentada. | Crear cuenta de prueba válida contra la URL real y cargarla en App content → Sign-in details. |
| Ficha, capturas, email de soporte | **PARCIAL** | `soporte@rutabikersync.com` verificado en `android/.../SupportScreen.kt:323` y enlazado desde la web. Sin capturas subidas ni ficha en Console. | Completar descripción, capturas, categoría y correo de soporte. |
| Permisos sensibles | **DISCREPANCIA DOCUMENTAL PARCIAL** | `AndroidManifest.xml:9-22` declara Bluetooth `neverForLocation` y **no** declara `ACCESS_BACKGROUND_LOCATION`; `docs/GOOGLE_PLAY_DATA_SAFETY.md:10` y `CHECKLIST.md:116` aún citan el permiso eliminado (se añadió nota de actualización). | Declarar solo los permisos del manifest y adjuntar justificación de ubicación en primer plano. |
| Prueba cerrada 12 testers / 14 días | **NO ENCONTRADO** | `docs/PLAY_TEST_SETUP.md:26-27` solo prevé dos cuentas. | Inscribir ≥12 testers de forma continua 14 días, documentar feedback y pedir acceso a producción. |
| Cuenta de desarrollador, pago e identidad | **NO VERIFICABLE** | Sin evidencia en repo. | Confirmar en Console pago US$25, verificación de identidad y, si aplica, dispositivo físico verificado. |
| Catálogo de facturación / RTDN | **NO VERIFICABLE** | Sin registro en repo. | Solo si se monetiza: crear productos, RTDN y license testers antes de compras reales. |

## 2. Orden de ejecución para el responsable de Play Console

1. **Cuenta y app:** confirmar pago, identidad, dispositivo verificado y package `com.rutabikersync.app` (no modificable después).
2. **Release técnica:** instalar SDK 36 → subir `compileSdk`/`targetSdk` → `.\gradlew.bat :app:testStandardDebugUnitTest --rerun-tasks --no-daemon` → `.\gradlew.bat :app:assembleStandardDebug --no-daemon` → validar dependencias → definir secretos y `RBS_PROD_URL` → `.\scripts\verify_release_security.ps1` en `PASS` completo.
3. **AAB:** `.\gradlew.bat :app:bundleStandardRelease --no-daemon`; guardar SHA-256 y subir a pruebas internas; verificar arranque contra backend real.
4. **Web legal:** desplegar `backend/public/privacy.html`, `terms.html` y `account-deletion.html` bajo HTTPS; pegar la URL de privacidad en App content y ficha.
5. **App content:** Data Safety, anuncios, audiencia, IARC, permisos, sign-in details, ficha de tienda, capturas y email de soporte.
6. **Monetización** (si aplica): catálogo, RTDN y license testers.
7. **Prueba cerrada:** ≥12 testers / 14 días con feedback documentado.
8. **Producción:** solicitar acceso y publicar; registrar fecha, versión y SHA-256 del AAB en `docs/HISTORY_NOTE.md`.

## 3. Evidencia generada en esta fase

- `docs/evidence/google_play_fase4_20261127/verify_release_security.log`: 3 controles OK y `FAIL_PLACEHOLDER`.
- `docs/evidence/google_play_fase4_20261127/process_standard_release_manifest.log`: `processStandardReleaseManifest` en verde.
- `docs/evidence/google_play_fase4_20261127/npm_audit.json`: 11 vulnerabilidades (2 críticas, 2 altas, 7 moderadas); `zod` alta en runtime queda para Fase 5.
- `docs/evidence/google_play_fase4_20261127/README.md`: cómo reproducir cada resultado.
